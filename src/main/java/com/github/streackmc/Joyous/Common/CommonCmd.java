package com.github.streackmc.Joyous.Common;

import java.util.List;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.StreackLib.types.SConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;

public class CommonCmd {
  CommonCmd() {
  }

  SConfig conf() {
    return CommonMain.conf;
  }

  String perm(String suffix) {
    return CommonMain.NAMES.CMD_PERMISSION_PREFIX(suffix);
  }

  final void register() {
    if (conf().getBoolean("closeinv", true)) {
      Joyous.addPermissions(
          PermDef.all(perm("closeinv"), "关闭正在打开的容器或背包界面"),
          PermDef.op(perm("closeinv.other"), "关闭他人正在打开的容器或背包界面"));
      Joyous.registerCommand(Commands.literal("closeinv")
          .requires(ctx -> ctx.getSender().hasPermission(perm("closeinv")))
          .executes(ctx -> {
            Entity entity = ctx.getSource().getExecutor();
            if (entity instanceof org.bukkit.entity.Player player) {
              player.closeInventory();
              return 0;
            } else {
              ctx.getSource().getSender().sendMessage(i18n.tr("system.command.player_only"));
              return 0;
            }
          })
          .then(
              Commands.argument("player", ArgumentTypes.player())
                  .requires(ctx -> ctx.getSender().hasPermission("minecraft.selector"))
                  .requires(ctx -> ctx.getSender().hasPermission(perm("closeinv.other")))
                  .executes(ctx -> {
                    Player target;
                    try {
                      target = ctx.getArgument("player",
                          io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class)
                          .resolve(ctx.getSource()).getFirst();
                      target.closeInventory(Reason.PLUGIN);
                      return 0;
                    } catch (CommandSyntaxException e) {
                      ctx.getSource().getSender().sendMessage(i18n.tr("system.command.target_loss"));
                      return 0;
                    }
                  }))
          .build(), "关闭正在打开的容器或背包界面", List.of());
    }
  }
}
