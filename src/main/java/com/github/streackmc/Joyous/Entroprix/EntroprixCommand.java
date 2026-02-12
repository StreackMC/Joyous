package com.github.streackmc.Joyous.Entroprix;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;

public class EntroprixCommand {
  EntroprixCommand() {
  }

  final void register() {
    Joyous.addPermissions(
      PermDef.op("joyous.commands.entroprix.guarantee"),
      PermDef.op("joyous.commands.entroprix.guarantee.set"),
      PermDef.op("joyous.commands.entroprix.guarantee.set.tries"),
      PermDef.op("joyous.commands.entroprix.guarantee.set.counts"),
      PermDef.op("joyous.commands.entroprix.guarantee.get"),
      PermDef.op("joyous.commands.entroprix.guarantee.reset"),
      PermDef.op("joyous.commands.entroprix.roll")
    );
    Joyous.registerCommand(Commands.literal("entroprix")
      .then(
        Commands.argument("player", ArgumentTypes.player())
        .requires(ctx -> ctx.getSender().hasPermission("minecraft.selector"))
        .then(
          Commands.literal("guarantee")
          .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix"))
          .then(
            Commands.argument("id", StringArgumentType.string())
            .then(
              Commands.literal("get")
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.get"))
              .executes(this::guarantee_get) // /entroprix <player> guarantee <id> get
            ).then(
              Commands.literal("reset")
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.reset"))
              .executes(ctx -> { return guarantee_reset(ctx); }) // /entroprix <player> guarantee <id> reset
            ).then(
              Commands.literal("set")
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.set"))
              .then(
                Commands.literal("tries")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.set.tires"))
                .then(
                  Commands.argument("value", IntegerArgumentType.integer(0))
                  .executes(ctx -> { return guarantee_set(ctx, SET_TYPE.TRIES, IntegerArgumentType.getInteger(ctx, "value")); })// /entroprix <player> guarantee <id> set tries <value>
                )
              ).then(
                Commands.literal("counts")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.set.tires"))
                .then(
                  Commands.argument("value", IntegerArgumentType.integer(0))
                  .executes(ctx -> { return guarantee_set(ctx, SET_TYPE.COUNTS, IntegerArgumentType.getInteger(ctx, "value")); })// /entroprix <player> guarantee <id> set tries <value>
                )
              )
            )
          )
        ).then(
          Commands.literal("roll")
            .then(
              Commands.argument("poolName", StringArgumentType.string())
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.roll"))
              .executes(ctx -> { return roll(ctx, 1);}) // /entroprix <player> roll <poolName>
              .then(
                Commands.argument("times", IntegerArgumentType.integer(1))
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.entroprix.roll"))
                .executes(ctx -> { return roll(ctx, IntegerArgumentType.getInteger(ctx, "times"));}) // /entroprix <player> roll <poolName> [times]
              )
            )
        )
      ).build(), "熵流抽卡", Joyous.conf.getListOfString("entroprix.alias", List.of("ptitle")));
  }
  
  int roll(CommandContext<CommandSourceStack> ctx, int times) {
    return 1;
  }

  static class SET_TYPE {
    final static String TRIES = "tries";
    final static String COUNTS = "counts";
  }

  int guarantee_set(CommandContext<CommandSourceStack> ctx, String type, int times) {
    String name = StringArgumentType.getString(ctx, "id");
    CommandSender sender = ctx.getSource().getSender();
    Player target;

    try {// 读取玩家
      target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      logger.debug("无法设置为指定玩家设置保底状态：%s", e.getLocalizedMessage(), e);
      sender.sendMessage(Joyous.i18n.tr("system.command.target_loss"), e.getLocalizedMessage());
      return 0;
    }

    switch (type) {
      case SET_TYPE.COUNTS:
        EntroprixMain.Guarantee.setCounts(target, name, times);
        sender.sendMessage(Joyous.i18n.tr("entroprix.set.tries", target.getDisplayName(), name, times));
        return 1;
      case SET_TYPE.TRIES:
        EntroprixMain.Guarantee.setTries(target, name, times);
        sender.sendMessage(Joyous.i18n.tr("entroprix.set.counts", target.getDisplayName(), name, times));
        return 1;
      default:
        return 0;
    }
  }

  int guarantee_reset(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "id");
    CommandSender sender = ctx.getSource().getSender();
    Player target;

    try {// 读取玩家
      target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      logger.debug("无法设置为指定玩家设置保底状态：%s", e.getLocalizedMessage(), e);
      sender.sendMessage(Joyous.i18n.tr("system.command.target_loss"), e.getLocalizedMessage());
      return 0;
    }
    EntroprixMain.Guarantee.setCounts(target, name, 0);
    EntroprixMain.Guarantee.setTries(target, name, 0);
    sender.sendMessage(Joyous.i18n.tr("entroprix.set.reset", target.getDisplayName(), name));
    return 1;
  }

  int guarantee_get(CommandContext<CommandSourceStack> ctx) {
    return 1;
  }
}
