package com.github.streackmc.Joyous.Neonaic.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Neonaic.NeonaicConfig;
import com.github.streackmc.Joyous.Neonaic.bridge.NeonaicBridge;
import com.github.streackmc.Joyous.jlogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 聊天捕获与 AI 触发（{@code ai.*}）。
 * <p>
 * 监听 {@link AsyncChatEvent}，负责：
 * 记录上下文、from-mc 外发、{@code @} 触发与随机触发。
 * 触发判定在本侧完成，仅把「生成回复」委托给 Neonaic（{@code ai.request}）。
 *
 * @since 0.3.0
 */
public class ChatListener implements Listener {

  /** 计入上下文所需权限 */
  public static final String PERM_CONTEXT = "joyous.neonaic.ai.inchat.context";
  /** 允许随机回复触发所需权限 */
  public static final String PERM_TRIGGER = "joyous.neonaic.ai.inchat.trigger";
  /** 允许 @ / 命令触发所需权限 */
  public static final String PERM_ASK = "joyous.neonaic.ai.ask";

  private final NeonaicConfig config;
  private final NeonaicBridge bridge;
  private final AiReplyController aiController;
  private final GroupChatSync groupSync;
  private final Supplier<String> name;
  private final Supplier<String> subname;

  public ChatListener(NeonaicConfig config, NeonaicBridge bridge, AiReplyController aiController,
      GroupChatSync groupSync, Supplier<String> name, Supplier<String> subname) {
    this.config = config;
    this.bridge = bridge;
    this.aiController = aiController;
    this.groupSync = groupSync;
    this.name = name;
    this.subname = subname;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    String plain = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    if (plain.isEmpty()) return;

    // 记录上下文（是否计入取决于权限）
    boolean contextEligible = player.hasPermission(PERM_CONTEXT);
    aiController.recordChat(player.getName(), player.getUniqueId(), plain, contextEligible);

    // from-mc 外发
    groupSync.publishFromMc(player, plain);

    // @ 触发
    if (config.aiAtName() && player.hasPermission(PERM_ASK)
        && matchesAt(plain, name.get(), subname.get())) {
      aiController.markTrigger(player.getUniqueId());
      requestReply(player, plain, "at-name", true);
      return;
    }

    // 随机触发
    if (config.aiRandomEnabled() && player.hasPermission(PERM_TRIGGER)
        && aiController.shouldRandomReply(player.getUniqueId())) {
      requestReply(player, plain, "random", true);
    }
  }

  /**
   * 请求一次 AI 回复并展示。
   *
   * @param publicDisplay true 公屏展示，false 私聊
   */
  public void requestReply(Player player, String prompt, String trigger, boolean publicDisplay) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trigger", trigger);
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("name", player.getName());
    actor.put("uuid", player.getUniqueId().toString());
    actor.put("subname", "");
    payload.put("actor", actor);
    payload.put("prompt", player.getName() + ": " + prompt);
    payload.put("context", aiController.buildContext(player.getUniqueId()));
    payload.put("visibility", publicDisplay ? "public" : "private");

    bridge.aiRequest(payload).whenComplete((resp, err) -> {
      if (err != null) {
        jlogger.debug("[Neonaic] ai.request 失败：%s", err.getLocalizedMessage());
        return;
      }
      String output = resp != null ? str(resp.get("output"), "") : "";
      if (output.isBlank()) return;

      String botName = name.get();
      String botSub = subname.get();
      // 记账（Bot 自述不计入触发者/他人归属，uuid=null）
      aiController.recordChat(botName, null, output, true);

      Component component = publicDisplay
          ? ChatMessageRenderer.render(null, config.publicMsg(), output, botName, botSub, player.getName(),
              config.publicCover(), config.publicClickCmd(), config.publicClickArg())
          : ChatMessageRenderer.render(player, config.privateMsg(), output, botName, botSub, player.getName(),
              config.privateCover(), config.privateClickCmd(), config.privateClickArg());

      schedule(() -> {
        if (publicDisplay) {
          Bukkit.broadcast(component);
        } else {
          player.sendMessage(component);
        }
      });
    });
  }

  // ---- 内部 ----

  private static boolean matchesAt(String message, String name, String subname) {
    String lower = message.toLowerCase();
    if (name != null && !name.isBlank() && lower.contains("@" + name.toLowerCase())) return true;
    if (subname != null && !subname.isBlank() && lower.contains("@" + subname.toLowerCase())) return true;
    return false;
  }

  private static void schedule(Runnable task) {
    Bukkit.getScheduler().runTask(Joyous.plugin, task);
  }

  private static String str(Object o, String def) {
    if (o == null) return def;
    String s = String.valueOf(o);
    return s.isBlank() ? def : s;
  }
}
