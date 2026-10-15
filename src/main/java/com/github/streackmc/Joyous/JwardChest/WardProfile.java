package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 一个「自定义容器」的完整设置
 * <p>
 * 与 {@link WardProfileStore} 里的预设组并行：容器自身的 {@link #id()} 是唯一键，
 * 而 {@link #presetCode()} 指向一个可共享的预设组。设置为此二者互为覆盖关系——
 * 预设代号不为空时，生效设置来自预设组（编辑也写回预设组，从而联动同组全部容器）。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardProfile {

  /** 预设代号「未设置」的默认值 */
  public static final String UNSET_CODE = "<unset>";
  /** 曲线指数的默认值（1.0 = 最平缓） */
  public static final double DEFAULT_CURVE = 2.0;
  /** 曲线指数允许范围 */
  public static final double MIN_CURVE = 1.0;
  public static final double MAX_CURVE = 10.0;

  private final UUID id;
  private String presetCode;
  private WardContainerType container = WardContainerType.CHEST;
  private int rows = 6;
  private String title = "&b战利品箱";
  private int prefilterSeconds = 0;
  private final Map<WardRarity, Integer> minDraw = new EnumMap<>(WardRarity.class);
  private final Map<WardRarity, Integer> maxDraw = new EnumMap<>(WardRarity.class);
  /** 结果组总条数的取值分布（属于容器设置，不再走全局 config） */
  private WardDistribution countDistribution = WardDistribution.UNIFORM;
  private double countCurve = DEFAULT_CURVE;
  /** 单条结果里物品数量的取值分布 */
  private WardDistribution amountDistribution = WardDistribution.UNIFORM;
  private double amountCurve = DEFAULT_CURVE;
  private final List<WardItemEntry> entries = new ArrayList<>();

  public WardProfile(UUID id) {
    this.id = id;
    for (WardRarity r : WardRarity.values()) {
      minDraw.put(r, 0);
      maxDraw.put(r, 0);
    }
  }

  // ──────────────────────────────────────────────
  // 访问器
  // ──────────────────────────────────────────────

  public UUID id() {
    return id;
  }

  /** 预设代号；{@link #UNSET_CODE} 或 null 表示未加入任何预设组 */
  public String presetCode() {
    return presetCode;
  }

  public void presetCode(String presetCode) {
    this.presetCode = (presetCode == null || presetCode.isBlank()) ? null : presetCode;
  }

  /** 是否属于某个预设组 */
  public boolean inPreset() {
    return presetCode != null && !presetCode.isBlank();
  }

  public WardContainerType container() {
    return container;
  }

  public void container(WardContainerType container) {
    this.container = container == null ? WardContainerType.CHEST : container;
  }

  public int rows() {
    return WardContainerType.clampRows(rows);
  }

  public void rows(int rows) {
    this.rows = WardContainerType.clampRows(rows);
  }

  /** 容器槽位数（含标题栏之外的可用槽） */
  public int slots() {
    return container.slots(rows);
  }

  public String title() {
    return title;
  }

  public void title(String title) {
    this.title = title == null ? "" : title;
  }

  /** 粗筛时长（秒），0 表示不启用 */
  public int prefilterSeconds() {
    return prefilterSeconds;
  }

  public void prefilterSeconds(int seconds) {
    this.prefilterSeconds = Math.max(0, seconds);
  }

  public boolean prefilterEnabled() {
    return prefilterSeconds > 0;
  }

  /**
   * 该珍惜度的<b>最小</b>抽出数量（保底），0 表示不设保底
   */
  public int minDraw(WardRarity rarity) {
    return minDraw.getOrDefault(rarity, 0);
  }

  /**
   * 设置最小抽出数量
   * <p>
   * 保底不能超过上限——若设得比上限还大，<b>上限同步抬上来</b>（保留操作者刚输入的值），
   * 与 {@link WardItemEntry#setMin(int)} 的联动规则一致。
   */
  public void minDraw(WardRarity rarity, int value) {
    int v = Math.max(0, value);
    if (v > maxDraw(rarity)) {
      maxDraw.put(rarity, v);
    }
    minDraw.put(rarity, v);
  }

  /**
   * 该珍惜度的<b>最大</b>抽出数量（上限），0 表示不设上限
   */
  public int maxDraw(WardRarity rarity) {
    return maxDraw.getOrDefault(rarity, 0);
  }

  /**
   * 设置最大抽出数量
   * <p>
   * 上限不能低于保底——若设得比保底还小，<b>保底同步降下来</b>（与
   * {@link #minDraw(WardRarity, int)} 对称）。注意这只是一个<b>上限</b>：
   * 「设 3」不等于「一定抽 3 条」，实际条数还受权重分摊与总条数影响。
   */
  public void maxDraw(WardRarity rarity, int value) {
    int v = Math.max(0, value);
    if (v < minDraw(rarity)) {
      minDraw.put(rarity, v);
    }
    maxDraw.put(rarity, v);
  }

  /** 各珍惜度最小抽出数量之和（保底总条数） */
  public int totalMinDraw() {
    int sum = 0;
    for (WardRarity r : WardRarity.values()) {
      sum += minDraw(r);
    }
    return sum;
  }

  /** 各珍惜度最大抽出数量之和（结果组总条数的上限） */
  public int totalMaxDraw() {
    int sum = 0;
    for (WardRarity r : WardRarity.values()) {
      sum += maxDraw(r);
    }
    return sum;
  }

  public List<WardItemEntry> entries() {
    return entries;
  }

  /** 全部条目权重之和（不含权重为 0 的条目） */
  public int totalWeight() {
    int sum = 0;
    for (WardItemEntry e : entries) {
      sum += Math.max(0, e.weight());
    }
    return sum;
  }

  /**
   * 结果组总条数 = 各珍惜度最大抽出数量之和
   * <p>
   * 为 0 且 {@link #totalMinDraw()} 也为 0 时表示「不限量」，按容器容量抽满。
   *
   * @see #totalMaxDraw()
   */
  public int totalDrawCount() {
    return totalMaxDraw();
  }

  /** 条目数（编辑界面分页用） */
  public int entryCount() {
    return entries.size();
  }

  // ──────────────────────────────────────────────
  // 数量分布（容器设置的一部分）
  // ──────────────────────────────────────────────

  /** 结果组总条数的取值分布 */
  public WardDistribution countDistribution() {
    return countDistribution;
  }

  public void countDistribution(WardDistribution distribution) {
    this.countDistribution = distribution == null ? WardDistribution.UNIFORM : distribution;
  }

  /** 总条数分布的曲线指数 */
  public double countCurve() {
    return countCurve;
  }

  public void countCurve(double curve) {
    this.countCurve = clampCurve(curve);
  }

  /** 单条结果里物品数量的取值分布 */
  public WardDistribution amountDistribution() {
    return amountDistribution;
  }

  public void amountDistribution(WardDistribution distribution) {
    this.amountDistribution = distribution == null ? WardDistribution.UNIFORM : distribution;
  }

  /** 物品数量分布的曲线指数 */
  public double amountCurve() {
    return amountCurve;
  }

  public void amountCurve(double curve) {
    this.amountCurve = clampCurve(curve);
  }

  /** 曲线指数夹取到 [{@value #MIN_CURVE}, {@value #MAX_CURVE}] */
  public static double clampCurve(double curve) {
    if (Double.isNaN(curve)) {
      return DEFAULT_CURVE;
    }
    return Math.max(MIN_CURVE, Math.min(MAX_CURVE, curve));
  }

  @Override
  public String toString() {
    return "WardProfile[" + id + " preset=" + presetCode + " " + container.id() + " rows=" + rows
        + " entries=" + entries.size() + " prefilter=" + prefilterSeconds + "]";
  }

  // ──────────────────────────────────────────────
  // 覆盖：把另一份设置整体拷进来（保留 id）
  // ──────────────────────────────────────────────

  /** 用另一份设置覆盖本实例的全部字段（{@link #id()} 不变） */
  public void copySettingsFrom(WardProfile other) {
    this.presetCode = other.presetCode;
    this.container = other.container;
    this.rows = other.rows;
    this.title = other.title;
    this.prefilterSeconds = other.prefilterSeconds;
    this.minDraw.clear();
    this.minDraw.putAll(other.minDraw);
    this.maxDraw.clear();
    this.maxDraw.putAll(other.maxDraw);
    this.countDistribution = other.countDistribution;
    this.countCurve = other.countCurve;
    this.amountDistribution = other.amountDistribution;
    this.amountCurve = other.amountCurve;
    this.entries.clear();
    this.entries.addAll(other.entries);
  }

  // ──────────────────────────────────────────────
  // 持久化
  // ──────────────────────────────────────────────

  /**
   * 写入 SConfig 的指定路径下
   * <p>
   * <b>只做「两段路径 + 一张扁平 map」</b>，不要把字段直接拼成三段以上的路径。
   * 原因：SConfig 的 {@code putNested} 用<b>第一个</b>未转义点来推算叶子键名
   * （`lastKey = key.substring(firstDot + 1)`），而读取是按**所有**分段逐层下探的——
   * 于是长度 ≥3 的路径会把键名写坏（写成 "{@code <头段剩部分>.<叶>}"），
   * 之后再也读不回来，表现为配置整段静默丢失。这里自己组装好一层结构再整体 putSection，
   * 只用到两段路径，绕开该不对称。
   *
   * @param conf 目标配置
   * @param path 两段路径，如 {@code profiles.<uuid>}
   */
  public void writeTo(SConfig conf, String path) {
    Map<String, Object> section = new LinkedHashMap<>();
    section.put("preset-code", presetCode == null ? UNSET_CODE : presetCode);
    section.put("container", container.id());
    section.put("rows", rows());
    section.put("title", title);
    section.put("prefilter-seconds", prefilterSeconds);

    Map<String, Object> floor = new LinkedHashMap<>();
    for (WardRarity r : WardRarity.values()) {
      floor.put(r.id(), minDraw(r));
    }
    section.put("min-draw", floor);

    Map<String, Object> draw = new LinkedHashMap<>();
    for (WardRarity r : WardRarity.values()) {
      draw.put(r.id(), maxDraw(r));
    }
    section.put("max-draw", draw);

    List<String> raw = new ArrayList<>(entries.size());
    for (WardItemEntry e : entries) {
      raw.add(e.serialize());
    }
    section.put("entries", raw);

    Map<String, Object> dist = new LinkedHashMap<>();
    dist.put("count-distribution", countDistribution.id());
    dist.put("count-curve", countCurve());
    dist.put("amount-distribution", amountDistribution.id());
    dist.put("amount-curve", amountCurve());
    section.put("draw", dist);

    conf.putSection(path, section);
  }

  /**
   * 从 SConfig 的指定路径下读取（同样只用两段路径，取到 map 后按扁平键取值）
   *
   * @param conf 数据源
   * @param path 两段路径，如 {@code profiles.<uuid>}
   * @param id   容器 UUID
   * @return 设置实例（路径不存在时返回全默认的实例）
   */
  public static WardProfile readFrom(SConfig conf, String path, UUID id) {
    WardProfile p = new WardProfile(id);
    Map<String, Object> section = conf.getSection(path, null);
    if (section == null) {
      return p;
    }

    String code = str(section, "preset-code", UNSET_CODE);
    p.presetCode(UNSET_CODE.equalsIgnoreCase(code) ? null : code);

    p.container(WardContainerType.byId(str(section, "container", WardContainerType.CHEST.id())));
    p.rows(intOf(section.get("rows"), 6));
    p.title(str(section, "title", p.title));
    p.prefilterSeconds(intOf(section.get("prefilter-seconds"), 0));

    // 先读保底再读上限：保底值的设置会把上限一并抬起，随后的上限赋值会把它修正回文件里的值
    Object floorObj = section.get("min-draw");
    if (floorObj instanceof Map<?, ?> floor) {
      for (WardRarity r : WardRarity.values()) {
        p.minDraw(r, intOf(floor.get(r.id()), 0));
      }
    }

    Object capObj = section.get("max-draw");
    if (capObj instanceof Map<?, ?> cap) {
      for (WardRarity r : WardRarity.values()) {
        p.maxDraw(r, intOf(cap.get(r.id()), 0));
      }
    }

    Object distObj = section.get("draw");
    if (distObj instanceof Map<?, ?> dist) {
      p.countDistribution(WardDistribution.byId(raw(dist.get("count-distribution"))));
      p.countCurve(doubleOf(dist.get("count-curve"), DEFAULT_CURVE));
      p.amountDistribution(WardDistribution.byId(raw(dist.get("amount-distribution"))));
      p.amountCurve(doubleOf(dist.get("amount-curve"), DEFAULT_CURVE));
    }

    Object entriesObj = section.get("entries");
    if (entriesObj instanceof List<?> list) {
      for (Object line : list) {
        if (line == null) {
          continue;
        }
        WardItemEntry e = WardItemEntry.deserialize(String.valueOf(line));
        if (e != null) {
          p.entries.add(e);
        }
      }
    }
    return p;
  }

  /** 取字符串字段 */
  private static String str(Map<String, Object> section, String key, String def) {
    Object v = section.get(key);
    return v == null ? def : String.valueOf(v);
  }

  /** 取任意对象的字符串形式（容忍 YAML 的任意标量类型） */
  private static String raw(@Nullable Object v) {
    return v == null ? null : String.valueOf(v);
  }

  /** 取浮点字段：容忍 YAML 给出的 Double / Integer / String */
  private static double doubleOf(@Nullable Object v, double def) {
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    if (v instanceof String str) {
      try {
        return Double.parseDouble(str.trim());
      } catch (NumberFormatException ignored) {
        // 落到默认值
      }
    }
    return def;
  }

  /** 取整数字段：容忍 YAML 给出的 Integer / Double / String */
  private static int intOf(Object v, int def) {
    if (v instanceof Number n) {
      return n.intValue();
    }
    if (v instanceof String str) {
      try {
        return Integer.parseInt(str.trim());
      } catch (NumberFormatException ignored) {
        // 落到默认值
      }
    }
    return def;
  }

  /** 便捷方法：把一组物品包成默认条目 */
  public void addEntry(ItemStack template) {
    entries.add(WardItemEntry.of(template));
  }
}
