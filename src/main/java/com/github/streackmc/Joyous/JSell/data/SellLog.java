package com.github.streackmc.Joyous.JSell.data;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.JSell.JSellMain;
import com.github.streackmc.Joyous.utils.AsyncDailyLog;

/**
 * 出售流水（人可读的文本日志）
 * <p>
 * 与 {@code jsell_usage}（给限购用的数据库流水）分工不同：
 * <ul>
 *   <li>数据库那张表只为统计「这个玩家这个条目卖了多少」，出错时可以整表重建；</li>
 *   <li>这里的文件是<b>账</b>：谁、什么时候、卖了哪个分组的什么东西、落袋多少、属于哪个事务号。
 *       管理员对账、玩家申诉都靠它，所以它必须独立于数据库存在。</li>
 * </ul>
 * 落盘位置默认 {@code plugins/Joyous/logs/JSell/yyyy-MM/dd.txt}，每行一条，格式固定便于 grep：
 * <pre>
 *   12:34:56 | SOLD   | Steve | 8f3e…01 | ores | DIAMOND_SWORD | x32 | 64.0 金币 | tx=…
 *   12:35:10 | REFUND | Steve | 8f3e…01 | 3 件                                        | tx=…
 *   12:36:00 | FAILED | Steve | 8f3e…01 | 付款命令执行失败                              | tx=…
 * </pre>
 * 写入是异步的（见 {@link AsyncDailyLog}），主线程只做一次入队，不会因为磁盘慢而卡服。
 *
 * @author Neonai
 * @since 0.4.0
 */
public final class SellLog {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private static volatile AsyncDailyLog sink;
  /** 关掉日志时也不该每次调用都读配置，缓存一次 */
  private static volatile boolean enabled;

  private SellLog() {
  }

  /**
   * 初始化（模块启用时调用一次）：按配置决定是否开启、落在哪
   *
   * @param dataPath 插件数据目录，日志落在它下面的 {@code log.dir}
   */
  public static synchronized void init(File dataPath) {
    init(dataPath, JSellMain.CONF.logDir(), JSellMain.CONF.logEnabled());
  }

  /**
   * 显式初始化（配置之外的入口，自检与将来的独立测试用）
   *
   * @param dir 相对 {@code dataPath} 的目录
   */
  public static synchronized void init(File dataPath, String dir, boolean on) {
    close();
    enabled = on;
    if (!on) {
      return;
    }
    sink = new AsyncDailyLog(new File(dataPath, dir), "jsell-log");
  }

  /** 关闭写线程（模块禁用时调用；幂等） */
  public static synchronized void close() {
    AsyncDailyLog current = sink;
    sink = null;
    if (current != null) {
      current.close();
    }
  }

  /** 一行「卖出」：一条 {@link SellService.Sale} 一行，粒度与实物移动一致 */
  public static void sold(Player player, SellService.Sale sale, String txId) {
    sold(player.getName(), player.getUniqueId().toString(), sale, txId);
  }

  /**
   * 同上，但用玩家名与 UUID 字符串（不依赖 {@code Player}，便于自检与离线补录）
   */
  public static void sold(String playerName, String playerUuid, SellService.Sale sale, String txId) {
    if (!writable() || sale == null) {
      return;
    }
    String item = sale.item().getType().name();
    write(line("SOLD", playerName, playerUuid, sale.entry().group(), item,
        "x" + sale.item().getAmount(),
        SellService.amountText(sale.value(),
            JSellMain.CONF.decimals(sale.entry().group()))
            + " " + JSellMain.CONF.unitName(sale.entry().group()),
        "tx=" + txId));
  }

  /** 一行「退回」：不可售 / 超出限购而还给玩家的部分（按堆叠组计） */
  public static void refunded(Player player, int groups, String txId) {
    if (!writable() || groups <= 0) {
      return;
    }
    write(line("REFUND", player.getName(), player.getUniqueId().toString(), "-",
        groups + " 组", null, null, "tx=" + txId));
  }

  /** 一行「事务未走完」：付款或记账失败，留给管理员核查 */
  public static void failed(Player player, String txId, @Nullable String reason) {
    if (!writable()) {
      return;
    }
    write(line("FAILED", player.getName(), player.getUniqueId().toString(), "-",
        reason == null ? "-" : reason, null, null, "tx=" + txId));
  }

  private static boolean writable() {
    return enabled && sink != null;
  }

  private static void write(String line) {
    AsyncDailyLog current = sink;
    if (current != null) {
      current.write(line);
    }
  }

  /** 固定列序：时间 | 动作 | 玩家 | UUID | 分组 | 内容 | 数量 | 金额 | 事务号 */
  private static String line(String action, String playerName, String playerUuid, String group,
      String what, @Nullable String count, @Nullable String amount, String tx) {
    StringBuilder sb = new StringBuilder(96);
    sb.append(LocalTime.now().format(TIME)).append(" | ")
        .append(action).append(" | ")
        .append(playerName).append(" | ")
        .append(playerUuid).append(" | ")
        .append(group).append(" | ")
        .append(what);
    if (count != null) {
      sb.append(" | ").append(count);
    }
    if (amount != null) {
      sb.append(" | ").append(amount);
    }
    sb.append(" | ").append(tx);
    return sb.toString();
  }
}
