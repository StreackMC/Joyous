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
      ).then(
        Commands.literal("view")
          .then(
            Commands.argument("titleId", StringArgumentType.string())
            .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.playertitle.set"))
            .executes(this::view_titleId) // /playertitle view <titleId>
          )
      ).then(
        Commands.literal("help").executes(this::help)
      ).build(), "玩家称号管理", Joyous.conf.getListOfString("PlayerTitle.alias", List.of("ptitle")));
  }

  private int help(CommandContext<CommandSourceStack> ctx) {// 显示帮助
    CommandSender sender = ctx.getSource().getSender();
    sender.sendMessage(Joyous.i18n.tr("titles.help"));
    return 1;
  }

  private int view_titleId(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    ctx.getSource().getSender().sendMessage(String.format("称号样式预览：%s", PlayerTitleMain.getTitle(titleId)));;
    return 1;
  }

  private int set_titleId(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    CommandSender sender = ctx.getSource().getSender();

    // 不允许控制台执行
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }

    // 设置称号
    try {
      PlayerTitleMain.setTitle(player, titleId, false, false);
    } catch (IllegalArgumentException failure) {
      sender.sendMessage(failure.getLocalizedMessage());
      return 0;
    } catch (Exception e) {
      logger.warn("无法为 [%s] 设置称号 [%s]", sender.toString(), titleId, e);
      sender.sendMessage(Joyous.i18n.tr("titles.set.wrong"), e.getLocalizedMessage());
      return 0;
    }
    return 1;
  }
  
  private int set_titleId_target(CommandContext<CommandSourceStack> ctx) {
    String titleId = StringArgumentType.getString(ctx, "titleId");
    CommandSender sender = ctx.getSource().getSender();
    Player target;

    try {// 读取玩家
      target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      logger.debug("无法设置为指定玩家设置称号：%s", e.getLocalizedMessage(), e);
      sender.sendMessage(Joyous.i18n.tr("system.command.target_loss"), e.getLocalizedMessage());
      return 0;
    }

    try {// 设置称号
      PlayerTitleMain.setTitle(target, titleId, true, false);
    } catch (IllegalArgumentException failure) {
      sender.sendMessage(failure.getLocalizedMessage());
      return 0;
    } catch (Exception e) {
      logger.warn("无法为 [%s] 设置称号 [%s]", sender.toString(), titleId, e);
      sender.sendMessage(Joyous.i18n.tr("titles.set.wrong"));
      return 0;
    }
    return 1;
  }

}
