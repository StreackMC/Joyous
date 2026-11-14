package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 出售界面入口与共享的文本/按钮工具
 * <p>
 * 界面本体在 {@link JSellSellPage} 里（生命周期、渲染、点击都在那边），这里只做两件事：
 * <ul>
 *   <li><b>准备会话</b>：取（或新建）托管会话，把崩服遗留、超出放入区容量的部分先还给玩家；</li>
 *   <li>提供<b>文本与按钮</b>的构造工具（本模块的管理员界面也在用）。</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellGui {

  /** 界面总行数 */
  public static final int ROWS = 6;
  /** 界面总格数 */
  public static final int SIZE = ROWS * 9;

  private final JSellMain module;
  private final SellEntryStore entries;
  private final SellSessionManager sessions;
  private final SellService service;

  public JSellGui(JSellMain module, SellEntryStore entries, SellSessionManager sessions,
      SellService service) {
    this.module = module;
    this.entries = entries;
    this.sessions = sessions;
    this.service = service;
  }

  // ──────────────────────────────────────────────
  // 打开
  // ──────────────────────────────────────────────

  /** 打开出售界面（等同无参 {@code /jsell}） */
  public void open(Player player) {
    // 已经开着同一页时只重画：重开等于「关掉再打开」，而关掉出售界面就是取消（东西会全退回背包）
    IPageContext visible = PageArbiter.get().visible(player);
    if (visible != null && visible.page() instanceof JSellSellPage page) {
      page.refresh(visible);
      return;
    }

    SellSession session = sessions.open(player);
    // 崩服遗留的托管内容可能超过放入区容量：多出来的先还给他，
    // 否则它们会「看不见但被卖掉」——那是最糟糕的一种体验
    int max = JSellMain.CONF.maxSlots();
    int overflow = session.size() - max;
    if (overflow > 0) {
      List<ItemStack> extra = new ArrayList<>();
      for (int i = 0; i < overflow; i++) {
        ItemStack taken = session.take(session.size() - 1, 0);
        if (taken != null) {
          extra.add(taken);
        }
      }
      SellSessionManager.giveOrDrop(player, extra);
      sessions.persist(session);
      player.sendMessage(i18n.tr("jsell.escrow.overflow", extra.size()));
    }

    PageArbiter.get().open(player,
        new JSellSellPage(entries, sessions, service, session),
        PagePriorityEnum.REPLACE);
  }

  // ──────────────────────────────────────────────
  // 管理员界面标识（出售界面已迁到 JSellSellPage，不再用 Holder）
  // ──────────────────────────────────────────────

  /** 管理员界面种类 */
  public enum Kind {
    /** 可售物品列表 */
    EDITOR_LIST,
    /** 条目详情 */
    EDITOR_DETAIL
  }

  /** 管理员界面的自定义 Holder（避免用标题字符串判断界面） */
  public static final class Holder implements org.bukkit.inventory.InventoryHolder {
    private final Kind kind;
    private final Object payload;
    private org.bukkit.inventory.Inventory inventory;

    public Holder(Kind kind, @Nullable Object payload) {
      this.kind = kind;
      this.payload = payload;
    }

    public Kind kind() {
      return kind;
    }

    /** 附加上下文（条目 id / 编辑器状态），可为 null */
    @Nullable
    public Object payload() {
      return payload;
    }

    public void attach(org.bukkit.inventory.Inventory inventory) {
      this.inventory = inventory;
    }

    @Override
    public org.bukkit.inventory.Inventory getInventory() {
      return inventory;
    }
  }

  @Nullable
  public static Holder holderOf(@Nullable org.bukkit.inventory.Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    org.bukkit.inventory.InventoryHolder holder = inventory.getHolder();
    return holder instanceof Holder h ? h : null;
  }

  // ──────────────────────────────────────────────
  // 共享工具
  // ──────────────────────────────────────────────

  /** 构造一个按钮物品 */
  public static ItemStack button(Material material, String name, List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(text(null, name));
      if (lore != null && !lore.isEmpty()) {
        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
          lines.add(text(null, line));
        }
        meta.lore(lines);
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  /** 界面里的弱占位（玻璃板），避免快捷移动把物品塞进无主空槽 */
  public static ItemStack filler() {
    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false));
      item.setItemMeta(meta);
    }
    return item;
  }

  /** 物品原名（没有自定义名时用类型名的可读形式） */
  static String prettyName(ItemStack item) {
    String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    return name;
  }

  /**
   * 文本 → Component
   * <p>
   * 物品名/Lore 的原版默认样式是<b>斜体</b>，而 {@code deserialize} 出来的装饰是 NOT_SET
   * （＝继承＝开启），所以未显式设置的装饰要显式关掉。
   */
  public static Component text(@Nullable Player player, String raw) {
    String parsed = raw == null ? "" : raw;
    if (player != null) {
      parsed = i18n.getPHparsed(player, parsed);
    }
    return legacy(com.github.streackmc.StreackLib.utils.MCColor.parse(parsed));
  }

  /** 带 {@code §} 颜色码的文本 → Component（装饰已重置） */
  public static Component legacy(String colorized) {
    Component base = LegacyComponentSerializer.legacySection()
        .deserialize(colorized == null ? "" : colorized);
    return base.style(style -> {
      for (TextDecoration decoration : TextDecoration.values()) {
        if (base.style().decoration(decoration) == TextDecoration.State.NOT_SET) {
          style.decoration(decoration, false);
        }
      }
    });
  }
}
