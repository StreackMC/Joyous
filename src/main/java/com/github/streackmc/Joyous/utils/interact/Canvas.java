package com.github.streackmc.Joyous.utils.interact;

import java.util.List;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 渲染画布
 * <p>
 * 包住一个 {@link Inventory}，只允许「往格子里放东西」这类安全操作，
 * 并把三处重复过的文本处理收成一份：
 * <ul>
 *   <li>{@link #legacy(String)} —— 颜色码 + 显式关掉斜体</li>
 *   <li>{@link #title(Player, String)} —— 颜色码 + PAPI</li>
 *   <li>{@link #button(Material, String, List)} —— 带名字与 Lore 的按钮</li>
 * </ul>
 * <p>
 * <b>为什么必须显式关掉斜体</b>：{@code §r} 只把装饰设成 {@code NOT_SET}，而原版渲染物品名/悬浮文本时
 * 会把「未设置」按「启用」处理，于是所有名字都变斜体。这里把未显式设置的装饰一律写成 {@code false}，
 * 显式写了 {@code §l} 之类的仍保留。
 *
 * @author Neonai
 * @since 0.7.0
 */
public final class Canvas {

  private final Inventory inventory;

  public Canvas(Inventory inventory) {
    this.inventory = inventory;
  }

  /** 界面槽位数 */
  public int size() {
    return inventory.getSize();
  }

  /** 清空整个界面 */
  public void clear() {
    inventory.clear();
  }

  /**
   * 往某一格放东西
   *
   * <p>越界与 {@code null} 都静默忽略：页面在容器尺寸被改小时不该抛异常。
   */
  public void set(int slot, @Nullable ItemStack item) {
    if (slot < 0 || slot >= inventory.getSize()) {
      return;
    }
    inventory.setItem(slot, item);
  }

  /**
   * 用同一个物品铺满一段区间（含两端）
   *
   * @param from 起始槽位
   * @param to   结束槽位（含）
   */
  public void fill(int from, int to, @Nullable ItemStack item) {
    if (item == null) {
      return;
    }
    for (int i = Math.max(0, from); i <= Math.min(to, inventory.getSize() - 1); i++) {
      inventory.setItem(i, item.clone());
    }
  }

  /** 用同一个物品铺满整个界面 */
  public void fill(@Nullable ItemStack item) {
    fill(0, inventory.getSize() - 1, item);
  }

  // ──────────────────────────────────────────────
  // 文本与物品
  // ──────────────────────────────────────────────

  /**
   * 把带 {@code &} 或 {@code §} 颜色码的字符串转成组件，并显式关闭未设置的装饰
   *
   * @param colorized 原始文本，可为 null
   */
  public static Component legacy(@Nullable String colorized) {
    return ensureReset(
        LegacyComponentSerializer.legacySection().deserialize(colorized == null ? "" : colorized));
  }

  /**
   * 界面标题：先解析 PAPI，再转组件
   *
   * @param player 用于解析玩家相关占位符，可为 null
   * @param raw    原始标题
   */
  public static Component title(@Nullable Player player, @Nullable String raw) {
    return legacy(MCColor.parse(i18n.getPHparsed(player, raw == null ? "" : raw)));
  }

  /**
   * 构造一个按钮物品
   *
   * @param material 材质
   * @param name     显示名（支持颜色码）
   * @param lore     Lore 行（支持颜色码），可为 null
   */
  public static ItemStack button(Material material, String name, @Nullable List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return item;
    }
    meta.displayName(legacy(name));
    if (lore != null && !lore.isEmpty()) {
      meta.lore(lore.stream().map(Canvas::legacy).toList());
    }
    item.setItemMeta(meta);
    return item;
  }

  /** 确保未显式设置的文字装饰为 false，避免被原版渲染成斜体 */
  private static Component ensureReset(Component component) {
    var style = component.style();
    return component.style(s -> {
      for (TextDecoration dec : TextDecoration.values()) {
        if (style.decoration(dec) == TextDecoration.State.NOT_SET) {
          s.decoration(dec, false);
        }
      }
    });
  }
}
