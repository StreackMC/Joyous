package com.github.streackmc.Joyous.EnvExport;

import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public class EnvExportCommand {

  final void register() {
    Joyous.addPermissions(
      PermDef.op("joyous.commands.envexport")
    );
    Joyous.registerCommand(Commands.literal("export")
      .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.envexport"))
      .then(
        Commands.argument("key", StringArgumentType.string()).then(
          Commands.argument("value", StringArgumentType.string())
          .executes(ctx -> { return putV(ctx); })
        )
        .executes(ctx -> { return removeV(ctx); })
      ).build(), "EnvExport 环境变量", List.of("envexport", "setx"));
  }

  int removeV(CommandContext<CommandSourceStack> ctx) {
    try {
      String key = StringArgumentType.getString(ctx, "key");
      EnvExport.setEnv(key, null);
      ctx.getSource().getSender().sendMessage(Joyous.i18n.tr("envexport.command.removed", key));
      return 0;
    } catch (Exception e) {
      return 1;
    }
  }

  int putV(CommandContext<CommandSourceStack> ctx) {
    try {
      String key = StringArgumentType.getString(ctx, "key");
      String value = StringArgumentType.getString(ctx, "value");
      EnvExport.setEnv(key, value);
      ctx.getSource().getSender().sendMessage(Joyous.i18n.tr("envexport.command.set", key, value));
      return 0;
    } catch (Exception e) {
      return 1;
    }
  }
}
