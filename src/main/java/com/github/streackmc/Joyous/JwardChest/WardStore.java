package com.github.streackmc.Joyous.JwardChest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.types.SDatabase.SdbAction;
import com.github.streackmc.StreackLib.types.SDatabase.SdbDataEntry;
import com.github.streackmc.StreackLib.types.SDatabase.SdbDatabase;

/**
 * 容器内容的持久化（StreackLib 的 Sdb 系列）
 * <p>
 * 存的是「这个容器里现在装着什么」——也就是 {@link WardSession} 的权威内容。
 * 之所以要落库：内容只能因为「命令重置」或「会话过期」刷新，服务器重启不应该让箱子凭空补货。
 * <p>
 * 表结构（一张扁平表，一行一个容器）：
 * <pre>
 *   profile_id  VARCHAR(64) PRIMARY KEY   容器 UUID
 *   generated   INT                       内容是否已生成
 *   payload     MEDIUMTEXT                槽位内容（见 {@link #encode(List)}）
 *   updated_at  BIGINT                    最后写入时间（毫秒）
 * </pre>
 * <p>
 * <b>错误处理</b>：所有数据库异常都会被包成 {@link WardStoreException} 抛出，由调用方
 * （{@link JwardChestMain}）统一记录并以玩家可读的方式反馈——绝不静默吞掉。
 * 这样即使 Sdb 内部出问题，也能一眼看出是「存档失败」而不是「箱子行为诡异」。
 * <p>
 * <b>写入策略</b>：每次内容变更都立即同步写一次（先 DELETE 再 INSERT，同一事务内）。
 * 领取/放入是人的动作，频率极低，换来的是「写入失败即回滚内存」这个简单可靠的语义。
 * <p>
 * <b>SQL 安全边界（CWE-89）</b>：这里的 SQL 是<b>拼接</b>出来的，因为当前 StreackLib 的
 * {@code SdbActionContext} 还不支持参数化写入——{@code UPDATE} 的 SET 子句会直接抛
 * {@code UnsupportedOperationException}，参数化分支只会生成 {@code UPDATE tbl SET ?}，
 * {@code MERGE} 也同样残缺；{@code act(String)} 走的是 {@code Statement}，没有参数位。
 * 既然只能拼，就把「能拼什么」收紧成<b>白名单</b>：
 * <ul>
 *   <li>标识符（表名）：{@link #sanitizeTable(String)} 只留 {@code [A-Za-z0-9_]}，再包反引号；</li>
 *   <li>值：{@link #quoteValue(String)} <b>不做转义而是断言</b>，只接受
 *       {@code [A-Za-z0-9+/=_.:@-]}（即 UUID、Base64、数字）。</li>
 * </ul>
 * 于是「往 SQL 里加一个自由文本字段」这件事会在运行时立刻失败，而不是悄悄拼出一条可注入的语句。
 * <b>真要做，正确的顺序是：先把 StreackLib 升到支持 PreparedStatement 的版本，再改用参数化 API</b>，
 * 不要靠扩展白名单来放行新字段（白名单一放宽，这道防线就没了）。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardStore {

  /** 编码格式版本；改动编码方式时递增，旧数据会被判定为不兼容而重抽 */
  private static final int FORMAT_VERSION = 1;

  /**
   * 允许直接拼进 SQL 的值的字符集
   * <p>
   * 覆盖：UUID（十六进制与连字符）、标准/URL 安全 Base64、数字、以及少量无害标点。
   * 不含单双引号、反斜杠、分号、空格、括号、百分号——这些是拼 SQL 时唯一有意义的东西。
   */
  private static final java.util.regex.Pattern SAFE_VALUE =
      java.util.regex.Pattern.compile("[A-Za-z0-9+/=_.:@\\-]*");

  private final SdbDatabase db;
  private final String table;
  private final String tableSql;
  private volatile boolean ready;

  /**
   * @param db    数据库（{@code Joyous.database}）
   * @param table 表名；非法字符会被剔除
   */
  public WardStore(@Nullable SdbDatabase db, @Nullable String table) {
    this.db = db;
    this.table = sanitizeTable(table == null || table.isBlank() ? "joyous_jwardchest" : table);
    this.tableSql = "`" + this.table + "`";
  }

  /** 是否具备持久化能力（没配数据库时为 false，此时容器照常可用但不跨重启） */
  public boolean available() {
    return db != null;
  }

  /** 表是否已就绪 */
  public boolean ready() {
    return ready;
  }

  /** 表名（已清洗） */
  public String table() {
    return table;
  }

  /**
   * 建表（幂等）
   *
   * @throws WardStoreException 数据库操作失败
   */
  public void ensureTable() throws WardStoreException {
    if (db == null) {
      return;
    }
    exec("CREATE TABLE IF NOT EXISTS " + tableSql + " ("
        + "`profile_id` VARCHAR(64) NOT NULL, "
        + "`generated` INT NOT NULL DEFAULT 0, "
        + "`payload` MEDIUMTEXT, "
        + "`updated_at` BIGINT NOT NULL DEFAULT 0, "
        + "PRIMARY KEY (`profile_id`))");
    ready = true;
    jlogger.debug("JwardChest 存档表已就绪：%s", table);
  }

  // ──────────────────────────────────────────────
  // 读写
  // ──────────────────────────────────────────────

  /** 一条容器存档 */
  public record Payload(boolean generated, List<ItemStack> contents) {
  }

  /**
   * 读取一个容器的存档
   * <p>
   * 读写两侧都用<b>原始 SQL</b>：{@code SdbActionContext.table()} 会自动加上 profile 的
   * {@code table_prefix}，而原始 SQL 不会——两边混用就会「写进 A 表、从 B 表读」。
   * 这里统一走原始 SQL，表名就是配置里写的那个（已在构造时清洗掉非法字符）。
   *
   * @param id 容器 UUID
   * @return 存档；没有记录或数据损坏时返回 null（调用方会重新抽取）
   * @throws WardStoreException 数据库操作失败
   */
  @Nullable
  public Payload load(UUID id) throws WardStoreException {
    if (db == null) {
      return null;
    }
    SdbDataEntry entry;
    try {
      entry = db.act("SELECT `generated`,`payload` FROM " + tableSql
          + " WHERE `profile_id`=" + quoteValue(id.toString()) + " LIMIT 1");
    } catch (Exception e) {
      throw new WardStoreException("读取容器存档失败：" + id, e);
    }
    if (entry == null || entry.ResultLines == null || entry.ResultLines.isEmpty()) {
      return null;
    }
    SConfig row = entry.ResultLines.get(0);
    boolean generated = row.getInt("generated", 0) != 0;
    String payload = row.getString("payload", "");
    List<ItemStack> contents = decode(payload);
    if (contents == null) {
      jlogger.warn("JwardChest 容器 %s 的存档无法解析（格式版本不兼容或数据损坏），将重新抽取", id);
      return null;
    }
    return new Payload(generated, contents);
  }

  /**
   * 写入一个容器的存档（整行覆盖）
   * <p>
   * 拼进 SQL 的值只有 {@code profile_id}（UUID）与 {@code payload}（Base64），两者都必须能通过
   * {@link #quoteValue(String)} 的白名单断言。想在这里加自由文本，先去看类注释里的
   * 「SQL 安全边界」——正确做法是升级 StreackLib 并改用参数化 API，而不是放宽白名单。
   *
   * @param id        容器 UUID
   * @param generated 内容是否已生成
   * @param contents  槽位顺序的内容（含 null）
   * @throws WardStoreException 数据库操作失败
   */
  public void save(UUID id, boolean generated, List<ItemStack> contents) throws WardStoreException {
    if (db == null) {
      return;
    }
    String payload = encode(contents);
    String tableName = tableSql;
    String idValue = quoteValue(id.toString());
    String payloadValue = quoteValue(payload);
    long now = System.currentTimeMillis();
    try (SdbAction action = db.act()) {
      action.apply("DELETE FROM " + tableName + " WHERE `profile_id`=" + idValue);
      action.apply("INSERT INTO " + tableName
          + " (`profile_id`,`generated`,`payload`,`updated_at`) VALUES ("
          + idValue + "," + (generated ? 1 : 0) + "," + payloadValue + "," + now + ")");
      action.commit();
    } catch (Exception e) {
      throw new WardStoreException("写入容器存档失败：" + id, e);
    }
  }

  /**
   * 删除一个容器的存档（解除绑定 / 命令重置时用）
   *
   * @param id 容器 UUID
   * @throws WardStoreException 数据库操作失败
   */
  public void delete(UUID id) throws WardStoreException {
    if (db == null) {
      return;
    }
    exec("DELETE FROM " + tableSql + " WHERE `profile_id`=" + quoteValue(id.toString()));
  }

  /**
   * 清空整张表（{@code /jc reset all}）
   *
   * @throws WardStoreException 数据库操作失败
   */
  public void deleteAll() throws WardStoreException {
    if (db == null) {
      return;
    }
    exec("DELETE FROM " + tableSql);
  }

  // ──────────────────────────────────────────────
  // 编码
  // ──────────────────────────────────────────────

  /**
   * 把槽位内容编码成 Base64 字符串
   * <p>
   * 格式（小端不重要，只用 DataOutput 的默认大端）：
   * <pre>
   *   byte   版本
   *   byte   是否已生成
   *   short  槽位数
   *   每个槽：byte 是否有物品；有则 int 长度 + byte[]（ItemStack.serializeAsBytes）
   * </pre>
   */
  static String encode(List<ItemStack> contents) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(FORMAT_VERSION);
      out.writeShort(contents == null ? 0 : contents.size());
      if (contents != null) {
        for (ItemStack item : contents) {
          if (item == null || item.getType().isAir()) {
            out.writeByte(0);
            continue;
          }
          byte[] data = item.serializeAsBytes();
          out.writeByte(1);
          out.writeInt(data.length);
          out.write(data);
        }
      }
    } catch (IOException e) {
      // ByteArrayOutputStream 不会真的抛 IO 异常
      jlogger.warn("JwardChest 编码容器内容失败：%s", e.getLocalizedMessage());
      return "";
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  /**
   * 解码槽位内容
   *
   * @return 槽位顺序的列表（含 null）；格式不认识或数据损坏时返回 null
   */
  @Nullable
  static List<ItemStack> decode(@Nullable String payload) {
    if (payload == null || payload.isBlank()) {
      return new ArrayList<>();
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(payload);
    } catch (IllegalArgumentException e) {
      return null;
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
      int version = in.readByte();
      if (version != FORMAT_VERSION) {
        return null;
      }
      int slots = in.readShort() & 0xFFFF;
      List<ItemStack> contents = new ArrayList<>(slots);
      for (int i = 0; i < slots; i++) {
        int present = in.readByte();
        if (present == 0) {
          contents.add(null);
          continue;
        }
        int len = in.readInt();
        byte[] data = new byte[len];
        in.readFully(data);
        try {
          contents.add(ItemStack.deserializeBytes(data));
        } catch (Exception e) {
          // 单格损坏不该让整箱作废
          contents.add(null);
        }
      }
      return contents;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  private void exec(String sql) throws WardStoreException {
    if (db == null) {
      return;
    }
    try {
      db.act(sql);
    } catch (Exception e) {
      throw new WardStoreException("数据库操作失败", e);
    }
  }

  /**
   * 清洗表名：标识符无法参数化，所以只允许字母、数字与下划线
   * <p>
   * 被剔除过字符时记一条警告——表名是「拼进 SQL 的标识符」，静默改名会让管理员以为数据丢了
   * （明明写的是 {@code my-table}，实际建的是 {@code mytable}）。
   */
  private static String sanitizeTable(String raw) {
    String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
    if (cleaned.isEmpty()) {
      jlogger.warn("JwardChest 配置的表名 [%s] 不含任何可用字符，已回落到 joyous_jwardchest", raw);
      return "joyous_jwardchest";
    }
    if (!cleaned.equals(raw)) {
      jlogger.warn("JwardChest 配置的表名 [%s] 含非法字符，实际使用 [%s]（只允许字母、数字、下划线）",
          raw, cleaned);
    }
    return cleaned;
  }

  /**
   * 把值包成 SQL 字符串字面量，<b>并把「能不能拼」收紧成白名单断言</b>
   * <p>
   * 这里刻意<b>不做转义</b>：转义是「尽力让危险字符变得无害」，一旦哪天漏掉一个字符就静默出事；
   * 断言是「只要出现危险字符就当场失败」，谁想往 SQL 里塞自由文本都会立刻看到异常。
   * <p>
   * 当前调用点只有 UUID 与 Base64，都落在白名单内，所以行为与直接拼接完全一致。
   *
   * @param value 待拼入的值，null 映射为 SQL {@code NULL}
   * @return 可直接拼进 SQL 的字面量
   * @throws UnsafeSqlValueException 值里出现了白名单之外的字符（说明有人想拼自由文本）
   */
  private static String quoteValue(@Nullable String value) {
    if (value == null) {
      return "NULL";
    }
    if (!SAFE_VALUE.matcher(value).matches()) {
      // 先落一条 severe：即使上层把它当普通异常吞了，日志里也一定看得到「有人往 SQL 里塞了自由文本」
      jlogger.severe("JwardChest 拒绝把非白名单字符拼进 SQL（长度 %s），疑似有代码把自由文本写进了存档层",
          value.length());
      throw new UnsafeSqlValueException(value);
    }
    return "'" + value + "'";
  }

  /**
   * 有值想拼进 SQL 但不在白名单内
   * <p>
   * 这不是「用户输入有问题」，而是<b>调用方把不该拼接的数据交给了 SQL</b>。
   * 正确做法见类注释：升级 StreackLib 到支持 PreparedStatement 的版本，改用参数化 API；
   * <b>不要</b>为了让它通过而扩大 {@link #SAFE_VALUE} ——那等于把这道断言拆掉。
   */
  public static final class UnsafeSqlValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    UnsafeSqlValueException(String value) {
      super("拒绝把非白名单字符拼进 SQL（长度 " + value.length() + "）。"
          + "WardStore 只允许 UUID / Base64 / 数字这类可解析的值；"
          + "若要写入自由文本，请先升级 StreackLib 并迁移到参数化（PreparedStatement）API，"
          + "而不是放宽 SAFE_VALUE。");
    }
  }

  /** 存档层异常（供调用方统一反馈） */
  public static final class WardStoreException extends Exception {
    private static final long serialVersionUID = 1L;

    public WardStoreException(String message, Throwable cause) {
      super(message + (cause == null || cause.getMessage() == null ? "" : "：" + cause.getMessage()), cause);
    }
  }
}
