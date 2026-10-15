package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.jlogger;

/**
 * 珍惜度（<b>声明式</b>）
 * <p>
 * 整套珍惜度不是在代码里写死的枚举，而是由配置 {@code JwardChest.rarities} <b>声明</b>出来：
 * 一个条目就是一份完整的声明——显示名、文字颜色、紧急着色用的玻璃板、编辑器用到的两种材质、
 * 搜索基准时长、完成音效及其音量音高。于是代码里不再有「按珍惜度分支」的 switch，
 * 也不会出现「名字写在一处、材质在另一处、音效又在第三个平行 map 里」。
 * <p>
 * <b>声明顺序即强度顺序</b>：越靠后越珍贵。{@link #index()} 就是声明次序，
 * {@link #highest()} 是最后一档，{@link #next()} / {@link #previous()} 按声明顺序环绕。
 * <p>
 * 条目的键（{@code id}）是<b>稳定标识</b>：容器的各档抽出数量、每条待选物品的珍惜度都按 id 落盘，
 * 所以改 id 等于换了一档（已有设置里那一档的值会读不回来，回落为默认）。
 * <p>
 * 声明缺失、或一条都解析不出来时，会回落到 {@link #defaults()} 那份内置默认并记一条警告——
 * 免得配置写坏以后整条抽取流程因为「一档都没有」而崩掉。配置里写了一条就完全以配置为准。
 * <p>
 * 相等性按 {@code id} 判定（而非实例），这样热重载后产生的新实例仍能命中容器设置里存下的旧键。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardRarity implements Comparable<WardRarity> {

  /** 声明所在的配置段（相对 {@code Joyous.conf} 的根） */
  private static final String SECTION = "JwardChest.rarities";

  /** 一个声明条目可写的字段名 */
  private static final String[] SPEC_KEYS = {
      "name", "color", "glass", "wool", "carpet", "seconds", "sound", "volume", "pitch"
  };

  /**
   * 内置默认声明
   * <p>
   * 与 {@code config.yml} 里的 {@code JwardChest.rarities} 内容一致，仅在配置缺失/写坏时兜底。
   * 每行按 {@link #SPEC_KEYS} 的顺序给出（第一列是 id）。
   */
  private static final String[][] DEFAULTS = {
      { "common", "普通", "§f", "WHITE_STAINED_GLASS_PANE", "WHITE_WOOL", "WHITE_CARPET", "1.3",
          "entity.experience_orb.pickup", "0.5", "1.0" },
      { "rare", "稀有", "§a", "LIME_STAINED_GLASS_PANE", "GREEN_WOOL", "GREEN_CARPET", "1.9",
          "entity.experience_orb.pickup", "0.8", "1.2" },
      { "blue", "精良", "§9", "BLUE_STAINED_GLASS_PANE", "BLUE_WOOL", "BLUE_CARPET", "2.3",
          "entity.experience_orb.pickup", "0.95", "1.3" },
      { "collect", "典藏", "§d", "PURPLE_STAINED_GLASS_PANE", "PURPLE_WOOL", "PURPLE_CARPET", "2.8",
          "entity.player.levelup", "1.1", "1.4" },
      { "precious", "珍贵", "§6", "ORANGE_STAINED_GLASS_PANE", "YELLOW_WOOL", "YELLOW_CARPET", "4.6",
          "entity.player.levelup", "1.4", "1.6" },
      { "mythic", "绝世", "§c", "RED_STAINED_GLASS_PANE", "RED_WOOL", "RED_CARPET", "6.0",
          "ui.toast.challenge_complete", "1.7", "1.8" },
  };

  /** 当前生效的声明，按声明顺序 */
  private static volatile List<WardRarity> order = List.of();
  /** id → 声明 */
  private static volatile Map<String, WardRarity> byId = Map.of();

  private final int index;
  private final String id;
  private final String displayName;
  private final String color;
  private final Material glass;
  private final Material wool;
  private final Material carpet;
  private final double baseSeconds;
  private final String sound;
  private final float volume;
  private final float pitch;

  private WardRarity(int index, String id, String displayName, String color, Material glass,
      Material wool, Material carpet, double baseSeconds, String sound, float volume, float pitch) {
    this.index = index;
    this.id = id;
    this.displayName = displayName;
    this.color = color;
    this.glass = glass;
    this.wool = wool;
    this.carpet = carpet;
    this.baseSeconds = baseSeconds;
    this.sound = sound;
    this.volume = volume;
    this.pitch = pitch;
  }

  // ──────────────────────────────────────────────
  // 声明
  // ──────────────────────────────────────────────

  /** 声明次序（0 起），也就是强度序：越大越珍贵 */
  public int index() {
    return index;
  }

  /** 稳定标识，用于配置键与持久化 */
  public String id() {
    return id;
  }

  /** 显示名 */
  public String displayName() {
    return displayName;
  }

  /** 原版遗留颜色码（形如 {@code §a}） */
  public String color() {
    return color;
  }

  /** 进入「紧急着色窗口」后「搜索中」占位用的玻璃板 */
  public Material glass() {
    return glass;
  }

  /** 编辑界面「最大抽出数量」按钮的材质 */
  public Material wool() {
    return wool;
  }

  /** 编辑界面「最小抽出数量」按钮的材质（地毯，与羊毛区分开） */
  public Material carpet() {
    return carpet;
  }

  /** 本档的搜索基准时长（秒） */
  public double baseSeconds() {
    return baseSeconds;
  }

  /** 本档的搜索完成音效键 */
  public String sound() {
    return sound;
  }

  /** 音效音量（大于 1 会扩大可听范围） */
  public float volume() {
    return volume;
  }

  /** 音效音高 */
  public float pitch() {
    return pitch;
  }

  // ──────────────────────────────────────────────
  // 行为
  // ──────────────────────────────────────────────

  /**
   * 单个结果的搜索时长（秒）
   * <p>
   * {@code 时长 = 本档声明的 seconds + 物品数量 × 全局 amount-factor}，后者影响很小。
   *
   * @param amount 该结果的物品数量
   * @return 搜索秒数，至少为 0
   */
  public double searchSeconds(int amount) {
    double per = JwardChestMain.CONF.searchAmountFactor();
    return Math.max(0.0, baseSeconds + Math.max(0, amount) * per);
  }

  /** 按声明顺序取下一档（最后一档回到第一档） */
  public WardRarity next() {
    List<WardRarity> all = list();
    return all.get((index + 1) % all.size());
  }

  /** 按声明顺序取上一档（第一档回到最后一档） */
  public WardRarity previous() {
    List<WardRarity> all = list();
    return all.get(Math.floorMod(index - 1, all.size()));
  }

  /** 播放「搜索完成」音效（音效、音量、音高都来自本档的声明） */
  public void playCompletion(Player player) {
    if (player == null || !player.isOnline()) {
      return;
    }
    Sound resolved = resolveSound(sound);
    if (resolved == null) {
      return;
    }
    player.playSound(player.getLocation(), resolved, Math.max(0f, volume), Math.max(0f, pitch));
  }

  /** 按原版命名空间解析音效；无法解析时返回 null 并记一条调试日志 */
  private static Sound resolveSound(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    NamespacedKey nk = normalized.contains(":")
        ? NamespacedKey.fromString(normalized)
        : NamespacedKey.minecraft(normalized);
    if (nk == null) {
      return null;
    }
    try {
      return Registry.SOUNDS.get(nk);
    } catch (Exception e) {
      jlogger.debug("无法解析音效 [%s]：%s", key, e.getLocalizedMessage());
      return null;
    }
  }

  // ──────────────────────────────────────────────
  // 注册表
  // ──────────────────────────────────────────────

  /**
   * 重新读取声明
   * <p>
   * 模块启用时调用一次；{@code /jc reload} 也会调（配置热重载改了声明后需要它来重建实例）。
   * 读不出任何有效条目时回落到内置默认。
   */
  public static synchronized void reload() {
    Map<String, Object> section = Joyous.conf == null
        ? Collections.emptyMap()
        : Joyous.conf.getSection(SECTION, Collections.emptyMap());

    List<WardRarity> built = new ArrayList<>();
    Map<String, WardRarity> index = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : section.entrySet()) {
      String id = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
      if (id.isEmpty() || !(entry.getValue() instanceof Map)) {
        jlogger.warn("JwardChest 珍惜度声明 [%s] 不是一个字段组，已跳过", entry.getKey());
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) entry.getValue();
      if (index.containsKey(id)) {
        jlogger.warn("JwardChest 珍惜度声明 [%s] 重复，已忽略后一个", id);
        continue;
      }
      built.add(parse(built.size(), id, spec));
      index.put(id, built.get(built.size() - 1));
    }

    if (built.isEmpty()) {
      jlogger.warn("JwardChest 配置里没有可用的珍惜度声明（%s），本次使用内置默认 %s 档",
          SECTION, DEFAULTS.length);
      for (String[] row : DEFAULTS) {
        Map<String, Object> spec = new LinkedHashMap<>();
        for (int i = 0; i < SPEC_KEYS.length; i++) {
          spec.put(SPEC_KEYS[i], row[i + 1]);
        }
        built.add(parse(built.size(), row[0], spec));
        index.put(row[0], built.get(built.size() - 1));
      }
    }

    order = List.copyOf(built);
    byId = Collections.unmodifiableMap(new LinkedHashMap<>(index));
  }

  /** 按声明解析一条 */
  private static WardRarity parse(int index, String id, Map<String, Object> spec) {
    return new WardRarity(
        index,
        id,
        str(spec, "name", id),
        str(spec, "color", "§f"),
        material(spec, "glass", Material.WHITE_STAINED_GLASS_PANE),
        material(spec, "wool", Material.WHITE_WOOL),
        material(spec, "carpet", Material.WHITE_CARPET),
        num(spec, "seconds", 2.0),
        str(spec, "sound", "entity.experience_orb.pickup"),
        (float) num(spec, "volume", 1.0),
        (float) num(spec, "pitch", 1.0));
  }

  /** 全部声明（按声明顺序）；尚未加载时先加载一次 */
  public static List<WardRarity> list() {
    List<WardRarity> snapshot = order;
    if (snapshot.isEmpty()) {
      reload();
      snapshot = order;
    }
    return snapshot;
  }

  /** 声明数量 */
  public static int size() {
    return list().size();
  }

  /**
   * 按下标取声明
   *
   * @return 越界返回 null
   */
  @Nullable
  public static WardRarity byIndex(int index) {
    List<WardRarity> all = list();
    return (index >= 0 && index < all.size()) ? all.get(index) : null;
  }

  /** 按 id 取声明（大小写不敏感） */
  @Nullable
  public static WardRarity find(@Nullable String id) {
    return id == null ? null : byId.get(id.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * 按 id 取声明，认不出来就取<b>第一档</b>
   * <p>
   * 用于反序列化：某个 id 的声明被移除后，旧数据仍然能读成一个合理的默认档，
   * 而不是让整条物品条目解析失败。
   */
  public static WardRarity byId(@Nullable String id) {
    WardRarity found = find(id);
    if (found != null) {
      return found;
    }
    if (id != null && !id.isBlank()) {
      jlogger.debug("JwardChest 无法识别的珍惜度 [%s]，已按 %s 处理", id, first().id());
    }
    return first();
  }

  /** 第一档（最普通的一档） */
  public static WardRarity first() {
    return list().get(0);
  }

  /** 最后一档（最珍贵的一档） */
  public static WardRarity highest() {
    List<WardRarity> all = list();
    return all.get(all.size() - 1);
  }

  /** 某声明在序列里的下标；不属于当前声明时返回 -1 */
  public static int indexOf(@Nullable WardRarity rarity) {
    WardRarity found = rarity == null ? null : find(rarity.id());
    return found == null ? -1 : found.index;
  }

  /** 全部 id，逗号分隔（用于命令提示） */
  public static String ids() {
    StringBuilder sb = new StringBuilder();
    for (WardRarity r : list()) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(r.id);
    }
    return sb.toString();
  }

  /** 内置默认声明（仅供兜底使用，正常路径以配置为准） */
  static List<WardRarity> defaults() {
    List<WardRarity> built = new ArrayList<>();
    for (String[] row : DEFAULTS) {
      Map<String, Object> spec = new LinkedHashMap<>();
      for (int i = 0; i < SPEC_KEYS.length; i++) {
        spec.put(SPEC_KEYS[i], row[i + 1]);
      }
      built.add(parse(built.size(), row[0], spec));
    }
    return built;
  }

  // ──────────────────────────────────────────────
  // 取值工具
  // ──────────────────────────────────────────────

  /** 取字符串字段（容忍 YAML 的任意标量类型） */
  private static String str(Map<String, Object> spec, String key, String def) {
    Object v = spec.get(key);
    return v == null ? def : String.valueOf(v).trim();
  }

  /** 取浮点字段 */
  private static double num(Map<String, Object> spec, String key, double def) {
    Object v = spec.get(key);
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    if (v instanceof String s) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException ignored) {
        // 落到默认值
      }
    }
    return def;
  }

  /** 取材质字段：名字认不出来时用兜底材质并记一条警告 */
  private static Material material(Map<String, Object> spec, String key, Material def) {
    String raw = str(spec, key, null);
    if (raw == null || raw.isBlank()) {
      return def;
    }
    Material found = Material.matchMaterial(raw);
    if (found == null) {
      jlogger.warn("JwardChest 珍惜度声明的 %s=[%s] 不是有效材质，已用 %s 代替", key, raw, def);
      return def;
    }
    return found;
  }

  // ──────────────────────────────────────────────
  // 值语义
  // ──────────────────────────────────────────────

  /** 按声明顺序比较（用于「结果组里最高的珍惜度」这类判断） */
  @Override
  public int compareTo(WardRarity other) {
    if (other == null) {
      return 1;
    }
    return Integer.compare(index, other.index);
  }

  /** 相等性按 id 判定，这样热重载后的新实例仍与旧实例（容器设置里存下的键）相等 */
  @Override
  public boolean equals(Object obj) {
    return obj instanceof WardRarity other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    return id;
  }
}
