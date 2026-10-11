package com.github.streackmc.Joyous.Neonaic;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.Neonaic.chat.IdentityBindingService;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeException;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeProtocol;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Neonaic 命令族：
 * <ul>
 *   <li>{@code /neonaic ...} —— 桥接管理、**管理员直接设置**身份绑定、AI 提问；</li>
 *   <li>{@code /neonaicbindidentity} 与 {@code /neonaicunbindidentity}（名称/别名见配置）——
 *       玩家**自助**绑定代码流程与解绑；</li>
 *   <li>by-cmd 触发：按配置的 Bot 名注册 {@code /ask}。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class NeonaicCommand {

  /** 自助绑定/解绑权限 */
  public static final String PERM_BIND_SELF = "joyous.neonaic.bind.self";

  private final NeonaicSubsystem subsystem;

  public NeonaicCommand(NeonaicSubsystem subsystem) {
    this.subsystem = subsystem;
  }

  public void register() {
    Joyous.addPermissions(
        PermDef.op("joyous.commands.neonaic.status", "查看与管理 Neonaic 桥接状态"),
        PermDef.op("joyous.commands.neonaic.bind", "管理员：直接设置身份绑定"),
        PermDef.all(PERM_BIND_SELF, "自助请求绑定代码 / 解除绑定"),
        PermDef.all("joyous.neonaic.ai.ask", "通过 @ 或命令请求 AI 回复"));

    Joyous.registerCommand(
        Commands.literal("neonaic")
            .then(Commands.literal("bridge")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.neonaic.status"))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("start").executes(this::start))
                .then(Commands.literal("stop").executes(this::stop))
                .then(Commands.literal("restart").executes(this::restart)))
            // 管理员直接设置（旧流程保留，供运维/迁移使用）
            .then(Commands.literal("bind")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.neonaic.bind"))
                .then(Commands.argument("profile", StringArgumentType.word())
                    .then(Commands.argument("chatId", StringArgumentType.word())
                        .executes(ctx -> adminBind(ctx, null))
                        .then(Commands.argument("code", StringArgumentType.word())
                            .executes(ctx -> adminBind(ctx, StringArgumentType.getString(ctx, "code")))))))
            .then(Commands.literal("ask")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.neonaic.ai.ask"))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(this::ask)))
            .build(),
        "Neonaic 桥接管理",
        List.of());

    // by-cmd 触发：把配置的 Bot 名 / 副名注册为命令（仅当配置显式给出时）
    if (subsystem.byCmdEnabled()) {
      Joyous.registerCommand(
          Commands.literal("ask")
              .requires(ctx -> ctx.getSender().hasPermission("joyous.neonaic.ai.ask"))
              .then(Commands.argument("text", StringArgumentType.greedyString())
                  .executes(this::ask))
              .build(),
          "向 " + subsystem.botSubname() + " 提问（私聊回复）",
          List.of(subsystem.botName(), subsystem.botSubname()));
    }

    registerSelfServiceCommands();
  }

  /** 自助绑定/解绑命令（命令名与别名取自配置） */
  private void registerSelfServiceCommands() {
    NeonaicConfig cfg = subsystem.config();
    if (cfg == null || !cfg.bindIdentityEnabled()) return;

    Joyous.registerCommand(
        Commands.literal(cfg.bindCommandName())
            .requires(ctx -> ctx.getSender().hasPermission(PERM_BIND_SELF))
            .executes(this::requestBindCode)
            .build(),
        "请求 Neonaic 身份绑定代码",
        cfg.bindCommandAlias());

    Joyous.registerCommand(
        Commands.literal(cfg.unbindCommandName())
            .requires(ctx -> ctx.getSender().hasPermission(PERM_BIND_SELF))
            .executes(this::unbindIdentity)
            .build(),
        "解除 Neonaic 身份绑定",
        cfg.unbindCommandAlias());
  }

  // ---- /neonaic 子命令 ----

  private int status(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    for (String line : subsystem.statusLines()) {
      NeonaicText.send(sender, NeonaicText.of(line));
    }
    return 1;
  }

  /** /neonaic bridge start —— 建立连接 */
  private int start(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (subsystem.isConnectionRunning()) {
      NeonaicText.send(sender, NeonaicText.of("&e连接已处于建立状态，无需重复建立。"));
      return 1;
    }
    boolean ok = subsystem.startConnection();
    NeonaicText.send(sender, NeonaicText.of(ok
        ? "&a已建立连接（模式 " + subsystem.connectionMode() + "）。UDS 模式下等待对端拨入握手。"
        : "&c建立连接失败，请查看控制台日志（检查套接字路径/端口/权限）。"));
    return ok ? 1 : 0;
  }

  /** /neonaic bridge stop —— 断开连接并释放监听 */
  private int stop(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!subsystem.isConnectionRunning()) {
      NeonaicText.send(sender, NeonaicText.of("&e连接本就未建立。"));
      return 1;
    }
    subsystem.stopConnection();
    NeonaicText.send(sender, NeonaicText.of("&a已断开连接并释放监听。"));
    return 1;
  }

  /** /neonaic bridge restart —— 重连 */
  private int restart(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    boolean ok = subsystem.restartConnection();
    NeonaicText.send(sender, NeonaicText.of(ok
        ? "&a已重连（模式 " + subsystem.connectionMode() + "）。"
        : "&c重连失败，请查看控制台日志。"));
    return ok ? 1 : 0;
  }

  /** /neonaic bind <profile> <chatId> [code] —— 管理员直接设置（旧流程） */
  private int adminBind(CommandContext<CommandSourceStack> ctx, String code) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      NeonaicText.send(sender, NeonaicText.tr("system.command.player_only"));
      return 0;
    }
    if (!subsystem.bindIdentityEnabled()) {
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.disabled"));
      return 0;
    }
    String profile = StringArgumentType.getString(ctx, "profile");
    String chatId = StringArgumentType.getString(ctx, "chatId");
    subsystem.adminBind(player, profile, chatId, code).whenComplete((resp, err) -> {
      if (err != null) {
        NeonaicText.send(sender, NeonaicText.tr("neonaic.admin.bind-failed", err.getLocalizedMessage()));
        return;
      }
      boolean bound = resp != null && Boolean.TRUE.equals(resp.get("bound"));
      NeonaicText.send(sender, bound
          ? NeonaicText.tr("neonaic.admin.bind-success", profile, chatId)
          : NeonaicText.tr("neonaic.admin.bind-incomplete", profile));
    });
    return 1;
  }

  // ---- 自助绑定 / 解绑 ----

  /** /neonaicbindidentity —— 生成绑定代码 */
  private int requestBindCode(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      NeonaicText.send(sender, NeonaicText.tr("system.command.player_only"));
      return 0;
    }
    IdentityBindingService service = subsystem.binding();
    if (service == null || !subsystem.bindIdentityEnabled()) {
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.disabled"));
      return 0;
    }
    if (!subsystem.isConnectionRunning()) {
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.bridge-not-ready"));
      return 0;
    }

    try {
      IdentityBindingService.PendingCode pending = service.requestCode(player.getUniqueId(), player.getName());
      long minutes = Math.max(1, (pending.remainingSeconds() + 59) / 60);
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.code-issued", minutes));
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.code-line", pending.code())
          .clickEvent(ClickEvent.copyToClipboard(pending.code()))
          .hoverEvent(HoverEvent.showText(NeonaicText.tr("neonaic.bind.code-copy-hover"))));
      return 1;
    } catch (BridgeException e) {
      if (BridgeProtocol.Errors.RATE_LIMITED.equals(e.code())) {
        NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.rate-limited", e.getMessage()));
      } else {
        NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.request-failed", e.getMessage()));
      }
      return 0;
    }
  }

  /** /neonaicunbindidentity —— 解绑 */
  private int unbindIdentity(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      NeonaicText.send(sender, NeonaicText.tr("system.command.player_only"));
      return 0;
    }
    IdentityBindingService service = subsystem.binding();
    if (service == null || !subsystem.bindIdentityEnabled()) {
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.disabled"));
      return 0;
    }
    if (!subsystem.isConnectionRunning()) {
      NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.bridge-not-ready"));
      return 0;
    }

    service.unbind(player).whenComplete((resp, err) -> {
      if (err != null) {
        NeonaicText.send(sender, NeonaicText.tr("neonaic.bind.unbind-failed", err.getLocalizedMessage()));
        return;
      }
      boolean removedAny = resp != null && resp.get("removed") instanceof List<?> removed && !removed.isEmpty();
      NeonaicText.send(sender, removedAny
          ? NeonaicText.tr("neonaic.bind.unbound")
          : NeonaicText.tr("neonaic.bind.unbind-none"));
    });
    return 1;
  }

  private int ask(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      NeonaicText.send(sender, NeonaicText.tr("system.command.player_only"));
      return 0;
    }
    String text = StringArgumentType.getString(ctx, "text");
    subsystem.requestPrivateReply(player, text, "by-cmd");
    return 1;
  }
}
