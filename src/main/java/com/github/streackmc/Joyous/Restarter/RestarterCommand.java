package com.github.streackmc.Joyous.Restarter;

import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * Restarter 模块命令系统
 */
public class RestarterCommand {
  RestarterCommand() {
  }

  final void register() {
    Joyous.addPermissions(
        PermDef.op("joyous.commands.restarter.restart", "计划重启服务器"),
        PermDef.op("joyous.commands.restarter.stop", "计划关闭服务器"),
        PermDef.op("joyous.commands.restarter.cancel", "取消计划重启/关闭"),
        PermDef.op("joyous.commands.restarter.fp", "管理假人列表"));

    // /jrestart [秒数] [理由]
    Joyous.registerCommand(Commands.literal("jrestart")
        .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.restarter.restart"))
        .executes(ctx -> {
          RestarterMain.scheduleRestart(RestarterMain.getDefaultTimeout(), "");
          return 1;
        })
        .then(
            Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                .executes(ctx -> {
                  int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                  RestarterMain.scheduleRestart(seconds, "");
                  return 1;
                })
                .then(
                    Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> {
                          int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                          String reason = StringArgumentType.getString(ctx, "reason");
                          RestarterMain.scheduleRestart(seconds, reason);
                          return 1;
                        })))
        .build(), "计划重启服务器", List.of());

    // /jstop [秒数] [理由]
    Joyous.registerCommand(Commands.literal("jstop")
        .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.restarter.stop"))
        .executes(ctx -> {
          RestarterMain.scheduleStop(RestarterMain.getDefaultTimeout(), "");
          return 1;
        })
        .then(
            Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                .executes(ctx -> {
                  int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                  RestarterMain.scheduleStop(seconds, "");
                  return 1;
                })
                .then(
                    Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> {
                          int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                          String reason = StringArgumentType.getString(ctx, "reason");
                          RestarterMain.scheduleStop(seconds, reason);
                          return 1;
                        })))
        .build(), "计划关闭服务器", List.of());

    // /jrestarter cancel — 取消计划
    // /jrestarter fp add|remove|list — 假人管理
    var fpNode = Commands.literal("fp")
        .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.restarter.fp")
            && RestarterMain.isFpCommandCapable())
        .then(
            Commands.literal("add")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes(this::fpAdd)))
        .then(
            Commands.literal("remove")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes(this::fpRemove)))
        .then(
            Commands.literal("list")
                .executes(this::fpList));

    Joyous.registerCommand(Commands.literal("jrestarter")
        .then(
            Commands.literal("cancel")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.restarter.cancel"))
                .executes(this::cancelAction))
        .then(fpNode)
        .build(), "服务器重启管理", List.of());
  }

  /** /jrestarter cancel — 取消计划 */
  private int cancelAction(CommandContext<CommandSourceStack> ctx) {
    if (!RestarterMain.scheduled) {
      ctx.getSource().getSender().sendMessage("§e当前没有计划的服务器重启或关闭。");
      return 1;
    }
    RestarterMain.cancelCountdown();
    Bukkit.broadcastMessage("§a服务器重启/关闭计划已被 §e" + ctx.getSource().getSender().getName() + " §a取消。");
    return 1;
  }

  /** /jrestarter fp add <player> — 添加假人 */
  private int fpAdd(CommandContext<CommandSourceStack> ctx) {
    String name = StringArgumentType.getString(ctx, "player");
    if (RestarterMain.addFakePlayer(name)) {
      ctx.getSource().getSender().sendMessage("§a已将 §f" + name + " §a添加到假人恢复列表。");
    } else {
      ctx.getSource().getSender().sendMessage("§e" + name + " §e已在假人恢复列表中。");
    }
    return 1;
  }

  /** /jrestarter fp remove <player> — 移除假人 */
  private int fpRemove(CommandContext<CommandSourceStack> ctx) {
    String name = StringArgumentType.getString(ctx, "player");
    if (RestarterMain.removeFakePlayer(name)) {
      ctx.getSource().getSender().sendMessage("§a已将 §f" + name + " §a从假人恢复列表中移除。");
    } else {
      ctx.getSource().getSender().sendMessage("§e" + name + " §e不在假人恢复列表中。");
    }
    return 1;
  }

  /** /jrestarter fp list — 列出假人 */
  private int fpList(CommandContext<CommandSourceStack> ctx) {
    Set<String> fps = RestarterMain.fakePlayers;
    if (fps.isEmpty()) {
      ctx.getSource().getSender().sendMessage("§7当前假人恢复列表为空。");
    } else {
      ctx.getSource().getSender()
          .sendMessage("§a假人恢复列表 (§f" + fps.size() + "§a): §f" + String.join(", ", fps));
    }
    return 1;
  }
}
