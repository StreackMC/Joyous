package com.github.streackmc.Joyous.Neonaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.streackmc.StreackLib.types.SConfig;

/**
 * Neonaic 模块配置读取（{@code models/Neonaic.yml}）。
 * <p>
 * 本类只做「读值 + 归一化」，不产生副作用；配置支持热重载（每次调用重新读值）。
 *
 * @since 0.3.0
 */
public class NeonaicConfig {

  /** 模型配置文件相对数据目录的路径 */
  public static final String CONF_FILE = "models/Neonaic.yml";

  private final SConfig conf;

  public NeonaicConfig(SConfig conf) {
    this.conf = conf;
  }

  // ---- Bot 展示名（配置为空则回退到对端握手帧） ----

  public String name() {
    return conf.getString("name", "");
  }

  public String subname() {
    return conf.getString("subname", "");
  }

  // ---- 聊天格式 ----

  public String publicMsg() {
    return conf.getString("public-msg", "[name]→ [msg]");
  }

  public String publicCover() {
    return conf.getString("public-cover", "");
  }

  public String publicClickCmd() {
    return conf.getString("public-click.cmd", "none");
  }

  public String publicClickArg() {
    return conf.getString("public-click.arg", "");
  }

  public String privateMsg() {
    return conf.getString("private-msg", "Private: [name]→ [msg]");
  }

  public String privateCover() {
    return conf.getString("private-cover", "");
  }

  public String privateClickCmd() {
    return conf.getString("private-click.cmd", "none");
  }

  public String privateClickArg() {
    return conf.getString("private-click.arg", "");
  }

  // ---- 通信（connect） ----

  /** {@code http} / {@code uds} / {@code auto} */
  public String method() {
    return conf.getString("connect.method", "uds");
  }

  public String udsPath() {
    return conf.getString("connect.uds.path", "/tmp/neonai-joyous.sock");
  }

  public String httpInvokeUrl() {
    return conf.getString("connect.http.invoke-url", "");
  }

  public String httpCommandUrl() {
    return conf.getString("connect.http.command-url", "");
  }

  public String httpToken() {
    return conf.getString("connect.http.token", "");
  }

  /** 本插件 HTTP 服务端上接收 Neonaic 调用的路径（承载 chat.broadcast） */
  public String httpServerPath() {
    return conf.getString("connect.http.server-path", "/neonai/invoke");
  }

  public long handshakeTimeout() {
    return conf.getLong("connect.timeout.handshake", 5000L);
  }

  public long requestTimeout() {
    return conf.getLong("connect.timeout.request", 10000L);
  }

  public long heartbeat() {
    return conf.getLong("connect.timeout.heartbeat", 30000L);
  }

  public long commandTimeout() {
    return conf.getLong("connect.timeout.command", 30000L);
  }

  // ---- AI ----

  public boolean aiRandomEnabled() {
    return conf.getBoolean("ai.reply-chat-randomly.enabled", true);
  }

  public int aiRandomIntervalPlayer() {
    return Math.max(0, conf.getInt("ai.reply-chat-randomly.interval-player", 10));
  }

  public int aiRandomIntervalGlobal() {
    return Math.max(0, conf.getInt("ai.reply-chat-randomly.interval-global", 10));
  }

  public double aiRandomPossibility() {
    double p = conf.getDouble("ai.reply-chat-randomly.possiblility", 0.2);
    if (p < 0) return 0;
    if (p > 1) return 1;
    return p;
  }

  public int aiContextWhenRecent() {
    return Math.max(0, conf.getInt("ai.reply-chat-randomly.context.when-is-recent", 60));
  }

  public int aiContextMaxTrigger() {
    return Math.max(0, conf.getInt("ai.reply-chat-randomly.context.max-of-trigger", 0));
  }

  public int aiContextMaxOthers() {
    return Math.max(0, conf.getInt("ai.reply-chat-randomly.context.max-of-others", 0));
  }

  public boolean aiAtName() {
    return conf.getBoolean("ai.reply-by-at.trigger.at-name", true);
  }

  public boolean aiByCmd() {
    return conf.getBoolean("ai.reply-by-at.trigger.by-cmd", true);
  }

  // ---- 群服互联 ----

  public boolean syncEnabled() {
    return conf.getBoolean("group-chat-sync.enabled", true);
  }

  /** profile 名 → 该 profile 下要同步的频道/用户标识列表 */
  @SuppressWarnings("unchecked")
  public Map<String, List<String>> syncProfiles() {
    Map<String, Object> section = conf.getSection("group-chat-sync.profile");
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (section == null) return result;
    for (Map.Entry<String, Object> e : section.entrySet()) {
      List<String> ids = new ArrayList<>();
      if (e.getValue() instanceof List<?> list) {
        for (Object o : list) {
          if (o != null) ids.add(String.valueOf(o));
        }
      } else if (e.getValue() != null) {
        ids.add(String.valueOf(e.getValue()));
      }
      result.put(e.getKey(), ids);
    }
    return result;
  }

  public boolean syncFromMcEnabled() {
    return conf.getBoolean("group-chat-sync.from-mc.enabled", true);
  }

  public String syncFromMcFormat() {
    return conf.getString("group-chat-sync.from-mc.format", "");
  }

  public boolean syncToMcEnabled() {
    return conf.getBoolean("group-chat-sync.to-mc.enabled", true);
  }

  public String syncToMcFormat() {
    return conf.getString("group-chat-sync.to-mc.format", "");
  }

  public boolean bindIdentityEnabled() {
    return conf.getBoolean("group-chat-sync.bind-identity.enabled", true);
  }
}
