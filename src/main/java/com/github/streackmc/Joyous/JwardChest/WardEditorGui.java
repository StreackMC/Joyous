package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;


/**
 * 编辑界面
 * <p>
 * 主界面固定 7 个按钮（容器类型 / 预设代号 / 待选物品 / 各品质最大抽出 / 标题 / 粗筛时长 / 预览）。
 * 除「待选物品」子菜单带显式保存/取消外，其余改动即时生效并落盘。
 * 所有编辑都通过 {@link WardProfileStore#effective(UUID)} 取生效设置，
 * 因此属于预设组时会自动联动整组。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardEditorGui {

  private final JwardChestMain module;
  private final WardProfileStore store;
  private final WardSessionManager sessions;
  private final WardSearchController search;

  public WardEditorGui(JwardChestMain module, WardProfileStore store, WardSessionManager sessions,
      WardSearchController search) {
    this.module = module;
    this.store = store;
    this.sessions = sessions;
    this.search = search;
  }

  /** 待选物品子菜单的工作状态（显式保存/取消） */
  // ──────────────────────────────────────────────
  // 主编辑界面
  // ──────────────────────────────────────────────

  /**
   * 打开主编辑界面
   *
   * @param player    编辑者
   * @param profileId 容器 UUID
   * @param block     被绑定的方块（预览需要它的坐标）
   */
  public void openEditor(Player player, UUID profileId, Location block) {
    // 界面本体在 WardEditorPage 里；这里只负责把页面交给仲裁器
    PageArbiter.get().open(player, new WardEditorPage(this, store, profileId, block),
        PagePriorityEnum.REPLACE);
  }

  /**
   * 让某个容器已生成的内容作废
   * <p>
   * 改了待选物品就必须调用：否则下次打开还是旧结果（会话默认空闲一小时才回收）。
   */
  void invalidate(UUID profileId) {
    module.invalidate(profileId);
  }

  /** 主界面「最大抽出数量」那段 Lore（页面与命令共用） */
  static List<String> maxDrawLore(WardProfile profile) {
    List<String> lore = new ArrayList<>();
    for (WardRarity r : WardRarity.list()) {
      lore.add(r.color() + r.displayName() + " §7→ §f" + profile.minDraw(r) + " §8~ §f"
          + profile.maxDraw(r));
    }
    lore.add(i18n.tr("jwardchest.maxdraw.total", profile.totalMinDraw(), profile.totalMaxDraw()));
    lore.add(i18n.tr("jwardchest.maxdraw.dist", profile.totalMinDraw(), profile.totalMaxDraw(),
        profile.countDistribution().id()));
    lore.addAll(overflowHints(profile));
    lore.add(i18n.tr("jwardchest.editor.click"));
    return lore;
  }

  /** 上/下限与容器容量不匹配时的提示（多余会被舍弃 / 未设上限时按容量抽） */
  private static List<String> overflowHints(WardProfile profile) {
    int lower = profile.totalMinDraw();
    int upper = profile.totalMaxDraw();
    int slots = profile.slots();
    if (upper <= 0) {
      return List.of(lower > 0
          ? i18n.tr("jwardchest.maxdraw.auto_floor", lower, slots)
          : i18n.tr("jwardchest.maxdraw.auto", slots));
    }
    if (upper > slots) {
      return List.of(i18n.tr("jwardchest.maxdraw.overflow", upper, slots));
    }
    return List.of();
  }

  // ──────────────────────────────────────────────
  // 容器类型子菜单
  // ──────────────────────────────────────────────

  /** 箱子行数选择 */
  // ──────────────────────────────────────────────
  // 各品质最大抽出数量
  // ──────────────────────────────────────────────

  /**
   * 各品质的抽出数量上/下限
   * <p>
   * 两行：上排「最小」（地毯）＝保底，下排「最大」（羊毛）＝上限。
   * 上限只是上限——设 3 不等于一定抽 3 条，实际条数由权重分摊与总条数决定。
   */
  /**
   * 上/下限的编辑按钮
   *
   * @param floorRow true = 上排（最小 / 保底，用地毯区分），false = 下排（最大 / 上限，用羊毛）
   */
  static ItemStack drawButton(WardProfile profile, WardRarity r, boolean floorRow) {
    List<String> lore = new ArrayList<>();
    lore.add(i18n.tr("jwardchest.maxdraw.range", profile.minDraw(r), profile.maxDraw(r)));
    lore.add(i18n.tr(floorRow
        ? "jwardchest.maxdraw.row_floor"
        : "jwardchest.maxdraw.row_ceiling"));
    lore.add(i18n.tr("jwardchest.maxdraw.total", profile.totalMinDraw(), profile.totalMaxDraw()));
    lore.add(i18n.tr("jwardchest.maxdraw.dist", profile.totalMinDraw(), profile.totalMaxDraw(),
        profile.countDistribution().id()));
    lore.addAll(overflowHints(profile));
    lore.add(i18n.tr("jwardchest.maxdraw.hint"));
    return button(floorRow ? r.carpet() : r.wool(),
        i18n.tr(floorRow ? "jwardchest.maxdraw.label_floor" : "jwardchest.maxdraw.label_ceiling",
            r.color() + r.displayName()),
        lore);
  }

  /** 每 9 档占一行，上下两排（最小 / 最大）共 {@code 2 × 行数} 行 */
  static int maxDrawRows() {
    return Math.max(1, (WardRarity.size() + 8) / 9);
  }

  /**
   * 上/下限界面里某档的槽位
   * <p>
   * 上排（最小 / 保底）在前，下排（最大 / 上限）紧随其后，各占 {@code maxDrawRows()} 行 × 9 格，
   * 所以档位数量变化时布局自己会跟着长。
   *
   * @param index    档位下标（声明顺序）
   * @param ceiling  true = 下排（上限）
   */
  static int maxDrawSlot(int index, boolean ceiling) {
    int rows = maxDrawRows();
    int row = index / 9;
    int col = index % 9;
    return ((ceiling ? rows : 0) + row) * 9 + col;
  }

  // ──────────────────────────────────────────────
  // 数量分布子菜单
  // ──────────────────────────────────────────────

  /**
   * 数量分布子界面
   * <p>
   * 左端两格管「结果组总条数」，右端两格管「单条结果的物品数量」；
   * 每对分别是「分布」与「曲线指数」。分布与曲线都属于<b>容器设置</b>，随容器/预设组落盘。
   */
  static ItemStack distributionButton(WardProfile profile, boolean forCount) {
    WardDistribution distribution =
        forCount ? profile.countDistribution() : profile.amountDistribution();
    double curve = forCount ? profile.countCurve() : profile.amountCurve();
    List<String> lore = new ArrayList<>();
    lore.add(i18n.tr("jwardchest.dist.shape", shapePreview(distribution, curve)));
    lore.add(i18n.tr("jwardchest.dist.modes"));
    lore.add(i18n.tr("jwardchest.dist.help_dist"));
    return button(Material.COMPARATOR,
        i18n.tr(forCount ? "jwardchest.dist.count_label" : "jwardchest.dist.amount_label",
            distribution.id()),
        lore);
  }

  static ItemStack curveButton(WardProfile profile, boolean forCount) {
    WardDistribution distribution =
        forCount ? profile.countDistribution() : profile.amountDistribution();
    double curve = forCount ? profile.countCurve() : profile.amountCurve();
    List<String> lore = new ArrayList<>();
    lore.add(i18n.tr("jwardchest.dist.shape", shapePreview(distribution, curve)));
    lore.add(distribution == WardDistribution.UNIFORM
        ? i18n.tr("jwardchest.dist.curve_unused", distribution.id())
        : i18n.tr("jwardchest.dist.help_curve"));
    return button(Material.LEVER,
        i18n.tr(forCount ? "jwardchest.dist.count_curve" : "jwardchest.dist.amount_curve",
            formatCurve(curve)),
        lore);
  }

  /** 形状预览用的字符（由低到高 8 档） */
  private static final String BARS = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588";

  /**
   * 画一个 9 段的形状预览
   * <p>
   * 直接<b>用同一个 {@code pick()} 采样</b>再统计，所以预览与真实抽取行为必然一致
   * （固定种子，同一配置每次看到的形状相同）。
   */
  private static String shapePreview(WardDistribution distribution, double curve) {
    int buckets = 9;
    int[] hits = new int[buckets];
    java.util.Random random = new java.util.Random(20261015L);
    int samples = 3000;
    for (int i = 0; i < samples; i++) {
      hits[distribution.pick(random, 0, buckets - 1, curve)]++;
    }
    int max = 1;
    for (int hit : hits) {
      max = Math.max(max, hit);
    }
    StringBuilder sb = new StringBuilder(buckets);
    for (int hit : hits) {
      int level = (int) Math.round((double) hit / max * (BARS.length() - 1));
      sb.append(BARS.charAt(Math.max(0, Math.min(BARS.length() - 1, level))));
    }
    return sb.toString();
  }

  static String formatCurve(double curve) {
    return String.format(java.util.Locale.ROOT, "%.1f", curve);
  }

  /** 写入总条数 / 物品数量那一条的曲线指数 */
  static void applyCurve(WardProfile profile, boolean forCount, double curve) {
    if (forCount) {
      profile.countCurve(curve);
    } else {
      profile.amountCurve(curve);
    }
  }

  // ──────────────────────────────────────────────
  // 预览
  // ──────────────────────────────────────────────

  /**
   * 预览
   *
   * @param instant Shift 点击时为 true：跳过粗筛与搜索动画，直接摆出全部结果
   */
  void openPreview(Player player, UUID profileId, Location block, boolean instant) {
    WardProfile profile = store.effective(profileId);
    Location loc = block == null ? player.getLocation() : block;
    WardSession session = sessions.getOrCreate(
        WardSessionManager.previewKey(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            player.getUniqueId()),
        profileId, loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
        profile.slots(), true);
    search.open(player, profile, session, !instant);
  }

  // ──────────────────────────────────────────────
  // 点击处理
  // ──────────────────────────────────────────────

  /** 写入上排（保底）或下排（上限）的值；两者的联动由 {@link WardProfile} 内部保证 */
  static void applyDraw(WardProfile profile, WardRarity rarity, boolean floorRow, int value) {
    if (floorRow) {
      profile.minDraw(rarity, value);
    } else {
      profile.maxDraw(rarity, value);
    }
  }

  /**
   * 定位点击的槽位属于哪个块的第几个部件
   *
   * @return {页内块序号 0-9, 部件}，部件 0=物品格 1=概率格 2=珍惜度格；不在任何块内返回 null
   */
  @Nullable
  // ──────────────────────────────────────────────
  // 工具
  // ─────────────────────────────────────────────

  /** 构造一个按钮物品 */
  private static ItemStack button(Material material, String name, List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(WardGui.legacy(name));
      if (lore != null && !lore.isEmpty()) {
        meta.lore(lore.stream().map(WardGui::legacy).toList());
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  static int parseIntOrDefault(String value, int def) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      return def;
    }
  }
}
