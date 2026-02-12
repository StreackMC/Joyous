package com.github.streackmc.Joyous.Entroprix;

import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;

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
    return 1;
  }

  int guarantee_reset(CommandContext<CommandSourceStack> ctx) {
    return 1;
  }

  int guarantee_get(CommandContext<CommandSourceStack> ctx) {
    return 1;
  }
}
