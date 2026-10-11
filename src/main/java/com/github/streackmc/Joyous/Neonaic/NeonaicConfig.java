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

  /**
   * 是否在模块启用时自动建立连接。
   * <p>
   * 缺省 {@code false}：连接的开始/结束由公开 API 与 {@code /neonaic} 命令掌控，
   * 不由插件生命周期负责（避免「插件一启用就必须连上」的耦合）。
   */
  public boolean autoConnect() {
    return conf.getBoolean("connect.auto-connect", false);
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

  // ---- 身份绑定：自助绑定代码流程 ----

  /** 玩家发起绑定（生成代码）的命令名 */
  public String bindCommandName() {
    String s = conf.getString("group-chat-sync.bind-identity.bind.name", "neonaicbindidentity");
    return s == null || s.isBlank() ? "neonaicbindidentity" : s.trim();
  }

  /** 玩家发起绑定的命令别名 */
  public List<String> bindCommandAlias() {
    return conf.getListOfString("group-chat-sync.bind-identity.bind.alias", List.of());
  }

  /** 玩家解除绑定的命令名 */
  public String unbindCommandName() {
    String s = conf.getString("group-chat-sync.bind-identity.unbind.name", "neonaicunbindidentity");
    return s == null || s.isBlank() ? "neonaicunbindidentity" : s.trim();
  }

  /** 玩家解除绑定的命令别名 */
  public List<String> unbindCommandAlias() {
    return conf.getListOfString("group-chat-sync.bind-identity.unbind.alias", List.of());
  }

  /** 绑定代码有效期（秒），缺省 300（5 分钟）；<=0 回退缺省 */
  public int bindCodeTtlSeconds() {
    int v = conf.getInt("group-chat-sync.bind-identity.code-ttl", 300);
    return v > 0 ? v : 300;
  }

  /** 绑定代码长度（位），缺省 6；限制在 [4,12] */
  public int bindCodeLength() {
    int v = conf.getInt("group-chat-sync.bind-identity.code-length", 6);
    if (v < 4) return 4;
    if (v > 12) return 12;
    return v;
  }

  /** 同一玩家两次请求绑定代码的冷却（秒），缺省 0（不限） */
  public int bindRequestCooldownSeconds() {
    return Math.max(0, conf.getInt("group-chat-sync.bind-identity.request-cooldown", 0));
  }
}
