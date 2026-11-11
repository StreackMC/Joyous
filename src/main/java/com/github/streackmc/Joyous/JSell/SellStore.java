package com.github.streackmc.Joyous.JSell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.types.SDatabase.SdbAction;
import com.github.streackmc.StreackLib.types.SDatabase.SdbDataEntry;
import com.github.streackmc.StreackLib.types.SDatabase.SdbDatabase;

/**
 * JSell 的运行时状态存档（StreackLib 的 Sdb 系列）
 * <p>
 * 存三样东西，都是「崩服也不能丢」的那部分状态：
 * <table border="1">
 * <tr><th>表</th><th>内容</th><th>为什么必须落库</th></tr>
 * <tr><td>{@code jsell_escrow}</td><td>玩家放进出售界面、还没结算的物品</td>
 *     <td>界面里的东西已经从背包扣出来了，只存在内存里的话崩服就等于吞了玩家的物品</td></tr>
 * <tr><td>{@code jsell_tx}</td><td>出售事务日志（多阶段提交）</td>
 *     <td>让「物品已消失」与「钱已给」之间的窗口可恢复</td></tr>
 * <tr><td>{@code jsell_usage}</td><td>销量流水</td>
 *     <td>限额必须跨重启累计，否则玩家重启一次就能绕过限购</td></tr>
 * </table>
 *
 * <h3>SQL 安全边界（CWE-89）</h3>
 * StreackLib 的 {@code SdbActionContext} 目前<b>没有 INSERT</b>（只有 SELECT/UPDATE/MERGE/DELETE/
 * CREATE/ALTER/DROP/TRUNCATE），所以写行只能走 {@code act(String)} 的原始 SQL——而那是个
 * {@link java.sql.Statement}，没有参数位。既然只能拼，就把「能拼什么」收紧成白名单：
 * 值只允许 {@link #SAFE_VALUE}（UUID / Base64 / 数字 / 时间戳），
 * <b>任何自由文本（玩家名、命令原文、备注）都必须先塞进 Base64 payload</b>。
 * 表名另外走 {@link #sanitizeTable(String)}。
 * 这不是「转义」而是「断言」：谁想往 SQL 里塞自由文本，会在当场抛异常而不是悄悄拼出一条可注入语句。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellStore {

  /**
   * 事务阶段。每个阶段都对应一句「哪些副作用一定已经发生了」，恢复逻辑只认这个：
   * <ul>
   *   <li>{@link #TX_PREPARED} —— 只写了日志，<b>什么都没做</b>（没给钱、没交付退款）</li>
   *   <li>{@link #TX_COMMITTED} —— 记录已接管物品（托管行已删），但<b>仍然什么都没做</b></li>
   *   <li>{@link #TX_REFUNDED} —— 不可售/超限的部分<b>已经交还给玩家</b></li>
   *   <li>{@link #TX_PAID} —— 钱与附加命令<b>可能已经执行</b>（执行到一半崩了也停在这里）</li>
   * </ul>
   * 因此：PREPARED / COMMITTED 可以安全地「整单退回」，REFUNDED / PAID 只能按已售处理。
   */
  public static final int TX_PREPARED = 0;
  /** 已接管物品（托管已删），副作用仍未发生 */
  public static final int TX_COMMITTED = 1;
  /** 退款部分已交付玩家 */
  public static final int TX_REFUNDED = 2;
  /** 付款命令可能已执行 */
  public static final int TX_PAID = 3;

  /** 允许直接拼进 SQL 的字符集：UUID / 标准与 URL Base64 / 数字 / 少量无害标点 */
  private static final java.util.regex.Pattern SAFE_VALUE =
      java.util.regex.Pattern.compile("[A-Za-z0-9+/=_.:@\\-]*");

  private static final int FORMAT_VERSION = 1;

  private final SdbDatabase db;
  private final String escrowTable;
  private final String txTable;
  private final String usageTable;
  private volatile boolean ready;

  public SellStore(@Nullable SdbDatabase db, @Nullable String escrowTable,
      @Nullable String txTable, @Nullable String usageTable) {
    this.db = db;
    this.escrowTable = sanitizeTable(escrowTable, "joyous_jsell_escrow");
    this.txTable = sanitizeTable(txTable, "joyous_jsell_tx");
    this.usageTable = sanitizeTable(usageTable, "joyous_jsell_usage");
  }

  /** 是否具备持久化能力（没有数据库时为 false：功能照常，但不跨重启） */
  public boolean available() {
    return db != null;
  }

  public boolean ready() {
    return ready;
  }

  public String escrowTable() {
    return escrowTable;
  }

  public String txTable() {
    return txTable;
  }

  public String usageTable() {
    return usageTable;
  }

  /**
   * 建表（幂等）
   * <p>
   * 索引单独建：MySQL 与 SQLite 对 {@code CREATE INDEX IF NOT EXISTS} 的支持不一致，
   * 所以失败只记一条 debug——没有索引只是慢一点，不该让模块起不来。
   */
  public void ensureTables() throws SellStoreException {
    if (db == null) {
      return;
    }
    exec("CREATE TABLE IF NOT EXISTS " + quote(escrowTable) + " ("
        + "`player_id` VARCHAR(64) NOT NULL, "
        + "`payload` MEDIUMTEXT, "
        + "`updated_at` BIGINT NOT NULL DEFAULT 0, "
        + "PRIMARY KEY (`player_id`))");
    exec("CREATE TABLE IF NOT EXISTS " + quote(txTable) + " ("
        + "`tx_id` VARCHAR(64) NOT NULL, "
        + "`player_id` VARCHAR(64) NOT NULL, "
        + "`status` INT NOT NULL DEFAULT 0, "
        + "`amount` DOUBLE NOT NULL DEFAULT 0, "
        + "`payload` MEDIUMTEXT, "
        + "`created_at` BIGINT NOT NULL DEFAULT 0, "
        + "PRIMARY KEY (`tx_id`))");
    exec("CREATE TABLE IF NOT EXISTS " + quote(usageTable) + " ("
        + "`player_id` VARCHAR(64) NOT NULL, "
        + "`entry_id` VARCHAR(64) NOT NULL, "
        + "`amount` INT NOT NULL DEFAULT 0, "
        + "`sold_at` BIGINT NOT NULL DEFAULT 0)");
    tryIndex(usageTable, "idx_jsell_usage", "(`player_id`, `entry_id`, `sold_at`)");
    tryIndex(txTable, "idx_jsell_tx", "(`player_id`)");
    ready = true;
    jlogger.debug("JSell 数据表已就绪：%s / %s / %s", escrowTable, txTable, usageTable);
  }

  private void tryIndex(String table, String index, String columns) {
    try {
      exec("CREATE INDEX " + quote(index) + " ON " + quote(table) + " " + columns);
    } catch (SellStoreException e) {
      // 已存在（MySQL / SQLite 都没有 IF NOT EXISTS）或权限不足，都只是性能问题
      jlogger.debug("JSell 索引 %s 已存在或无法创建，跳过（不影响功能）", index);
    }
  }

  // ──────────────────────────────────────────────
  // 托管（玩家放进界面、尚未结算的物品）
  // ──────────────────────────────────────────────

  /** 读取全部托管记录（启动时调用，用于崩服恢复） */
  public Map<UUID, List<ItemStack>> loadEscrows() throws SellStoreException {
    Map<UUID, List<ItemStack>> result = new LinkedHashMap<>();
    if (db == null) {
      return result;
    }
    List<SConfig> rows = query("SELECT `player_id`,`payload` FROM " + quote(escrowTable));
    for (SConfig row : rows) {
      UUID player = parseUuid(row.getString("player_id", ""));
      if (player == null) {
        continue;
      }
      List<ItemStack> items = decodeItems(row.getString("payload", ""));
      if (items == null) {
        jlogger.warn("JSell 托管记录 %s 无法解析，已跳过（请让管理员人工核对）", player);
        continue;
      }
      if (!items.isEmpty()) {
        result.put(player, items);
      }
    }
    return result;
  }

  /**
   * 覆盖写一个玩家的托管内容
   * <p>
   * 先删后插、同一事务——半条记录比没有记录更危险（{@code INSERT} 成功但 {@code DELETE} 失败
   * 就会读到两份）。
   */
  public void saveEscrow(UUID player, List<ItemStack> items) throws SellStoreException {
    if (db == null) {
      return;
    }
    String id = quoteValue(player.toString());
    String payload = quoteValue(encodeItems(items));
    long now = System.currentTimeMillis();
    String table = quote(escrowTable);
    try (SdbAction action = db.act()) {
      action.apply("DELETE FROM " + table + " WHERE `player_id`=" + id);
      action.apply("INSERT INTO " + table + " (`player_id`,`payload`,`updated_at`) VALUES ("
          + id + "," + payload + "," + now + ")");
      action.commit();
    } catch (Exception e) {
      throw new SellStoreException("写入托管物品失败：" + player, e);
    }
  }

  public void deleteEscrow(UUID player) throws SellStoreException {
    if (db == null) {
      return;
    }
    exec("DELETE FROM " + quote(escrowTable) + " WHERE `player_id`=" + quoteValue(player.toString()));
  }

  /**
   * 读单个玩家的托管内容（上线交付时用）
   * <p>
   * 走主键，比整表载入便宜得多——玩家每次上线都会查一次，不能做全表扫描。
   *
   * @return 托管内容；没有记录返回 null
   */
  @Nullable
  public List<ItemStack> loadEscrow(UUID player) throws SellStoreException {
    if (db == null) {
      return null;
    }
    List<SConfig> rows = query("SELECT `payload` FROM " + quote(escrowTable)
        + " WHERE `player_id`=" + quoteValue(player.toString()) + " LIMIT 1");
    if (rows.isEmpty()) {
      return null;
    }
    List<ItemStack> items = decodeItems(rows.get(0).getString("payload", ""));
    if (items == null) {
      jlogger.warn("JSell 托管记录 %s 无法解析，请让管理员人工核对", player);
      return null;
    }
    return items;
  }

  // ──────────────────────────────────────────────
  // 事务日志（多阶段提交）
  // ──────────────────────────────────────────────

  /**
   * 一条出售事务
   *
   * @param status  见 {@link #TX_PREPARED} 等
   * @param amount  本次结算金额（两位小数）
   * @param sold    本次出售的物品（事务被判定为「取消」时原样退还）
   * @param refunds 不可售 / 超限的部分（要在结算时交还玩家）
   * @param note    人类可读摘要，用于日志与管理员核查（自由文本，存在 payload 里）
   */
  public record Tx(String id, UUID player, int status, double amount, List<ItemStack> sold,
      List<ItemStack> refunds, String note, long createdAt) {

    /** 这条事务还什么都没做，可以整单退回 */
    public boolean cancellable() {
      return status == TX_PREPARED || status == TX_COMMITTED;
    }
  }

  public void createTx(Tx tx) throws SellStoreException {
    if (db == null) {
      return;
    }
    String table = quote(txTable);
    String id = quoteValue(tx.id());
    String player = quoteValue(tx.player().toString());
    String payload = quoteValue(encodeTx(tx.note(), tx.sold(), tx.refunds()));
    String amount = quoteValue(BigDecimal.valueOf(tx.amount()).toPlainString());
    try (SdbAction action = db.act()) {
      action.apply("DELETE FROM " + table + " WHERE `tx_id`=" + id);
      action.apply("INSERT INTO " + table
          + " (`tx_id`,`player_id`,`status`,`amount`,`payload`,`created_at`) VALUES ("
          + id + "," + player + "," + tx.status() + "," + amount + "," + payload + ","
          + tx.createdAt() + ")");
      action.commit();
    } catch (Exception e) {
      throw new SellStoreException("写入出售事务失败：" + tx.id(), e);
    }
  }

  /** 推进事务阶段 */
  public void markStatus(String txId, int status) throws SellStoreException {
    if (db == null) {
      return;
    }
    exec("UPDATE " + quote(txTable) + " SET `status`=" + status
        + " WHERE `tx_id`=" + quoteValue(txId));
  }

  public void deleteTx(String txId) throws SellStoreException {
    if (db == null) {
      return;
    }
    exec("DELETE FROM " + quote(txTable) + " WHERE `tx_id`=" + quoteValue(txId));
  }

  /** 读出全部事务（启动恢复、管理员核查） */
  public List<Tx> loadTxs() throws SellStoreException {
    List<Tx> result = new ArrayList<>();
    if (db == null) {
      return result;
    }
    List<SConfig> rows = query("SELECT `tx_id`,`player_id`,`status`,`amount`,`payload`,"
        + "`created_at` FROM " + quote(txTable) + " ORDER BY `created_at` ASC");
    for (SConfig row : rows) {
      String id = row.getString("tx_id", "");
      UUID player = parseUuid(row.getString("player_id", ""));
      if (id.isEmpty() || player == null) {
        continue;
      }
      Decoded decoded = decodeTx(row.getString("payload", ""));
      result.add(new Tx(id, player, row.getInt("status", TX_PREPARED),
          row.getDouble("amount", 0.0), decoded.sold(), decoded.refunds(), decoded.note(),
          row.getLong("created_at", 0L)));
    }
    return result;
  }

  // ──────────────────────────────────────────────
  // 销量（限额）
  // ──────────────────────────────────────────────

  /**
   * 一次销量查询的结果
   *
   * @param total        窗口内的累计销量
   * @param latestSoldAt 窗口内<b>最近一次</b>销量记录的时间戳（毫秒）；没有记录时为 0
   */
  public record Usage(long total, long latestSoldAt) {
  }

  /**
   * 查某玩家在某个条目上的累计销量与最近一次销量的时间
   * <p>
   * 后者用来算「限额什么时候重置」：窗口是<b>滚动</b>的（只看最近 N 秒的流水），
   * 所以当前这批用量要等到「最后一次卖出 + 窗口长度」才全部过期，
   * 那一刻才是额度恢复满额的时间。
   *
   * @param sinceMillis 只统计该时间点之后的流水；{@code <= 0} 表示不限时间（终身累计）
   */
  public Usage usage(UUID player, UUID entry, long sinceMillis) throws SellStoreException {
    if (db == null) {
      return new Usage(0L, 0L);
    }
    String sql = "SELECT COALESCE(SUM(`amount`),0) AS `total`, "
        + "COALESCE(MAX(`sold_at`),0) AS `latest` FROM " + quote(usageTable)
        + " WHERE `player_id`=" + quoteValue(player.toString())
        + " AND `entry_id`=" + quoteValue(entry.toString())
        + (sinceMillis > 0 ? " AND `sold_at`>=" + sinceMillis : "");
    List<SConfig> rows = query(sql);
    if (rows.isEmpty()) {
      return new Usage(0L, 0L);
    }
    // SUM / MAX 在不同驱动上可能是 DECIMAL / BIGINT / DOUBLE，getLong 都能吃下去
    SConfig row = rows.get(0);
    return new Usage(row.getLong("total", 0L), row.getLong("latest", 0L));
  }

  /**
   * 某玩家在某个条目上的累计销量
   *
   * @param sinceMillis 只统计该时间点之后的流水；{@code <= 0} 表示不限时间（终身累计）
   */
  public long usageSum(UUID player, UUID entry, long sinceMillis) throws SellStoreException {
    return usage(player, entry, sinceMillis).total();
  }

  public void addUsage(UUID player, UUID entry, int amount, long at) throws SellStoreException {
    if (db == null || amount <= 0) {
      return;
    }
    exec("INSERT INTO " + quote(usageTable)
        + " (`player_id`,`entry_id`,`amount`,`sold_at`) VALUES ("
        + quoteValue(player.toString()) + "," + quoteValue(entry.toString()) + "," + amount
        + "," + at + ")");
  }

  /** 清理旧流水（只影响限额统计，不影响任何物品） */
  public int pruneUsage(long before) throws SellStoreException {
    if (db == null) {
      return 0;
    }
    SdbDataEntry entry = exec("DELETE FROM " + quote(usageTable) + " WHERE `sold_at`<" + before);
    return entry == null ? 0 : (int) entry.InfluencedLines;
  }

  // ──────────────────────────────────────────────
  // 编码
  // ──────────────────────────────────────────────

  /**
   * 编码物品列表（Base64）
   * <pre>
   *   byte 版本 | int 物品数 | 每件：int 长度 + ItemStack.serializeAsBytes()
   * </pre>
   */
  static String encodeItems(@Nullable List<ItemStack> items) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(FORMAT_VERSION);
      writeItems(out, items);
    } catch (IOException e) {
      // ByteArrayOutputStream 不会真的抛 IO 异常
      jlogger.warn("JSell 编码物品列表失败：%s", e.getLocalizedMessage());
      return "";
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  /** 解码物品列表；格式不兼容或损坏返回 null */
  @Nullable
  static List<ItemStack> decodeItems(@Nullable String payload) {
    if (payload == null || payload.isBlank()) {
      return new ArrayList<>();
    }
    try (DataInputStream in = new DataInputStream(
        new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
      if (in.readByte() != FORMAT_VERSION) {
        return null;
      }
      return readItems(in);
    } catch (Exception e) {
      return null;
    }
  }

  /** 事务 payload：备注 + 待售物品 + 待退还物品 */
  static String encodeTx(@Nullable String note, @Nullable List<ItemStack> sold,
      @Nullable List<ItemStack> refunds) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(FORMAT_VERSION);
      out.writeUTF(note == null ? "" : truncate(note));
      writeItems(out, sold);
      writeItems(out, refunds);
    } catch (IOException e) {
      jlogger.warn("JSell 编码事务失败：%s", e.getLocalizedMessage());
      return "";
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  /** 事务 payload 的解码结果 */
  record Decoded(String note, List<ItemStack> sold, List<ItemStack> refunds) {
  }

  static Decoded decodeTx(@Nullable String payload) {
    if (payload == null || payload.isBlank()) {
      return new Decoded("", List.of(), List.of());
    }
    try (DataInputStream in = new DataInputStream(
        new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
      if (in.readByte() != FORMAT_VERSION) {
        return new Decoded("", List.of(), List.of());
      }
      String note = in.readUTF();
      List<ItemStack> sold = readItems(in);
      List<ItemStack> refunds = readItems(in);
      return new Decoded(note, sold, refunds);
    } catch (Exception e) {
      return new Decoded("", List.of(), List.of());
    }
  }

  private static void writeItems(DataOutputStream out, @Nullable List<ItemStack> items)
      throws IOException {
    int count = 0;
    if (items != null) {
      for (ItemStack item : items) {
        if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
          count++;
        }
      }
    }
    out.writeInt(count);
    if (items == null) {
      return;
    }
    for (ItemStack item : items) {
      if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
        continue;
      }
      byte[] data = item.serializeAsBytes();
      out.writeInt(data.length);
      out.write(data);
    }
  }

  private static List<ItemStack> readItems(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > 8192) {
      throw new IOException("物品数量异常：" + count);
    }
    List<ItemStack> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int len = in.readInt();
      if (len < 0 || len > 1 << 22) {
        throw new IOException("物品数据长度异常：" + len);
      }
      byte[] data = new byte[len];
      in.readFully(data);
      try {
        items.add(ItemStack.deserializeBytes(data));
      } catch (Exception e) {
        // 单件损坏不该让整份记录作废
        jlogger.warn("JSell 记录里的某件物品无法解析，已跳过：%s", e.getLocalizedMessage());
      }
    }
    return items;
  }

  /** writeUTF 的上限是 65535 字节，超长备注直接截断——它只是给人看的摘要 */
  private static String truncate(String raw) {
    return raw.length() <= 512 ? raw : raw.substring(0, 512) + "…";
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  private List<SConfig> query(String sql) throws SellStoreException {
    if (db == null) {
      return List.of();
    }
    try {
      SdbDataEntry entry = db.act(sql);
      return entry.ResultLines == null ? List.of() : entry.ResultLines;
    } catch (Exception e) {
      throw new SellStoreException("查询失败", e);
    }
  }

  @Nullable
  private SdbDataEntry exec(String sql) throws SellStoreException {
    if (db == null) {
      return null;
    }
    try {
      return db.act(sql);
    } catch (Exception e) {
      throw new SellStoreException("数据库操作失败", e);
    }
  }

  @Nullable
  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (Exception e) {
      return null;
    }
  }

  /** 标识符清洗：只留字母、数字、下划线（标识符无法参数化） */
  private static String sanitizeTable(@Nullable String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
    if (cleaned.isEmpty()) {
      jlogger.warn("JSell 配置的表名 [%s] 不含可用字符，已回落到 %s", raw, fallback);
      return fallback;
    }
    if (!cleaned.equals(raw)) {
      jlogger.warn("JSell 配置的表名 [%s] 含非法字符，实际使用 [%s]", raw, cleaned);
    }
    return cleaned;
  }

  private static String quote(String identifier) {
    return "`" + identifier.replace("`", "``") + "`";
  }

  /** 值 → SQL 字面量；不在白名单内立刻失败（见类注释的「SQL 安全边界」） */
  private static String quoteValue(@Nullable String value) {
    if (value == null) {
      return "NULL";
    }
    if (!SAFE_VALUE.matcher(value).matches()) {
      jlogger.severe("JSell 拒绝把非白名单字符拼进 SQL（长度 %s），疑似有代码把自由文本写进了存档层",
          value.length());
      throw new UnsafeSqlValueException(value.length());
    }
    return "'" + value + "'";
  }

  /** 有值想拼进 SQL 但不在白名单内（属于代码错误，不是用户输入问题） */
  public static final class UnsafeSqlValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    UnsafeSqlValueException(int length) {
      super("拒绝把非白名单字符拼进 SQL（长度 " + length + "）。JSell 只允许 UUID / Base64 / 数字；"
          + "自由文本请放进 payload（Base64）里，或等 StreackLib 支持参数化写入后再改。");
    }
  }

  /** 存档层异常（由调用方统一反馈） */
  public static final class SellStoreException extends Exception {
    private static final long serialVersionUID = 1L;

    public SellStoreException(String message, Throwable cause) {
      super(message + (cause == null || cause.getMessage() == null ? "" : "：" + cause.getMessage()),
          cause);
    }
  }
}
