package com.github.streackmc.Joyous.SMenu;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * SMenu 命令处理器
 * <p>
 * 提供命令：
 * <ul>
 *   <li>{@code /jmenu open <menu> [player]} — 为（指定）玩家打开菜单</li>
 *   <li>{@code /jmenu get [menu]} — 获取菜单物品</li>
 *   <li>{@code /jmenu reload} — 重载所有菜单缓存</li>
 *   <li>{@code /jmenu help} — 显示帮助</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class SMenuCommand {

  private final SMenuManager manager;

  public SMenuCommand(SMenuManager manager) {
    this.manager = manager;
  }

  /**
   * 注册命令树和权限
   */
  public void register() {
    Joyous.addPermissions(
        PermDef.all("joyous.commands.smenu.open", "打开菜单"),
        PermDef.op("joyous.commands.smenu.open.others", "为他人打开菜单"),
        PermDef.all("joyous.commands.smenu.get", "获取菜单物品"),
        PermDef.op("joyous.commands.smenu.reload", "重载菜单缓存"));

    Joyous.registerCommand(
        Commands.literal("smenu")
            .then(Commands.literal("open")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.smenu.open"))
                .then(Commands.argument("menu", StringArgumentType.string())
                    .executes(this::open) // /jmenu open <menu>
                    .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(ctx -> ctx.getSender().hasPermission("minecraft.selector"))
                        .requires(ctx -> ctx.getSender().hasPermission(
                            "joyous.commands.smenu.open.others"))
                        .executes(this::openOthers)))) // /jmenu open <menu> <player>
            .then(Commands.literal("get")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.smenu.get"))
                .executes(this::get) // /jmenu get
                .then(Commands.argument("menu", StringArgumentType.string())
                    .executes(this::getMenu))) // /jmenu get <menu>
            .then(Commands.literal("reload")
                .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.smenu.reload"))
                .executes(this::reload))
            .then(Commands.literal("help")
                .executes(this::help))
            .build(),
        "Joyous 双端通用菜单系统",
        Joyous.conf.getListOfString("SMenu.alias", List.of()));
  }

  // ──────────────────────────────────────────────
  // 命令实现
  // ──────────────────────────────────────────────

  /** /jmenu open <menu> */
  private int open(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }
    String menuPath = StringArgumentType.getString(ctx, "menu");
    return openForPlayer(player, menuPath, sender);
  }

  /** /jmenu open <menu> <player> */
  private int openOthers(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    String menuPath = StringArgumentType.getString(ctx, "menu");
    Player target;
    try {
      target = ctx.getArgument("player",
          io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class)
          .resolve(ctx.getSource()).getFirst();
    } catch (CommandSyntaxException e) {
      sender.sendMessage(Joyous.i18n.tr("system.command.target_loss"));
      return 0;
    }
    return openForPlayer(target, menuPath, sender);
  }

  /** 为指定玩家打开菜单 */
  private int openForPlayer(Player target, String menuPath, CommandSender sender) {
    try {
      manager.openMenuFor(menuPath, target);
      if (!sender.equals(target)) {
        sender.sendMessage(MCColor.parse("&a已为 &f" + target.getName() + " &a打开菜单"));
      }
      return 1;
    } catch (IllegalArgumentException e) {
      sender.sendMessage(MCColor.parse("&c" + e.getLocalizedMessage()));
      return 0;
    }
  }

  /** /jmenu get */
  private int get(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }
    return giveMenuItem(player, "", sender);
  }

  /** /jmenu get <menu> */
  private int getMenu(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(Joyous.i18n.tr("system.command.player_only"));
      return 0;
    }
    String menuPath = StringArgumentType.getString(ctx, "menu");
    return giveMenuItem(player, menuPath, sender);
  }

  /** 给予玩家菜单物品 */
  private int giveMenuItem(Player player, String menuPath, CommandSender sender) {
    boolean specialized = Joyous.conf.getBoolean("SMenu.menu-item.specialized", true);
    String materialName = Joyous.conf.getString("SMenu.menu-item.material", "clock");
    List<String> display = Joyous.conf.getListOfString("SMenu.menu-item.display",
        List.of("&b菜单", "&7Powered by StreackMC/Joyous."));

    Material material = Material.getMaterial(materialName.toUpperCase());
    if (material == null) {
      sender.sendMessage(MCColor.parse("&c菜单物品材质 [" + materialName + "] 无效"));
      return 0;
    }

    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      if (!display.isEmpty()) {
        var serializer = LegacyComponentSerializer.legacySection();
        meta.displayName(serializer.deserialize(MCColor.parse(display.get(0))));
        if (display.size() > 1) {
          var lore = display.subList(1, display.size()).stream()
              .<Component>map(line -> serializer.deserialize(MCColor.parse(line)))
              .toList();
          meta.lore(lore);
        }
      }

      // 特化模式：写入菜单路径标记到 PDC
      if (specialized) {
        String resolvedPath = menuPath.isBlank() ? "main" : SMenuEntry.resolvePath(menuPath);
        meta.getPersistentDataContainer().set(
            SMenuMain.MENU_ITEM_KEY,
            org.bukkit.persistence.PersistentDataType.STRING,
            resolvedPath);
      }

      item.setItemMeta(meta);
    }

    // 给予物品，背包满则掉落
    var leftover = player.getInventory().addItem(item);
    if (!leftover.isEmpty()) {
      player.getWorld().dropItem(player.getLocation(), item);
    }
    sender.sendMessage(MCColor.parse("&a已获取菜单物品"));
    return 1;
  }

  /** /jmenu reload */
  private int reload(CommandContext<CommandSourceStack> ctx) {
    manager.invalidateAllCache();
    Joyous.conf.reload();
    ctx.getSource().getSender().sendMessage(MCColor.parse("&a已重载所有菜单缓存"));
    logger.info("菜单缓存已由 [%s] 重载", ctx.getSource().getSender().getName());
    return 1;
  }

  /** /jmenu help */
  private int help(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    sender.sendMessage(MCColor.parse("""
        &6/jmenu open <menu> [player]
        &f打开一个菜单，可指定目标玩家
        &2P: &7joyous.commands.smenu.open (默认所有)
        &2P: &7joyous.commands.smenu.open.others (默认 OP)
        &r
        &6/jmenu get [menu]
        &f获取菜单物品
        &2P: &7joyous.commands.smenu.get (默认所有)
        &r
        &6/jmenu reload
        &f重载所有菜单缓存
        &2P: &7joyous.commands.smenu.reload (默认 OP)
        &r
        &6/jmenu help
        &f显示此帮助
        """));
    return 1;
  }
}
