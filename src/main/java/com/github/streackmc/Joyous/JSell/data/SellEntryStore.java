package com.github.streackmc.Joyous.JSell.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.utils.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 可售列表的持久化（{@code models/JSell/items.yml}）
 *
 * <h3>文件长什么样</h3>
 * 一条记录一个列表项，字段逐项铺开——<b>可以直接手改</b>，坏了也只影响那一条：
 * <pre>
 * format: 2
 * entries:
 * - id: 8f3e2c1a-...
 *   group: default
 *   item: "AQACAAEAAAAEAAAA"        # 物品模板，Base64（NBT 完整保留）
 *   value: 12.5
 *   limit-count: 64
 *   limit-seconds: 86400
 *   match: strict
 *   durability-loss: true
 *   removed: false
 *   created: 1700000000000
 *   commands:
 *   - "eco give %player% %amount%"
 * </pre>
 *
 * <b>为什么物品模板仍然是 Base64</b>：{@code ItemStack} 的 NBT 只有
 * {@code serializeAsBytes} 这一个稳定、跨版本可读的表示；把它拆成 YAML 字段等于自己维护一份
 * 组件映射表，版本一更新就烂。除了这一项，其余全部是明文。
 *
 * <h3>写配置的约束（StreackLib 当前版本）</h3>
 * {@code putNested} 把<b>第一个点</b>当成「最后一个点」，所以 ≥3 层的写路径会把前缀重复进叶子键。
 * 这里只用两个路径：{@code format}（无点）与 {@code entries}（无点，值是嵌套 Map 列表），
 * 结构由 {@link SConfig#putList(String, List)} 一次写入，不经过拆点逻辑。
 *
 * <h3>旧格式自动迁移</h3>
 * 0.8.x 的格式是「一行一条整条 Base64」的扁平列表（键 {@code items}）。载入时若发现该键，
 * 逐条解码并<b>立即按新格式回写一次</b>，随后删掉旧键——所以升级后第一次启动会重写这个文件。
 *
 * @author Neonai
 * @since 0.9.0
 */
public final class SellEntryStore {

  private static final String FILE_NAME = "items.yml";
  /** 新格式：记录列表的键名 */
  private static final String ROOT = "entries";
  /** 旧格式（0.8.x）：整条 Base64 的扁平列表 */
  private static final String LEGACY_ROOT = "items";
  /** 格式版本；结构变化时递增 */
  private static final int FORMAT_VERSION = 2;
  /** 命令分隔符（NUL 不可能出现在聊天栏输入里） */
  private static final String COMMAND_SEPARATOR = "\u0000";

  private final File file;
  private final SConfig conf;
  /** 条目按登记顺序保存，差异相同的两条保持「先登记先命中」的直觉顺序 */
  private final Map<UUID, SellEntry> entries = new LinkedHashMap<>();
  /** 按材质分组：界面每重绘一次要判定几十个物品，不能每次都扫全表 */
  private final Map<Material, List<SellEntry>> byMaterial = new HashMap<>();
  private boolean indexDirty = true;
  private volatile boolean dirty;

  public SellEntryStore(File dataDir) {
    this.file = new File(new File(dataDir, "models/JSell"), FILE_NAME);
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    // YAMLc：支持块注释，便于在文件里写「这个字段是什么」
    this.conf = new SConfig(file, SConfig.TYPES.YAMLc);
    // 批量写入后手动 save()，避免每次改动都落盘
    this.conf.setWriteMode(SConfig.WRITE_MODE.INERTIA);
    reload();
    if (!file.exists()) {
      writeNow();
    }
  }

  /** 从磁盘重新读取全部可售物品；发现旧格式时顺带迁移 */
  public synchronized void reload() {
    entries.clear();
    try {
      conf.reload();
    } catch (Exception e) {
      jlogger.warn("JSell 无法读取可售列表 %s：%s", file.getPath(), e.getLocalizedMessage());
    }

    int broken = 0;
    boolean legacyFound = false;

    // ① 新格式
    for (Object row : conf.getList(ROOT, List.of())) {
      SellEntry entry = decodeRow(row);
      if (entry == null) {
        broken++;
        continue;
      }
      entries.put(entry.id(), entry);
    }

    // ② 旧格式（一行一条整条 Base64）→ 迁移
    List<String> legacy = conf.getListOfString(LEGACY_ROOT, List.of());
    for (String line : legacy) {
      legacyFound = true;
      SellEntry entry = decodeLegacy(line);
      if (entry == null) {
        broken++;
        continue;
      }
      // 同 id 已经在新区里出现过（重复迁移）时不覆盖
      entries.putIfAbsent(entry.id(), entry);
    }

    if (broken > 0) {
      jlogger.warn("JSell 可售列表里有 %s 条记录无法解析，已跳过（文件：%s）", broken,
          file.getPath());
    }

    indexDirty = true;
    SellGroups.register(groups());

    if (legacyFound) {
      jlogger.info("JSell 检测到旧格式的可售列表（%s 条），正在转换为新格式", entries.size());
      // 迁移是一次性的写动作：即使 dirty 为 false 也要落盘
      dirty = true;
      writeNow();
    } else {
      dirty = false;
    }
    jlogger.debug("JSell 已载入 %s 条可售物品", entries.size());
  }

  public File file() {
    return file;
  }

  // ──────────────────────────────────────────────
  // 读取
  // ──────────────────────────────────────────────

  /** 全部条目（副本，避免调用方改动内部列表） */
  public synchronized List<SellEntry> all() {
    return new ArrayList<>(entries.values());
  }

  public synchronized int size() {
    return entries.size();
  }

  /** 出现过的组名（{@code default} 在最前，其余按字典序） */
  public synchronized List<String> groups() {
    return SellGroups.groupsOf(entries.values());
  }

  @Nullable
  public synchronized SellEntry byId(UUID id) {
    return entries.get(id);
  }

  /** 命中该物品的条目（不校验权限，给管理员与内部逻辑用） */
  @Nullable
  public synchronized SellEntry find(@Nullable ItemStack item) {
    return locate(item, false, null);
  }

  /**
   * 命中该物品且该玩家<b>有权出售</b>的条目
   * <p>
   * 玩家的定价、限购、结算都必须走这个方法：拿不到组权限的物品等价于「不可售」，
   * 而不是「按别的组的价卖给他」。
   */
  @Nullable
  public synchronized SellEntry find(@Nullable Player viewer, @Nullable ItemStack item) {
    return locate(item, false, viewer);
  }

  /**
   * 命中该物品的条目，<b>包含已从价值列表移除的</b>
   * <p>
   * 给「设置单价」这类管理动作使用：管理员对一个已经移除出列表的物品再设一次价，
   * 期望的是「改这条」，而不是又建一条重复的。是否重新上架仍由移除标记决定。
   */
  @Nullable
  public synchronized SellEntry findIncludingRemoved(@Nullable ItemStack item) {
    return locate(item, true, null);
  }

  /**
   * 找出与给定模板「可收购 NBT 完全相同」的条目（含已移除）
   * <p>
   * 用于新增时的去重：管理员把同一件物品登记第二次时，应当打开已有条目，而不是再建一条。
   * 判据是 {@code isSimilar}——比的是可收购的数据，不比堆叠数量。
   */
  @Nullable
  public synchronized SellEntry findSameTemplate(@Nullable ItemStack template) {
    if (template == null || template.getType().isAir()) {
      return null;
    }
    ItemStack probe = template.clone();
    probe.setAmount(1);
    if (indexDirty) {
      rebuildIndex();
    }
    for (SellEntry entry : byMaterial.getOrDefault(probe.getType(), List.of())) {
      if (entry.template().isSimilar(probe)) {
        return entry;
      }
    }
    return null;
  }

  /**
   * 最优匹配
   * <p>
   * 同一材质下可能登记了多条模板，玩家手里那件往往同时命中好几条。选择顺序：
   * <ol>
   *   <li><b>可收购 NBT 完全相同</b>（差异代价 0）——最具体的规则先赢；</li>
   *   <li>否则取<b>差异代价最小</b>的那条（见 {@link SellMatch#difference}）；</li>
   *   <li>代价相同则取<b>先登记</b>的那条，保证结果稳定可预期。</li>
   * </ol>
   * 已从价值列表移除的条目默认跳过（等价于不可售）。
   */
  @Nullable
  private SellEntry locate(@Nullable ItemStack item, boolean includeRemoved,
      @Nullable Player viewer) {
    if (item == null || item.getType().isAir()) {
      return null;
    }
    if (indexDirty) {
      rebuildIndex();
    }
    List<SellEntry> candidates = byMaterial.get(item.getType());
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    SellEntry best = null;
    int bestCost = SellMatch.NO_MATCH;
    long bestCreatedAt = Long.MAX_VALUE;
    for (SellEntry entry : candidates) {
      if (!includeRemoved && entry.removed()) {
        continue;
      }
      if (viewer != null && !SellGroups.canSell(viewer, entry.group())) {
        continue;
      }
      int cost = entry.match().difference(item, entry.template());
      if (cost == SellMatch.NO_MATCH) {
        continue;
      }
      boolean better = cost < bestCost
          || (cost == bestCost && entry.createdAt() < bestCreatedAt);
      if (better) {
        best = entry;
        bestCost = cost;
        bestCreatedAt = entry.createdAt();
      }
    }
    return best;
  }

  /** 重建材质索引（条目增删、重载后调用） */
  private void rebuildIndex() {
    byMaterial.clear();
    for (SellEntry entry : entries.values()) {
      byMaterial.computeIfAbsent(entry.template().getType(), ignored -> new ArrayList<>())
          .add(entry);
    }
    indexDirty = false;
  }

  // ──────────────────────────────────────────────
  // 写入
  // ──────────────────────────────────────────────

  /** 新增或更新一条（只改内存，落盘见 {@link #flush()}） */
  public synchronized void save(SellEntry entry) {
    entries.put(entry.id(), entry);
    indexDirty = true;
    dirty = true;
    SellGroups.register(List.of(entry.group()));
  }

  /** 删除一条（真正的删除；「移除出价值列表」请用 {@link SellEntry#removed(boolean)}） */
  public synchronized void delete(UUID id) {
    if (entries.remove(id) != null) {
      indexDirty = true;
      dirty = true;
    }
  }

  public boolean isDirty() {
    return dirty;
  }

  /** 落盘（编辑界面关闭/保存时统一调用，一次写整份文件） */
  public synchronized void flush() {
    if (dirty) {
      writeNow();
    }
  }

  private void writeNow() {
    try {
      List<Object> rows = new ArrayList<>(entries.size());
      for (SellEntry entry : entries.values()) {
        Map<String, Object> row = encodeRow(entry);
        if (row != null) {
          rows.add(row);
        }
      }
      conf.putInt("format", FORMAT_VERSION);
      conf.putComment("format",
          "格式版本；结构变化时递增。entries 的每一项是 *一条* 可售物品，可以手改");
      conf.putComment(ROOT,
          "item 是物品模板的 Base64（含完整 NBT）；group 决定它在总览里的归类与权限");
      conf.putList(ROOT, rows);
      // 迁移完成后旧键必须消失，否则下次启动会把老数据再合并进来
      conf.remove(LEGACY_ROOT);
      conf.save();
      dirty = false;
    } catch (Exception e) {
      jlogger.warn("JSell 无法保存可售列表 %s：%s", file.getPath(), e.getLocalizedMessage());
    }
  }

  // ──────────────────────────────────────────────
  // 编码：新格式（逐字段）
  // ──────────────────────────────────────────────

  /**
   * 一条记录 → 一个有序 Map
   * <p>
   * 顺序刻意按「人最想先看到什么」排：id / 分组 / 物品 / 价格 …… 命令放最后。
   * 空列表与默认值不写，文件才不会一半是空数组。
   */
  @Nullable
  private static Map<String, Object> encodeRow(SellEntry entry) {
    String item = SellEntry.encodeItem(entry.template());
    if (item == null) {
      return null;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", entry.id().toString());
    row.put("group", entry.group());
    row.put("item", item);
    row.put("value", entry.unitValue());
    row.put("limit-count", entry.limitCount());
    row.put("limit-seconds", entry.limitSeconds());
    row.put("match", entry.match().id());
    if (entry.hasDurability()) {
      // 没有耐久的物品不写这个键，免得管理员以为它有意义
      row.put("durability-loss", entry.durabilityLoss());
    }
    row.put("removed", entry.removed());
    row.put("created", entry.createdAt());
    if (!entry.commands().isEmpty()) {
      row.put("commands", new ArrayList<>(entry.commands()));
    }
    if (!entry.perItemCommands().isEmpty()) {
      row.put("per-item-commands", new ArrayList<>(entry.perItemCommands()));
    }
    return row;
  }

  /** 一个 Map → 一条记录；缺字段用默认值，模板坏了返回 null */
  @Nullable
  private static SellEntry decodeRow(@Nullable Object raw) {
    if (!(raw instanceof Map<?, ?> row)) {
      return null;
    }
    try {
      String rawId = asString(row.get("id"), null);
      if (rawId == null || rawId.isBlank()) {
        // 没有 id 的记录无法稳定引用（界面按 id 定位），宁可跳过也不要每次重载换一个 id
        return null;
      }
      UUID id = UUID.fromString(rawId.trim());
      ItemStack template = SellEntry.decodeItem(asString(row.get("item"), null));
      if (template == null || template.getType().isAir()) {
        return null;
      }
      SellEntry entry = new SellEntry(id, template, asDouble(row.get("value"), 0.0),
          asLong(row.get("created"), 0L));
      entry.group(asString(row.get("group"), SellGroups.DEFAULT));
      entry.limitCount((int) asLong(row.get("limit-count"), 0L));
      entry.limitSeconds(asLong(row.get("limit-seconds"), 0L));
      entry.match(SellMatch.parse(asString(row.get("match"), SellMatch.STRICT.id())));
      entry.durabilityLoss(asBoolean(row.get("durability-loss"), false));
      entry.removed(asBoolean(row.get("removed"), false));
      entry.commands(asStringList(row.get("commands")));
      entry.perItemCommands(asStringList(row.get("per-item-commands")));
      return entry;
    } catch (Exception e) {
      return null;
    }
  }

  private static String asString(@Nullable Object raw, @Nullable String fallback) {
    return raw == null ? fallback : String.valueOf(raw);
  }

  private static double asDouble(@Nullable Object raw, double fallback) {
    if (raw instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return raw == null ? fallback : Double.parseDouble(String.valueOf(raw).trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static long asLong(@Nullable Object raw, long fallback) {
    if (raw instanceof Number number) {
      return number.longValue();
    }
    try {
      return raw == null ? fallback : Long.parseLong(String.valueOf(raw).trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static boolean asBoolean(@Nullable Object raw, boolean fallback) {
    if (raw instanceof Boolean bool) {
      return bool;
    }
    return raw == null ? fallback : Boolean.parseBoolean(String.valueOf(raw).trim());
  }

  private static List<String> asStringList(@Nullable Object raw) {
    List<String> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item != null && !String.valueOf(item).isBlank()) {
          result.add(String.valueOf(item));
        }
      }
    }
    return result;
  }

  // ──────────────────────────────────────────────
  // 编码：旧格式（仅供迁移）
  // ──────────────────────────────────────────────

  /**
   * 旧格式的一条记录：整条 Base64
   * <pre>
   *   byte   版本(1)
   *   UTF    条目 UUID
   *   UTF    物品模板的 Base64
   *   double 单价
   *   int    数量限额
   *   long   时长限额（秒）
   *   UTF    匹配模式
   *   UTF    卖出后命令（NUL 分隔）
   *   UTF    逐件命令（NUL 分隔）
   *   boolean 已移除
   *   long   登记时间
   * </pre>
   */
  @Nullable
  private static SellEntry decodeLegacy(@Nullable String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    try (DataInputStream in = new DataInputStream(
        new ByteArrayInputStream(Base64.getDecoder().decode(line.trim())))) {
      if (in.readByte() != 1) {
        return null;
      }
      UUID id = UUID.fromString(in.readUTF());
      ItemStack template = SellEntry.decodeItem(in.readUTF());
      if (template == null || template.getType().isAir()) {
        return null;
      }
      double value = in.readDouble();
      int limitCount = in.readInt();
      long limitSeconds = in.readLong();
      SellMatch match = SellMatch.parse(in.readUTF());
      List<String> commands = splitLegacy(in.readUTF());
      List<String> perItem = splitLegacy(in.readUTF());
      boolean removed = in.readBoolean();
      long created = in.readLong();

      SellEntry entry = new SellEntry(id, template, value, created);
      entry.limitCount(limitCount);
      entry.limitSeconds(limitSeconds);
      entry.match(match);
      entry.commands(commands);
      entry.perItemCommands(perItem);
      entry.removed(removed);
      entry.group(SellGroups.DEFAULT);
      return entry;
    } catch (Exception e) {
      return null;
    }
  }

  private static List<String> splitLegacy(@Nullable String raw) {
    List<String> result = new ArrayList<>();
    if (raw == null || raw.isEmpty()) {
      return result;
    }
    for (String part : raw.split(COMMAND_SEPARATOR, -1)) {
      if (!part.isEmpty()) {
        result.add(part);
      }
    }
    return result;
  }
}
