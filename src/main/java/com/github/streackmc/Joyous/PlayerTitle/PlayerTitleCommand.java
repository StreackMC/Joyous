package com.github.streackmc.Joyous.PlayerTitle;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;
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
    Joyous.registerCommand(Commands.literal("playertitle")
      .then(
        Commands.literal("set")
          .then(
            Commands.argument("titleId", StringArgumentType.string())
            .executes(this::set_titleId) // /playertitle set <titleId>
            .then(
              Commands.argument("target", ArgumentTypes.player())
              .requires(ctx -> ctx.getSender().hasPermission("minecraft.command.selector"))
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
      sender.sendMessage(MCColor.parse("&c该命令只能由玩家执行"));
      return 0;
    }
    try {
      PlayerTitleMain.setTitle(player, titleId);
    } catch (Exception e) {
      logger.warn("无法为 [%s] 设置称号 [%s]", sender.toString(), titleId, e);
      sender.sendMessage(MCColor.parse("&c设置称号时发生了错误：" + e.getLocalizedMessage()));
      return 0;
    }
    return 1;
  }
  
  private int set_titleId_target(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    Player target;
    try {
      target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      logger.debug("无法设置为指定玩家设置称号：%s", e.getLocalizedMessage(), e);
      ctx.getSource().getSender().sendMessage(MCColor.parse("&c找不到指定的玩家"));
      return 0;
    }
    PlayerTitleMain.setTitle(target, titleId);
    return 1;
  }

}
