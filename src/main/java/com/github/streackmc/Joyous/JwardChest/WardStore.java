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
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardStore {

  /** 编码格式版本；改动编码方式时递增，旧数据会被判定为不兼容而重抽 */
  private static final int FORMAT_VERSION = 1;

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
          + " WHERE `profile_id`=" + lit(id.toString()) + " LIMIT 1");
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
    String idLit = lit(id.toString());
    String payloadLit = lit(payload);
    long now = System.currentTimeMillis();
    try (SdbAction action = db.act()) {
      action.apply("DELETE FROM " + tableName + " WHERE `profile_id`=" + idLit);
      action.apply("INSERT INTO " + tableName
          + " (`profile_id`,`generated`,`payload`,`updated_at`) VALUES ("
          + idLit + "," + (generated ? 1 : 0) + "," + payloadLit + "," + now + ")");
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
    exec("DELETE FROM " + tableSql + " WHERE `profile_id`=" + lit(id.toString()));
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

  /** 表名只允许字母、数字与下划线（表名无法参数化，必须自己清洗） */
  private static String sanitizeTable(String raw) {
    String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
    return cleaned.isEmpty() ? "joyous_jwardchest" : cleaned;
  }

  /** SQL 字符串字面量转义（我们写入的值都是 UUID / Base64 / 数字，理论上无需转义，仍做一层保险） */
  private static String lit(String value) {
    if (value == null) {
      return "NULL";
    }
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
  }

  /** 存档层异常（供调用方统一反馈） */
  public static final class WardStoreException extends Exception {
    private static final long serialVersionUID = 1L;

    public WardStoreException(String message, Throwable cause) {
      super(message + (cause == null || cause.getMessage() == null ? "" : "：" + cause.getMessage()), cause);
    }
  }
}
