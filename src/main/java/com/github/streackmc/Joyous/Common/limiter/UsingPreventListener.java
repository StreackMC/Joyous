package com.github.streackmc.Joyous.Common.limiter;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.Common.CommonMain;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 「using prevent」：按 {@code custom_data} 拦截物品的「使用」动作。
 *
 * <p>命中配置 {@code using-prevent.custom-data} 任意一项的物品：
 * <ul>
 *   <li>无法放入熔炉 / 高炉 / 烟熏炉 / 酿造台 / 切石机 / 制图台 / 铁砧 / 砂轮 /
 *       织布机 / 锻造台 / 附魔台 / 信标 / 自动合成器 / 讲台等工作方块；</li>
 *   <li>无法参与合成（工作台 3x3 与背包 2x2 都不行）。</li>
 * </ul>
 * 纯存储容器（箱子、木桶、潜影盒、末影箱、漏斗、投掷器）不受影响。
 *
 * <p><b>custom_data 的读取口径</b>：优先交给 Item-NBT-API（{@link NbtCustomData}）——
 * 1.20.5+ 起 {@code NBT.get(ItemStack, ...)} 访问的就是 {@code custom_data} 组件本身，
 * 能同时看到顶层键（数据包 / {@code /give} 写入）与 {@code PublicBukkitValues} 的子键
 * （Paper API PDC 的落点）。NBTAPI 插件缺席时自动回退到 Paper 的 PDC 视图，
 * 此时只覆盖 {@code PublicBukkitValues} 这一层。
 *
 * <p>配置项若显式写了 {@code PublicBukkitValues.} / {@code custom_data.PublicBukkitValues.}
 * 前缀，会被自动剥掉；只写键名而不带命名空间时，则按「任意命名空间的同名键」宽松匹配。
 *
 * @author Neonai
 * @author kdxiaoyi
 * @since 0.4.1
 */
public final class UsingPreventListener implements Listener {

  private static final String KEY_ENABLED = "using-prevent.enabled";
  private static final String KEY_CUSTOM_DATA = "using-prevent.custom-data";

  /**
   * 「工作方块」白名单：这些容器里「非结果槽」不接受受限物品。
   * 刻意不包含 CHEST / BARREL / SHULKER_BOX / ENDER_CHEST / HOPPER / DROPPER / DISPENSER
   * 这类纯存储容器——往箱子里放东西不算「使用」。
   */
  private static final Set<InventoryType> WORK_STATIONS = EnumSet.of(
      InventoryType.FURNACE,
      InventoryType.BLAST_FURNACE,
      InventoryType.SMOKER,
      InventoryType.BREWING,
      InventoryType.STONECUTTER,
      InventoryType.CARTOGRAPHY,
      InventoryType.ANVIL,
      InventoryType.GRINDSTONE,
      InventoryType.LOOM,
      // 1.21 起 SMITHING 就是 4 槽锻造台；SMITHING_NEW 已废弃待删除，不再使用
      InventoryType.SMITHING,
      InventoryType.ENCHANTING,
      InventoryType.BEACON,
      InventoryType.CRAFTER,
      InventoryType.LECTERN,
      // 合成：3x3 与背包 2x2
      InventoryType.WORKBENCH,
      InventoryType.CRAFTING);

  // ──────────────────────────────────────────────
  // 配置
  // ──────────────────────────────────────────────

  private static boolean enabled() {
    SConfig conf = CommonMain.conf;
    return conf != null && conf.getBoolean(KEY_ENABLED, true);
  }

  private static List<String> patterns() {
    SConfig conf = CommonMain.conf;
    if (conf == null) {
      return List.of();
    }
    List<String> list = conf.getListOfString(KEY_CUSTOM_DATA, List.of());
    return list == null ? List.of() : list;
  }

  // ──────────────────────────────────────────────
  // custom_data 匹配
  // ──────────────────────────────────────────────

  private static boolean isRestricted(ItemStack stack, List<String> patterns) {
    if (stack == null || stack.getType().isAir() || patterns.isEmpty()) {
      return false;
    }
    Set<String> present = presentKeys(stack);
    if (present.isEmpty()) {
      return false;
    }
    for (String raw : patterns) {
      String token = normalize(raw);
      if (!token.isEmpty() && hit(present, token)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 物品 custom_data 里存在的键（统一小写，形如 {@code namespace:key}）。
   *
   * <p>优先走 Item-NBT-API：它能同时给出顶层键与 {@code PublicBukkitValues} 下的键；
   * NBTAPI 缺席时回退到 Paper 的 PDC 视图（只覆盖 {@code PublicBukkitValues}）。
   */
  private static Set<String> presentKeys(ItemStack stack) {
    Set<String> raw = NbtCustomData.keys(stack);
    if (raw == null) {
      raw = new HashSet<>();
      for (NamespacedKey key : stack.getPersistentDataContainer().getKeys()) {
        raw.add(key.toString());
      }
    }
    if (raw.isEmpty()) {
      return raw;
    }
    Set<String> normalized = new HashSet<>(raw.size());
    for (String key : raw) {
      normalized.add(key.toLowerCase(Locale.ROOT));
    }
    return normalized;
  }

  private static boolean hit(Set<String> present, String token) {
    if (present.contains(token)) {
      return true;
    }
    if (token.indexOf(':') >= 0) {
      // 写了命名空间就必须精确命中
      return false;
    }
    // 未写命名空间：按本地键名匹配任意命名空间（顶层裸键也在此列）
    for (String key : present) {
      int colon = key.indexOf(':');
      String local = colon >= 0 ? key.substring(colon + 1) : key;
      if (local.equalsIgnoreCase(token)) {
        return true;
      }
    }
    return false;
  }

  /** 归一化配置项：去空白/引号，剥掉显式书写的 PublicBukkitValues 前缀，并统一小写。 */
  private static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1).trim();
    }
    s = stripPrefix(s, "custom_data.PublicBukkitValues.");
    s = stripPrefix(s, "PublicBukkitValues.");
    s = stripPrefix(s, "PublicBukkitValues:");
    return s.toLowerCase(Locale.ROOT);
  }

  private static String stripPrefix(String value, String prefix) {
    if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
      return value.substring(prefix.length()).trim();
    }
    return value;
  }

  private static boolean containsRestricted(ItemStack[] items, List<String> patterns) {
    if (items == null) {
      return false;
    }
    for (ItemStack item : items) {
      if (isRestricted(item, patterns)) {
        return true;
      }
    }
    return false;
  }

  // ──────────────────────────────────────────────
  // 点击 / 拖拽：放进工作方块
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onClick(InventoryClickEvent event) {
    if (!enabled() || !WORK_STATIONS.contains(event.getView().getType())) {
      return;
    }
    ItemStack moving = itemEnteringStation(event);
    if (moving == null) {
      return;
    }
    if (isRestricted(moving, patterns())) {
      event.setCancelled(true);
    }
  }

  /**
   * 本次点击「将要进入」工作方块的物品；不是放入动作时返回 null。
   *
   * <p>结果槽只出不进；顶部槽的 Shift 是取回背包；玩家背包界面（CRAFTING）与工作台的
   * 底部 Shift 不会自动填充合成格，故不拦截，避免误伤背包整理。
   */
  private static ItemStack itemEnteringStation(InventoryClickEvent event) {
    InventoryView view = event.getView();
    int topSize = view.getTopInventory().getSize();
    int raw = event.getRawSlot();
    if (raw < 0) {
      return null;
    }

    if (raw < topSize) {
      if (view.getSlotType(raw) == InventoryType.SlotType.RESULT) {
        return null;
      }
      if (event.isShiftClick()) {
        return null;
      }
      return switch (event.getClick()) {
        case LEFT, RIGHT -> event.getCursor();
        case NUMBER_KEY -> hotbarItem(event);
        case SWAP_OFFHAND -> offhandItem(event);
        default -> null;
      };
    }

    // 点击在玩家侧：只有 Shift 可能把该物品塞进工作方块
    if (!event.isShiftClick()) {
      return null;
    }
    if (view.getType() == InventoryType.CRAFTING || view.getType() == InventoryType.WORKBENCH) {
      return null;
    }
    return hasFreeInputSlot(view) ? event.getCurrentItem() : null;
  }

  private static boolean hasFreeInputSlot(InventoryView view) {
    int topSize = view.getTopInventory().getSize();
    for (int slot = 0; slot < topSize; slot++) {
      if (view.getSlotType(slot) == InventoryType.SlotType.RESULT) {
        continue;
      }
      ItemStack current = view.getTopInventory().getItem(slot);
      if (current == null || current.getType().isAir()) {
        return true;
      }
    }
    return false;
  }

  private static ItemStack hotbarItem(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return null;
    }
    int button = event.getHotbarButton();
    if (button < 0 || button > 8) {
      return null;
    }
    return player.getInventory().getItem(button);
  }

  private static ItemStack offhandItem(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return null;
    }
    return player.getInventory().getItemInOffHand();
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDrag(InventoryDragEvent event) {
    if (!enabled() || !WORK_STATIONS.contains(event.getView().getType())) {
      return;
    }
    List<String> patterns = patterns();
    if (!isRestricted(event.getOldCursor(), patterns)) {
      return;
    }
    InventoryView view = event.getView();
    int topSize = view.getTopInventory().getSize();
    for (int slot : event.getRawSlots()) {
      if (slot >= 0 && slot < topSize && view.getSlotType(slot) != InventoryType.SlotType.RESULT) {
        event.setCancelled(true);
        return;
      }
    }
  }

  // ──────────────────────────────────────────────
  // 合成：不能作为材料
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH)
  public void onPrepareCraft(PrepareItemCraftEvent event) {
    if (!enabled()) {
      return;
    }
    if (containsRestricted(event.getInventory().getMatrix(), patterns())) {
      event.getInventory().setResult(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    if (!enabled()) {
      return;
    }
    if (containsRestricted(event.getInventory().getMatrix(), patterns())) {
      event.setCancelled(true);
    }
  }

  // ──────────────────────────────────────────────
  // 自动化输入：漏斗 / 投掷器等
  // ──────────────────────────────────────────────

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onMoveItem(InventoryMoveItemEvent event) {
    if (!enabled() || !WORK_STATIONS.contains(event.getDestination().getType())) {
      return;
    }
    if (isRestricted(event.getItem(), patterns())) {
      event.setCancelled(true);
    }
  }
}
