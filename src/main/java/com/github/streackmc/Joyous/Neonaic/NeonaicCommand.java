package com.github.streackmc.Joyous.Neonaic;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.StreackLib.utils.MCColor;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * {@code /neonaic} 管理命令，以及按配置注册的「Bot 名命令」（by-cmd 触发）。
 *
 * @since 0.3.0
 */
public class NeonaicCommand {

  private final NeonaicSubsystem subsystem;

  public NeonaicCommand(NeonaicSubsystem subsystem) {
    this.subsystem = subsystem;
  }

  public void register() {
    Joyous.addPermissions(
        PermDef.op("joyous.commands.neonaic.status", "查看与管理 Neonaic 桥接状态"),
        PermDef.op("joyous.commands.neonaic.bind", "绑定聊天身份"),
        PermDef.all("joyous.neonaic.ai.ask", "通过 @ 或命令请求 AI 回复"));

    Joyous.registerCommand(
        Commands.literal("neonaic")
            .then(Commands.literal("bridge")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.neonaic.status"))
                .then(Commands.literal("status")
                    .executes(this::status))
                .then(Commands.literal("start")
                    .executes(this::start))
                .then(Commands.literal("stop")
                    .executes(this::stop))
                .then(Commands.literal("restart")
                    .executes(this::restart)))
            .then(Commands.literal("bind")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.neonaic.bind"))
                .then(Commands.argument("profile", StringArgumentType.word())
                    .then(Commands.argument("chatId", StringArgumentType.word())
                        .executes(ctx -> bind(ctx, null))
                        .then(Commands.argument("code", StringArgumentType.word())
                            .executes(ctx -> bind(ctx, StringArgumentType.getString(ctx, "code")))))))
            .then(Commands.literal("ask")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.neonaic.ai.ask"))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(this::ask)))
            .build(),
        "Neonaic 桥接管理",
        List.of("neonai"));

    // by-cmd 触发：把配置的 Bot 名 / 副名注册为命令（仅当配置显式给出时）
    registerBotNameCommand(subsystem.botName());
    registerBotNameCommand(subsystem.botSubname());
  }

  private void registerBotNameCommand(String name) {
    if (name == null || name.isBlank())
      return;
    if (!subsystem.byCmdEnabled())
      return;
    Joyous.registerCommand(
        Commands.literal(name)
            .requires(ctx -> ctx.getSender().hasPermission("joyous.neonaic.ai.ask"))
            .then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(this::ask))
            .build(),
        "向 " + name + " 提问（私聊回复）",
        List.of());
  }

  // ---- 子命令实现 ----

  private int status(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    for (String line : subsystem.statusLines()) {
      sender.sendMessage(MCColor.parse(line));
    }
    return 1;
  }

  /** /neonaic bridge start —— 建立连接（仅建链；UDS 模式下本端开始监听，等待对端拨入） */
  private int start(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (subsystem.isConnectionRunning()) {
      sender.sendMessage(MCColor.parse("&e连接已处于建立状态，无需重复建立。"));
      return 1;
    }
    boolean ok = subsystem.startConnection();
    sender.sendMessage(MCColor.parse(ok
        ? "&a已建立连接（模式 " + subsystem.connectionMode() + "）。UDS 模式下等待对端拨入握手。"
        : "&c建立连接失败，请查看控制台日志（检查套接字路径/端口/权限）。"));
    return ok ? 1 : 0;
  }

  /** /neonaic bridge stop —— 断开连接并释放监听 */
  private int stop(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!subsystem.isConnectionRunning()) {
      sender.sendMessage(MCColor.parse("&e连接本就未建立。"));
      return 1;
    }
    subsystem.stopConnection();
    sender.sendMessage(MCColor.parse("&a已断开连接并释放监听。"));
    return 1;
  }

  /** /neonaic bridge restart —— 重连（先断开再建立） */
  private int restart(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    boolean ok = subsystem.restartConnection();
    sender.sendMessage(MCColor.parse(ok
        ? "&a已重连（模式 " + subsystem.connectionMode() + "）。"
        : "&c重连失败，请查看控制台日志。"));
    return ok ? 1 : 0;
  }

  private int bind(CommandContext<CommandSourceStack> ctx, String code) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }
    if (!subsystem.bindIdentityEnabled()) {
      sender.sendMessage(MCColor.parse("&c身份绑定功能未启用。"));
      return 0;
    }
    String profile = StringArgumentType.getString(ctx, "profile");
    String chatId = StringArgumentType.getString(ctx, "chatId");
    subsystem.bind(player, profile, chatId, code).whenComplete((resp, err) -> {
      if (err != null) {
        sender.sendMessage(MCColor.parse("&c绑定失败：" + err.getLocalizedMessage()));
        return;
      }
      boolean bound = resp != null && Boolean.TRUE.equals(resp.get("bound"));
      sender.sendMessage(MCColor.parse(bound
          ? "&a已绑定身份：" + profile + " / " + chatId
          : "&e绑定未完成（可能校验码错误）：" + profile));
    });
    return 1;
  }

  private int ask(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }
    String text = StringArgumentType.getString(ctx, "text");
    subsystem.requestPrivateReply(player, text, "by-cmd");
    return 1;
  }
}
