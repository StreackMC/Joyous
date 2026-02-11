package com.github.streackmc.Joyous.PlayerTitle;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;

public class PlayerTitleCommand {
  PlayerTitleCommand() {
  }

  final void register() {
    Joyous.addPermissions(
      PermDef.all("joyous.commands.playertitle.set"),
      PermDef.op("joyous.commands.playertitle.set.others")
    );
    Joyous.registerCommand(Commands.literal("playertitle")
      .then(
        Commands.literal("set")
          .then(
            Commands.argument("titleId", StringArgumentType.string())
            .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.playertitle.set"))
            .executes(this::set_titleId) // /playertitle set <titleId>
            .then(
              Commands.argument("target", ArgumentTypes.player())
              .requires(ctx -> ctx.getSender().hasPermission("minecraft.command.selector"))
              .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.playertitle.set.others"))
              .executes(this::set_titleId_target) // /playertitle set <titleId> <target>
            )
          )
      )
      .build(), "玩家称号管理", Joyous.conf.getListOfString("PlayerTitle.alias", List.of("ptitle")));
  }

  private int set_titleId(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    CommandSender sender = ctx.getSource().getSender();

    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.get("system.command.player_only"));
      return 0;
    }
    try {
      PlayerTitleMain.setTitle(player, titleId, false, false);
    } catch (IllegalArgumentException failure) {
      sender.sendMessage(failure.getLocalizedMessage());
      return 0;
    } catch (Exception e) {
      logger.warn("无法为 [%s] 设置称号 [%s]", sender.toString(), titleId, e);
      sender.sendMessage(Joyous.i18n.get("titles.set.wrong"));
      return 0;
    }
    return 1;
  }
  
  private int set_titleId_target(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    CommandSender sender = ctx.getSource().getSender();
    Player target;

    try {
      target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      logger.debug("无法设置为指定玩家设置称号：%s", e.getLocalizedMessage(), e);
      sender.sendMessage(Joyous.i18n.get("system.command.target_loss"));
      return 0;
    }
    try {
      PlayerTitleMain.setTitle(target, titleId, false, false);
    } catch (IllegalArgumentException failure) {
      sender.sendMessage(failure.getLocalizedMessage());
      return 0;
    } catch (Exception e) {
      logger.warn("无法为 [%s] 设置称号 [%s]", sender.toString(), titleId, e);
      sender.sendMessage(Joyous.i18n.get("titles.set.wrong"));
      return 0;
    }
    return 1;
  }

}
