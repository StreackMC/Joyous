package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.i18n;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * JSell 的两个命令
 * <ul>
 *   <li>{@code /jsell}（别名 {@code /sell}）：{@code gui} / {@code hand} / {@code inv} / {@code all}</li>
 *   <li>{@code /jsellset}（别名 {@code /sellset}）：打开编辑界面、重载、核查未完成事务</li>
 * </ul>
 * 出售是面向玩家的功能，但同样挂了权限节点（{@link JSellListener.Perm#USE}）：
 * 有些服务器要按身份或世界整块关掉这套系统，用权限比改配置重启省事。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellCommand {

  private final JSellMain module;
  private final SellEntryStore entries;
  private final SellSessionManager sessions;
  private final JSellGui gui;
  private final JSellEditorGui editor;
  private final SellStore store;

  public JSellCommand(JSellMain module, SellEntryStore entries, SellSessionManager sessions,
      JSellGui gui, JSellEditorGui editor, SellStore store) {
    this.module = module;
    this.entries = entries;
    this.sessions = sessions;
    this.gui = gui;
    this.editor = editor;
    this.store = store;
  }

  /** 注册命令与权限 */
  public void register() {
    Joyous.addPermissions(
        PermDef.all(JSellListener.Perm.USE, "使用出售系统"),
        PermDef.op(JSellListener.Perm.ADMIN, "编辑可售列表与核查出售事务"));

    Joyous.registerCommand(
        Commands.literal(JSellMain.COMMAND)
            .requires(ctx -> ctx.getSender().hasPermission(JSellListener.Perm.USE))
            .executes(this::cmdGui)
            .then(Commands.literal("gui").executes(this::cmdGui))
            .then(Commands.literal("hand").executes(this::cmdHand))
            .then(Commands.literal("inv").executes(this::cmdInv))
            .then(Commands.literal("all").executes(this::cmdAll))
            .build(),
        "出售物品",
        JSellMain.ALIASES);

    Joyous.registerCommand(
        Commands.literal(JSellMain.ADMIN_COMMAND)
            .requires(ctx -> ctx.getSender().hasPermission(JSellListener.Perm.ADMIN))
            .executes(this::cmdEditor)
            .then(Commands.literal("reload").executes(this::cmdReload))
            .then(Commands.literal("pending").executes(this::cmdPending))
            .then(Commands.literal("restore")
                .then(Commands.argument("tx", StringArgumentType.string())
                    .executes(this::cmdRestore)))
            // 快捷设价：/sellset <单价> [物品ID]（省略物品 = 主手）
            .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                .executes(this::cmdSetHandValue)
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests(this::suggestItems)
                    .executes(this::cmdSetItemValue)))
            .build(),
        "编辑可售物品列表",
        JSellMain.ADMIN_ALIASES);
  }

  // ──────────────────────────────────────────────
  // 玩家命令
  // ──────────────────────────────────────────────

  private int cmdGui(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    gui.open(player);
    return 1;
  }

  private int cmdHand(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    module.service().sellHand(player);
    return 1;
  }

  private int cmdInv(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    module.service().sellInventory(player);
    return 1;
  }

  private int cmdAll(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    // 界面里还有没结算的东西时，先提醒一句（免得他以为「全部」把那些也算进去了）
    SellSession session = sessions.get(player);
    if (session != null && !session.isEmpty()) {
      player.sendMessage(i18n.tr("jsell.command.session_open", session.size()));
    }
    module.service().sellAll(player);
    return 1;
  }

  // ──────────────────────────────────────────────
  // 管理员命令
  // ──────────────────────────────────────────────

  private int cmdEditor(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    editor.open(player);
    return 1;
  }

  /**
   * {@code /sellset <单价>} —— 给主手物品定价
   * <p>
   * 面向「手上拿着一个东西，想直接给它定个收购价」的场景，比先进列表翻条目快得多。
   */
  private int cmdSetHandValue(CommandContext<CommandSourceStack> ctx) {
    return setValue(ctx, DoubleArgumentType.getDouble(ctx, "value"), null);
  }

  /** {@code /sellset <单价> <物品ID>} —— 给指定物品定价 */
  private int cmdSetItemValue(CommandContext<CommandSourceStack> ctx) {
    return setValue(ctx, DoubleArgumentType.getDouble(ctx, "value"),
        StringArgumentType.getString(ctx, "item"));
  }

  /**
   * 给某件物品设单个收购价，随后直接打开它的编辑页
   * <p>
   * 列表里还没有这件物品时就用它登记一条——主手物品会连 NBT 一起带进去，
   * 所以带附魔/自定义名的东西也能被准确匹配。
   *
   * @param itemArg 物品 ID（可以是 {@code hand}）；null 等同 {@code hand}
   */
  private int setValue(CommandContext<CommandSourceStack> ctx, double value,
      @Nullable String itemArg) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    String key = itemArg == null ? "hand" : itemArg.trim().toLowerCase(Locale.ROOT);

    ItemStack target;
    if (key.isEmpty() || key.equals("hand") || key.equals("mainhand") || key.equals("this")) {
      target = player.getInventory().getItemInMainHand();
      if (target == null || target.getType().isAir()) {
        player.sendMessage(i18n.tr("jsell.command.value_no_item"));
        return 0;
      }
    } else {
      Material material = Material.matchMaterial(key);
      if (material == null || material.isAir() || !material.isItem()) {
        player.sendMessage(i18n.tr("jsell.command.value_bad_item", itemArg));
        return 0;
      }
      // 同一材质可能已经登记过带 NBT 的条目：优先改那一条，别再建一条「白板」重复项
      SellEntry known = entries.findIncludingRemoved(new ItemStack(material));
      if (known == null) {
        for (SellEntry candidate : entries.all()) {
          if (candidate.template().getType() == material) {
            known = candidate;
            break;
          }
        }
      }
      target = known == null ? new ItemStack(material) : known.template();
    }

    SellEntry entry = entries.findIncludingRemoved(target);
    boolean created = entry == null;
    if (created) {
      entry = new SellEntry(UUID.randomUUID(), target, value, System.currentTimeMillis());
    } else {
      entry.unitValue(value);
    }
    entries.save(entry);
    entries.flush();

    String name = JSellGui.prettyName(target);
    player.sendMessage(i18n.tr(created ? "jsell.command.value_added" : "jsell.command.value_set",
        name, SellService.display(entry.unitValue()), JSellMain.CONF.unitName()));
    if (entry.removed()) {
      player.sendMessage(i18n.tr("jsell.command.value_removed_hint"));
    }
    editor.openDetail(player, entry.id());
    return 1;
  }

  /**
   * 物品 ID 补全
   * <p>
   * 除了 {@code hand}，还会列出现有材质的键名；最多 64 条，避免一次推上千个候选把客户端压住。
   */
  private CompletableFuture<Suggestions> suggestItems(CommandContext<CommandSourceStack> ctx,
      SuggestionsBuilder builder) {
    String prefix = builder.getRemainingLowerCase();
    if ("hand".startsWith(prefix)) {
      builder.suggest("hand");
    }
    int count = 0;
    for (Material material : Material.values()) {
      if (material.isAir() || !material.isItem()) {
        continue;
      }
      String id = material.getKey().getKey();
      if (!id.startsWith(prefix)) {
        continue;
      }
      builder.suggest(id);
      if (++count >= 64) {
        break;
      }
    }
    return builder.buildFuture();
  }

  private int cmdReload(CommandContext<CommandSourceStack> ctx) {
    entries.reload();
    reply(ctx, i18n.tr("jsell.command.reloaded", entries.size()));
    return 1;
  }

  /**
   * 列出没走完的出售事务
   * <p>
   * 停在「付款阶段」的单子说明当时可能既没给钱也没退东西，需要人工判断；
   * 这里只报事实，不动任何数据。
   */
  private int cmdPending(CommandContext<CommandSourceStack> ctx) {
    if (!store.available()) {
      reply(ctx, i18n.tr("jsell.command.no_database"));
      return 0;
    }
    try {
      List<SellStore.Tx> txs = store.loadTxs();
      if (txs.isEmpty()) {
        reply(ctx, i18n.tr("jsell.command.pending_none"));
        return 1;
      }
      reply(ctx, i18n.tr("jsell.command.pending_header", txs.size()));
      for (SellStore.Tx tx : txs) {
        reply(ctx, i18n.tr("jsell.command.pending_line", tx.id(), tx.player(),
            tx.status(), SellService.display(tx.amount()), tx.sold().size(), tx.refunds().size(),
            tx.note()));
      }
      reply(ctx, i18n.tr("jsell.command.pending_hint"));
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("读取事务列表", e);
      reply(ctx, i18n.tr("jsell.command.failed", String.valueOf(e.getMessage())));
    }
    return 1;
  }

  /**
   * 把某个事务里的物品退还给玩家（人工补偿用）
   * <p>
   * 这是<b>管理员手动</b>动作，插件不会自动调用它：如果那笔钱其实已经给了，退物品就等于多给一份。
   * 玩家在线就直接塞背包，不在线就写回托管表，等他上线自动交付。
   */
  private int cmdRestore(CommandContext<CommandSourceStack> ctx) {
    String txId = StringArgumentType.getString(ctx, "tx");
    if (!store.available()) {
      reply(ctx, i18n.tr("jsell.command.no_database"));
      return 0;
    }
    try {
      SellStore.Tx target = null;
      for (SellStore.Tx tx : store.loadTxs()) {
        if (tx.id().equalsIgnoreCase(txId)) {
          target = tx;
          break;
        }
      }
      if (target == null) {
        reply(ctx, i18n.tr("jsell.command.restore_missing", txId));
        return 0;
      }
      List<ItemStack> items = new ArrayList<>();
      items.addAll(target.sold());
      items.addAll(target.refunds());
      if (items.isEmpty()) {
        store.deleteTx(target.id());
        reply(ctx, i18n.tr("jsell.command.restore_empty", txId));
        return 1;
      }
      Player online = Bukkit.getPlayer(target.player());
      if (online != null) {
        SellSessionManager.giveOrDrop(online, items);
        online.sendMessage(i18n.tr("jsell.escrow.restored", items.size()));
      } else {
        store.saveEscrow(target.player(), items);
      }
      store.deleteTx(target.id());
      reply(ctx, i18n.tr("jsell.command.restored", txId, items.size(),
          nameOf(target.player())));
    } catch (SellStore.SellStoreException e) {
      module.reportStorageError("补发事务物品", e);
      reply(ctx, i18n.tr("jsell.command.failed", String.valueOf(e.getMessage())));
    }
    return 1;
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  @org.jetbrains.annotations.Nullable
  private static Player playerOf(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (sender instanceof Player player) {
      return player;
    }
    sender.sendMessage(i18n.tr("system.command.player_only"));
    return null;
  }

  private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
    ctx.getSource().getSender().sendMessage(message);
  }

  /** 未使用但保留：把 UUID 转成可读名字 */
  static String nameOf(UUID uuid) {
    String name = Bukkit.getOfflinePlayer(uuid).getName();
    return name == null ? uuid.toString() : name;
  }
}
