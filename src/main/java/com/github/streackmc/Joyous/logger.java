package com.github.streackmc.Joyous;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * 全局静态日志工具，自动根据运行环境选择日志后端。
 * * 引用自Joyous
 * 
 * <p>
 * 使用方式：
 * 
 * <pre>{@code
 * // 在主类 onEnable 里初始化一次
 * logger.init(this);
 *
 * // 任意位置
 * logger.info("玩家 %s 加入了游戏", player.getName());
 * }</pre>
 * <p>
 * 优先级（运行时一次性探测）：
 * <ol>
 * <li>Bukkit 插件 Logger（通过 {@link #init(JavaPlugin)} 注入）</li>
 * <li>SLF4J（如果存在）</li>
 * <li>java.util.logging（保底）</li>
 * </ol>
 * <p>
 * 未来支持 Fabric 时，只需新增一个 {@link Backend} 实现即可，无需改动业务代码。
 *
 * @author KimiAI 编写
 * @author GitHub Copilot 编写
 * @author kdxiaoyi 审计
 * @since 0.0.1
 */
public class logger {

    /* ===================== 对外 API ===================== */

  /**
   * 输出调试信息
   * 这只在启用相应配置项后生效
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void debug(@NotNull Object... args) {
    if (!Joyous.conf.getBoolean("debug", false)) {
      return;
    }
    Payload p = extract(args);
    if (p.t != null) {
      backend().debug(p.msg + "\n" + throwableToString(p.t));
    } else {
      backend().debug(p.msg);
    }
  }

  /**
   * 输出一般信息
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void info(@NotNull Object... args) {
    Payload p = extract(args);
    if (p.t != null) {
      backend().info(p.msg + "\n" + throwableToString(p.t));
    } else {
      backend().info(p.msg);
    }
  }

  /**
   * 输出警告信息
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void warning(@NotNull Object... args) {
    Payload p = extract(args);
    if (p.t != null) {
      backend().warn(p.msg + "\n" + throwableToString(p.t));
    } else {
      backend().warn(p.msg);
    }
  }

  /**
   * 输出警告信息，别名
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void warn(@NotNull Object... args) {
    warning(args);
  }

  /**
   * 输出错误信息（可携带 Throwable）
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void severe(@NotNull Object... args) {
    Payload p = extract(args);
    backend().error(p.msg, p.t);
  }

  /**
   * 输出错误信息（可携带 Throwable）
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void error(@NotNull Object... args) {
    severe(args);
  }

  /**
   * 输出错误信息（可携带 Throwable）
   * 
   * @param args 任意数量、任意类型的参数。若第一个参数为 String，则视为格式化模板（其余参数用于 format）。
   *             若最后一个参数为 Throwable，则对 severe/error 系列方法会将其作为异常输出；其它级别会将堆栈附加到消息。
   */
  public static void err(@NotNull Object... args) {
    severe(args);
  }

  /* ===================== 内部实现 ===================== */

  /** 供外部探测的插件实例，null 表示未接入 Bukkit */
  public static JavaPlugin plugin = null;

  /** 日志后端接口，隔离具体实现 */
  public interface Backend {
    void debug(String msg);

    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);
  }

  /** 后端实例，惰性初始化且只初始化一次 */
  private static volatile Backend BACKEND;

  private static Backend backend() {
    if (BACKEND == null) {
      synchronized (logger.class) {
        if (BACKEND == null) {
          BACKEND = detectBackend();
        }
      }
    }
    return BACKEND;
  }

  /** 按优先级探测并实例化 Backend */
  private static Backend detectBackend() {
    // 1. Bukkit
    if (plugin != null)
      return new BukkitBackend();
    // 2. SLF4J
    try {
      Class.forName("org.slf4j.LoggerFactory");
      return new Slf4jBackend();
    } catch (ClassNotFoundException ignore) {
      /* 不存在 */
    }
    // 3. JUL 保底
    return new JulBackend();
  }

  /* -------------------- 后端实现 -------------------- */

  /** Bukkit 插件日志 */
  private static final class BukkitBackend implements Backend {
    private Logger log() {
      return plugin.getLogger();
    }

    public void debug(String msg) {
      log().info(msg);
    }

    public void info(String msg) {
      log().info(msg);
    }

    public void warn(String msg) {
      log().warning(msg);
    }

    public void error(String msg, Throwable t) {
      log().log(Level.SEVERE, msg, t);
    }
  }

  /** SLF4J 日志（无插件时） */
  private static final class Slf4jBackend implements Backend {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(logger.class);

    public void debug(String msg) {
      LOG.info(msg);
    }

    public void info(String msg) {
      LOG.info(msg);
    }

    public void warn(String msg) {
      LOG.warn(msg);
    }

    public void error(String msg, Throwable t) {
      LOG.error(msg, t);
    }
  }

  /** java.util.logging 保底 */
  private static final class JulBackend implements Backend {
    private static final Logger LOG = Logger.getLogger(logger.class.getName());

    public void debug(String msg) {
      LOG.info(msg);
    }

    public void info(String msg) {
      LOG.info(msg);
    }

    public void warn(String msg) {
      LOG.warning(msg);
    }

    public void error(String msg, Throwable t) {
      LOG.log(Level.SEVERE, msg, t);
    }
  }

  /* -------------------- 工具方法 -------------------- */

  /** 简单格式化：用 String.format，兼容 %s 等占位符 */
  private static String format(String msg, Object... arg) {
    return arg.length == 0 ? msg : String.format(msg, arg);
  }

  /** 解析传入参数，返回最终消息与可选 Throwable */
  private static final class Payload {
    final String msg;
    final Throwable t;

    Payload(String msg, Throwable t) {
      this.msg = msg;
      this.t = t;
    }
  }

  private static Payload extract(Object... args) {
    if (args == null || args.length == 0)
      return new Payload("", null);
    // 如果只有一个参数
    if (args.length == 1) {
      Object o = args[0];
      if (o instanceof Throwable) {
        return new Payload(((Throwable) o).toString(), (Throwable) o);
      }
      return new Payload(String.valueOf(o), null);
    }
    // 检查最后一个是否为 Throwable
    Throwable lastAsThrowable = null;
    int len = args.length;
    if (args[len - 1] instanceof Throwable) {
      lastAsThrowable = (Throwable) args[len - 1];
      len -= 1; // 剩余用于消息构造
    }
    Object first = args[0];
    if (first instanceof String) {
      Object[] fmtArgs = len <= 1 ? new Object[0] : Arrays.copyOfRange(args, 1, len);
      String msg = fmtArgs.length == 0 ? (String) first : format((String) first, fmtArgs);
      return new Payload(msg, lastAsThrowable);
    } else {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
        if (i > 0)
          sb.append(' ');
        sb.append(String.valueOf(args[i]));
      }
      return new Payload(sb.toString(), lastAsThrowable);
    }
  }

  private static String throwableToString(Throwable t) {
    if (t == null)
      return "";
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    pw.flush();
    return sw.toString();
  }

  private logger() {
  } // 禁止实例化

}
