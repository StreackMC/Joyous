package com.github.streackmc.Joyous.Entroprix;

import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.mojang.brigadier.arguments.StringArgumentType;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;

public class EntroprixCommand {
  EntroprixCommand() {
  }

  final void register() {
    Joyous.addPermissions(
      PermDef.all("joyous.commands.Entroprix.set"),
      PermDef.op("joyous.commands.Entroprix.set.others"),
      PermDef.all("joyous.commands.Entroprix.preview")
    );
    Joyous.registerCommand(Commands.literal("Entroprix")
      .then(
        Commands.literal("set")
          .then(
            Commands.argument("titleId", StringArgumentType.string())
            .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.Entroprix.set"))
            .executes(this::set_titleId) // /Entroprix set <titleId>
            .then(
              Commands.argument("target", ArgumentTypes.player())
              .requires(ctx -> ctx.getSender().hasPermission("minecraft.command.selector"))
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.Entroprix.set.others"))
              .executes(this::set_titleId_target) // /Entroprix set <titleId> <target>
            )
          )
      ).then(
        Commands.literal("preview")
          .then(
            Commands.argument("titleId", StringArgumentType.string())
            .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.Entroprix.preview"))
            .executes(this::preview_titleId) // /Entroprix preview <titleId>
          )
      ).then(
        Commands.literal("help").executes(this::help)
      ).build(), "玩家称号管理", Joyous.conf.getListOfString("Entroprix.alias", List.of("ptitle")));
  }
}
