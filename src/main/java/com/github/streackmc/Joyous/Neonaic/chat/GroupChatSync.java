package com.github.streackmc.Joyous.Neonaic.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Neonaic.NeonaicConfig;
import com.github.streackmc.Joyous.Neonaic.bridge.NeonaicBridge;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;

/**
 * 群服互联（{@code group-chat-sync}）。
 * <ul>
 *   <li>from-mc：把 MC 聊天经 {@code chat.publish} 外发到配置的 profile/频道；</li>
 *   <li>to-mc：作为 {@code chat.broadcast} 的方法处理器，把外部消息渲染后内播；</li>
 *   <li>bind-identity：经 {@code identity.bind} 绑定 MC↔聊天身份。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class GroupChatSync {

  /** 玩家侧「禁止群服互联」权限 */
  public static final String PERM_SYNC_DISABLED = "joyous.neonaic.chatsync.disabled";

  private final NeonaicConfig config;
  private final NeonaicBridge bridge;
  private final Supplier<String> name;
  private final Supplier<String> subname;

  public GroupChatSync(NeonaicConfig config, NeonaicBridge bridge, Supplier<String> name, Supplier<String> subname) {
    this.config = config;
    this.bridge = bridge;
    this.name = name;
    this.subname = subname;
  }

  // ---- from-mc ----

  /** 把一条 MC 聊天外发到所有配置的 profile/频道（fire-and-forget） */
  public void publishFromMc(Player player, String plainMessage) {
    if (!config.syncEnabled() || !config.syncFromMcEnabled()) return;
    if (player.hasPermission(PERM_SYNC_DISABLED)) return;

    Map<String, List<String>> profiles = config.syncProfiles();
    if (profiles.isEmpty()) return;

    String message = applyFormat(config.syncFromMcFormat(), player, plainMessage);
    long ts = System.currentTimeMillis();

    for (Map.Entry<String, List<String>> entry : profiles.entrySet()) {
      for (String channel : entry.getValue()) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", entry.getKey());
        payload.put("channel", channel);
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("name", player.getName());
        sender.put("uuid", player.getUniqueId().toString());
        sender.put("subname", "");
        payload.put("sender", sender);
        payload.put("message", message);
        payload.put("ts", ts);

        bridge.chatPublish(payload).whenComplete((r, e) -> {
          if (e != null) {
            jlogger.debug("[Neonaic] chat.publish 失败（profile=%s, channel=%s）：%s",
                entry.getKey(), channel, e.getLocalizedMessage());
          }
        });
      }
    }
  }

  // ---- to-mc（chat.broadcast 方法处理器） ----

  /** 处理 Neonaic 的 {@code chat.broadcast} 请求 */
  public Map<String, Object> onBroadcast(Map<String, Object> payload) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!config.syncEnabled()) {
      result.put("rendered", false);
      return result;
    }

    String message = str(payload.get("message"), "");
    String visibility = str(payload.get("visibility"), "public");
    Map<String, Object> user = asMap(payload.get("user"));
    String userName = user != null ? str(user.get("name"), "") : "";
    String userSub = user != null ? str(user.get("subname"), "") : "";

    boolean isPrivate = "private".equalsIgnoreCase(visibility);

    if (isPrivate) {
      Map<String, Object> target = asMap(payload.get("target"));
      if (target == null) {
        result.put("rendered", false);
        result.put("error", "visibility=private 但缺少 target");
        return result;
      }
      Player receiver = resolvePlayer(target);
      if (receiver == null) {
        result.put("rendered", false);
        result.put("recipients", 0);
        return result;
      }
      Component component = ChatMessageRenderer.render(
          receiver, config.privateMsg(), message, name.get(), subname.get(), userName,
          config.privateCover(), config.privateClickCmd(), config.privateClickArg());
      schedule(() -> receiver.sendMessage(component));
      result.put("rendered", true);
      result.put("visibility", "private");
      result.put("recipients", 1);
      return result;
    }

    // public
    if (!config.syncToMcEnabled()) {
      result.put("rendered", false);
      return result;
    }
    String format = config.syncToMcFormat().isBlank() ? config.publicMsg() : config.syncToMcFormat();
    String userLabel = userSub.isBlank() ? userName : userName + userSub;
    Component component = ChatMessageRenderer.render(
        null, format, message, name.get(), subname.get(), userLabel,
        config.publicCover(), config.publicClickCmd(), config.publicClickArg());
    schedule(() -> Bukkit.broadcast(component));

    result.put("rendered", true);
    result.put("visibility", "public");
    result.put("recipients", Bukkit.getOnlinePlayers().size());
    return result;
  }

  // ---- bind-identity ----

  public CompletableFuture<Map<String, Object>> bind(String profile, Player player, String chatId, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("profile", profile);
    Map<String, Object> mc = new LinkedHashMap<>();
    mc.put("name", player.getName());
    mc.put("uuid", player.getUniqueId().toString());
    payload.put("mc", mc);
    payload.put("chatId", chatId);
    if (code != null && !code.isBlank()) payload.put("code", code);
    return bridge.identityBind(payload);
  }

  // ---- 内部 ----

  /** 应用 from-mc 格式：token 替换 → PAPI → 去色，得到纯文本 */
  private String applyFormat(String format, Player player, String plainMessage) {
    if (format == null || format.isBlank()) return plainMessage;
    String substituted = ChatMessageRenderer.substitute(
        format, plainMessage, name.get(), subname.get(), player.getName());
    String papi = Joyous.i18n.getPHparsed(player, substituted);
    return MCColor.strip(MCColor.parse(papi));
  }

  private static void schedule(Runnable task) {
    Bukkit.getScheduler().runTask(Joyous.plugin, task);
  }

  private static Player resolvePlayer(Map<String, Object> target) {
    Object uuid = target.get("uuid");
    if (uuid != null) {
      try {
        Player p = Bukkit.getPlayer(UUID.fromString(String.valueOf(uuid)));
        if (p != null) return p;
      } catch (IllegalArgumentException ignored) {
        // 非 UUID，退化为按名字查找
      }
    }
    Object nameObj = target.get("name");
    return nameObj != null ? Bukkit.getPlayerExact(String.valueOf(nameObj)) : null;
  }

  private static String str(Object o, String def) {
    if (o == null) return def;
    String s = String.valueOf(o);
    return s.isBlank() ? def : s;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
  }
}
