package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

  private final UUID id;
  private String presetCode;
  private WardContainerType container = WardContainerType.CHEST;
  private int rows = 6;
  private String title = "&b战利品箱";
  private int prefilterSeconds = 0;
  private final Map<WardRarity, Integer> maxDraw = new EnumMap<>(WardRarity.class);
  private final List<WardItemEntry> entries = new ArrayList<>();

  public WardProfile(UUID id) {
    this.id = id;
    for (WardRarity r : WardRarity.values()) {
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

  public int maxDraw(WardRarity rarity) {
    return maxDraw.getOrDefault(rarity, 0);
  }

  public void maxDraw(WardRarity rarity, int value) {
    maxDraw.put(rarity, Math.max(0, value));
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

  /** 结果组总数量 = 各珍惜度最大抽出数量之和；为 0 时不产出任何结果 */
  public int totalDrawCount() {
    int sum = 0;
    for (WardRarity r : WardRarity.values()) {
      sum += maxDraw(r);
    }
    return sum;
  }

  /** 条目数（编辑界面分页用） */
  public int entryCount() {
    return entries.size();
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
    this.maxDraw.clear();
    this.maxDraw.putAll(other.maxDraw);
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

    Object drawObj = section.get("max-draw");
    if (drawObj instanceof Map<?, ?> draw) {
      for (WardRarity r : WardRarity.values()) {
        p.maxDraw(r, intOf(draw.get(r.id()), 0));
      }
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
