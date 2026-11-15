package com.github.streackmc.Joyous.JSell.data;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.utils.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 可售列表的持久化（{@code models/JSell/groups/<组名>.yml}，<b>一组一个文件</b>）
 *
 * <h3>为什么一组一个文件</h3>
 * 单文件时代（{@code items.yml}）改一条价格就要重写整份存档：文件越大越容易写一半、
 * 越容易被两个人同时改坏，出问题时也看不出是哪一组乱了。拆开之后
 * <ul>
 *   <li>改哪个组就只重写哪个文件，其余文件连时间戳都不动；</li>
 *   <li>手改/回滚/丢进版本库都按组进行，一个文件坏了只影响那一组；</li>
 *   <li>组的边界一眼可见——{@code groups/} 里有哪些文件就有哪些组。</li>
 * </ul>
 *
 * <h3>文件长什么样</h3>
 * <b>组名就是文件名</b>，条目里不再重复写 {@code group}（避免两处说法冲突时不知道信谁）。
 * 一条记录一个列表项，字段逐项铺开，可以直接手改：
 * <pre>
 * # models/JSell/groups/farming.yml
 * format: 3
 * entries:
 * - id: 8f3e2c1a-...
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
 * <b>为什么物品模板仍然是 Base64</b>：{@code ItemStack} 的 NBT 只有
 * {@code serializeAsBytes} 这一个稳定、跨版本可读的表示；把它拆成 YAML 字段等于自己维护一份
 * 组件映射表，版本一更新就烂。除了这一项，其余全部是明文。
 *
 * <h3>文件名与组名的对应</h3>
 * 组名规范化后只含 {@code [a-z0-9_-]} 且不超过 32 字符（见 {@link SellGroups#normalize}），
 * 所以能直接当文件名用，跨平台安全。只有 Windows 的保留设备名（{@code con} / {@code nul} /
 * {@code com1} …）会额外加一个 {@code _} 前缀（{@code con.yml} → {@code _con.yml}），读回来时再摘掉。
 * 手工把文件改名成不规范的名字（大写、空格）也能读进来——内容归属文件名规范化后的组，
 * 随后写回规范文件并把旧文件删掉。
 *
 * <h3>写配置的约束（StreackLib 当前版本）</h3>
 * {@code putNested} 把<b>第一个点</b>当成「最后一个点」，所以 ≥3 层的写路径会把前缀重复进叶子键。
 * 这里只用两个路径：{@code format}（无点）与 {@code entries}（无点，值是嵌套 Map 列表），
 * 结构由 {@link SConfig#putList(String, List)} 一次写入，不经过拆点逻辑。
 *
 * <h3>兼容旧存档</h3>
 * 单文件时代的两代格式都会被读出来，按组拆分到 {@code groups/}，随后把原文件改名成
 * {@code items.legacy.bak}（不删，人工还能翻回去对照）：
 * <ul>
 *   <li>{@code format: 2} —— {@code entries} 列表，每项带 {@code group}；</li>
 *   <li>0.8.x —— {@code items} 列表，一行一条整条 Base64，全部归 {@code default} 组。</li>
 * </ul>
 *
 * @author Neonai
 * @since 0.3.0
 */
public final class SellEntryStore {

  /** 分组文件的扩展名 */
  private static final String EXTENSION = ".yml";
  /** 分组文件里「记录列表」的键名 */
  private static final String ROOT = "entries";
  /** 旧格式（0.8.x）：整条 Base64 的扁平列表 */
  private static final String LEGACY_ROOT = "items";
  /** 格式版本；结构变化时递增（3 = 一组一文件，条目内不再写 group） */
  private static final int FORMAT_VERSION = 3;
  /** 单文件时代的存档名；发现它就拆分、然后归档 */
  private static final String LEGACY_FILE = "items.yml";
  /** 拆分完成后旧存档改名成这个 */
  private static final String LEGACY_ARCHIVE = "items.legacy.bak";
  /** Windows 上不能直接当文件名的设备名（存档可能在平台间搬来搬去） */
  private static final Set<String> RESERVED_NAMES = Set.of(
      "con", "prn", "aux", "nul",
      "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
      "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
  /** 命令分隔符（NUL 不可能出现在聊天栏输入里） */
  private static final String COMMAND_SEPARATOR = "\u0000";

  /** {@code models/JSell/groups/} */
  private final File dir;
  /** 单文件时代的 {@code models/JSell/items.yml}，只在迁移时读 */
  private final File legacyFile;
  /** 条目按登记顺序保存，差异相同的两条保持「先登记先命中」的直觉顺序 */
  private final Map<UUID, SellEntry> entries = new LinkedHashMap<>();
  /** 按材质分组：界面每重绘一次要判定几十个物品，不能每次都扫全表 */
  private final Map<Material, List<SellEntry>> byMaterial = new HashMap<>();
  /** 有改动、还没落盘的分组（只重写这些文件） */
  private final Set<String> dirtyGroups = new LinkedHashSet<>();
  /**
   * 每条记录<b>在磁盘上</b>属于哪个组
   * <p>
   * 条目是可变对象，管理员在详情页改分组时改的是<b>同一份实例</b>——等 {@link #save} 拿到它，
   * 旧值已经被覆盖了。没有这张快照就发现不了「搬家」，旧组文件里那一条也就删不掉。
   */
  private final Map<UUID, String> persistedGroups = new HashMap<>();
  private boolean indexDirty = true;
  private volatile boolean dirty;

  public SellEntryStore(File dataDir) {
    File root = new File(dataDir, "models/JSell");
    this.dir = new File(root, "groups");
    this.legacyFile = new File(root, LEGACY_FILE);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      jlogger.warn("JSell 无法创建可售列表目录 %s", dir.getPath());
    }
    reload();
  }

  /**
   * 从磁盘重新读取全部可售物品
   * <p>
   * 顺序：① 读 {@code groups/*.yml}；② 若还有单文件时代的老存档，读出来并<b>并进</b>内存
   * （不覆盖已读到的同名 id：分组文件是更新的权威）；③ 按登记时间排序后统一入表；
   * ④ 把需要修正的分组（迁移、文件名不规范）重写落盘；⑤ 归档老存档。
   */
  public synchronized void reload() {
    entries.clear();
    persistedGroups.clear();
    dirtyGroups.clear();
    indexDirty = true;

    List<SellEntry> loaded = new ArrayList<>();
    Set<File> stale = new LinkedHashSet<>();
    int broken = loadGroupFiles(loaded, stale);

    // 单文件时代的老存档（v2 / 0.8.x）：读出来，一会儿并进分组文件
    LegacyRead legacy = readLegacyFile();
    List<SellEntry> migrated = legacy.entries();
    broken += legacy.broken;

    // 分组文件是更新的权威：老存档里同 id 的记录不能把已经编辑过的值顶回去
    Set<UUID> known = new LinkedHashSet<>();
    for (SellEntry entry : loaded) {
      known.add(entry.id());
    }
    for (SellEntry entry : migrated) {
      if (known.add(entry.id())) {
        loaded.add(entry);
      }
    }

    // 全局按登记时间排序：「同代价取先登记」与条目落在哪个文件无关，文件顺序不影响结果
    loaded.sort(Comparator.comparingLong(SellEntry::createdAt));
    for (SellEntry entry : loaded) {
      entries.put(entry.id(), entry);
      persistedGroups.put(entry.id(), entry.group());
    }

    if (broken > 0) {
      jlogger.warn("JSell 可售列表里有 %s 条记录无法解析，已跳过（目录：%s）", broken, dir.getPath());
    }

    indexDirty = true;
    SellGroups.register(groups());

    if (!migrated.isEmpty()) {
      for (SellEntry entry : migrated) {
        dirtyGroups.add(entry.group());
      }
      jlogger.info("JSell 检测到单文件存档（%s 条），正在按分组建档", migrated.size());
    }

    if (!dirtyGroups.isEmpty()) {
      writeDirty();
      // 非规范文件名的旧文件，内容已经写进规范文件了，删掉免得下次重载又并一遍
      for (File file : stale) {
        deleteQuietly(file);
      }
    }

    archiveLegacy(legacy);
    // 迁移/改名写盘失败时 dirtyGroups 里还有东西，别把它当成「已落盘」
    dirty = !dirtyGroups.isEmpty();
    jlogger.debug("JSell 已载入 %s 条可售物品（%s 个分组）", entries.size(), groups().size());
  }

  /** 分组文件所在目录（{@code models/JSell/groups}） */
  public File dir() {
    return dir;
  }

  /** 某个分组对应的存档文件 */
  public File fileOf(@Nullable String group) {
    String normalized = SellGroups.normalize(group);
    String prefix = RESERVED_NAMES.contains(normalized) ? "_" : "";
    return new File(dir, prefix + normalized + EXTENSION);
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

  /**
   * 新增或更新一条（只改内存，落盘见 {@link #flush()}）
   * <p>
   * 改分组时要<b>两个组都重写</b>：新组要出现这一条，旧组要让它消失。
   * 「旧组」取自 {@link #persistedGroups} 而不是入参对象——入参往往就是表里那一份，
   * 它的 {@code group} 已经被改成分组输入框里的新值了。
   */
  public synchronized void save(SellEntry entry) {
    entries.put(entry.id(), entry);
    String previous = persistedGroups.put(entry.id(), entry.group());
    if (previous != null) {
      dirtyGroups.add(previous);
    }
    dirtyGroups.add(entry.group());
    indexDirty = true;
    dirty = true;
    SellGroups.register(List.of(entry.group()));
  }

  /** 删除一条（真正的删除；「移除出价值列表」请用 {@link SellEntry#removed(boolean)}） */
  public synchronized void delete(UUID id) {
    SellEntry removed = entries.remove(id);
    persistedGroups.remove(id);
    if (removed == null) {
      return;
    }
    dirtyGroups.add(removed.group());
    indexDirty = true;
    dirty = true;
  }

  public boolean isDirty() {
    return dirty;
  }

  /** 落盘：只重写有改动的分组文件（编辑界面关闭/保存时统一调用） */
  public synchronized void flush() {
    if (!dirty && dirtyGroups.isEmpty()) {
      return;
    }
    writeDirty();
  }

  /** 把 {@link #dirtyGroups} 里的分组逐个写盘；写失败的分组留在表里等下一次 */
  private void writeDirty() {
    boolean failed = false;
    for (String group : new ArrayList<>(dirtyGroups)) {
      if (writeGroup(group)) {
        dirtyGroups.remove(group);
      } else {
        failed = true;
      }
    }
    dirty = failed || !dirtyGroups.isEmpty();
  }

  /**
   * 重写一个分组文件
   * <p>
   * 该组已经没有任何条目时<b>删掉文件</b>——组的边界就是文件，留着空文件等于留下一个空组。
   *
   * @return 是否成功（失败时保持 dirty，下次 flush 再试）
   */
  private boolean writeGroup(String group) {
    File target = fileOf(group);
    List<Object> rows = new ArrayList<>();
    for (SellEntry entry : entries.values()) {
      if (!entry.group().equals(group)) {
        continue;
      }
      Map<String, Object> row = encodeRow(entry);
      if (row != null) {
        rows.add(row);
      }
    }
    if (rows.isEmpty()) {
      if (target.isFile() && !target.delete()) {
        jlogger.warn("JSell 分组「%s」已经没有条目，但删不掉空文件 %s", group, target.getPath());
        return false;
      }
      return true;
    }
    try {
      SConfig conf = new SConfig(target, SConfig.TYPES.YAMLc);
      // 批量写入后手动 save()，避免每次 put 都落盘
      conf.setWriteMode(SConfig.WRITE_MODE.INERTIA);
      try {
        conf.reload();
      } catch (Exception ignored) {
        // 新文件读不到是正常的，下面直接整份写
      }
      conf.putInt("format", FORMAT_VERSION);
      conf.putComment("format",
          "格式版本；结构变化时递增。本文件的组名就是文件名（" + target.getName()
              + "），条目里不再重复写 group");
      conf.putComment(ROOT,
          "一项一条可售物品；item 是物品模板的 Base64（含完整 NBT），其余字段都是明文");
      conf.putList(ROOT, rows);
      // 老格式的键必须消失，否则下次载入会把废弃数据再并进来
      conf.remove(LEGACY_ROOT);
      conf.save();
      // 这一组已经落盘，组别快照跟着对齐（下次「搬家」才知道从哪搬）
      for (SellEntry entry : entries.values()) {
        if (entry.group().equals(group)) {
          persistedGroups.put(entry.id(), group);
        }
      }
      return true;
    } catch (Exception e) {
      jlogger.warn("JSell 无法保存分组「%s」的可售列表 %s：%s", group, target.getPath(),
          e.getLocalizedMessage());
      return false;
    }
  }

  // ──────────────────────────────────────────────
  // 载入：分组文件
  // ──────────────────────────────────────────────

  /**
   * 读取 {@code groups/} 下的全部 {@code *.yml}
   *
   * @param sink  读到的条目（调用方负责入表）
   * @param stale 文件名不是规范组名的旧文件（内容已读入，写回规范文件后可以删掉）
   * @return 解析失败的条数
   */
  private int loadGroupFiles(List<SellEntry> sink, Set<File> stale) {
    File[] files = dir.listFiles();
    if (files == null) {
      return 0;
    }
    // 文件名字典序：只影响同毫秒登记的条目的相对顺序，随后还会按 created 重排
    Arrays.sort(files, Comparator.comparing(File::getName));
    int broken = 0;
    for (File file : files) {
      if (!file.isFile()) {
        continue;
      }
      String raw = rawGroupName(file);
      if (raw == null) {
        continue;
      }
      String group = SellGroups.normalize(raw);
      broken += loadGroupFile(file, group, sink);
      // 判据是「写盘时会用哪个文件名」而不是字符串比一比：手改的大写/空格、
      // 以及保留设备名少写 `_` 前缀（con.yml 应当叫 _con.yml）都算不规范
      File canonical = fileOf(group);
      if (!canonical.getName().equals(file.getName())) {
        dirtyGroups.add(group);
        stale.add(file);
        jlogger.info("JSell 分组文件 %s 的文件名不规范，已按「%s」载入并写回 %s",
            file.getName(), group, canonical.getName());
      }
    }
    return broken;
  }

  private int loadGroupFile(File file, String group, List<SellEntry> sink) {
    SConfig conf = new SConfig(file, SConfig.TYPES.YAMLc);
    try {
      conf.reload();
    } catch (Exception e) {
      jlogger.warn("JSell 无法读取分组文件 %s：%s", file.getPath(), e.getLocalizedMessage());
      return 0;
    }
    int broken = 0;
    for (Object row : conf.getList(ROOT, List.of())) {
      SellEntry entry = decodeRow(row, group, false);
      if (entry == null) {
        broken++;
        continue;
      }
      // 组名只认文件名，行里哪怕写了 group 也不算数
      entry.group(group);
      sink.add(entry);
    }
    return broken;
  }

  /**
   * 文件名 → 原始组名；不是 {@code .yml} 返回 null
   * <p>
   * 摘掉写入时给 Windows 保留设备名加的前缀（{@code _con.yml} → {@code con}）。
   */
  @Nullable
  private static String rawGroupName(File file) {
    String name = file.getName();
    if (!name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
      return null;
    }
    String base = name.substring(0, name.length() - EXTENSION.length());
    if (base.length() > 1 && base.charAt(0) == '_'
        && RESERVED_NAMES.contains(base.substring(1).toLowerCase(Locale.ROOT))) {
      base = base.substring(1);
    }
    return base;
  }

  // ──────────────────────────────────────────────
  // 载入：单文件时代的老存档
  // ──────────────────────────────────────────────

  /**
   * 单文件老存档的读取结果
   *
   * @param entries 读出来的条目（已按行内 {@code group} 归组）
   * @param broken  解析失败的条数
   * @param readable 文件是否读成功（读失败时不能归档，否则数据就被藏起来了）
   */
  private record LegacyRead(List<SellEntry> entries, int broken, boolean readable) {
  }

  /** 读出 {@code items.yml}（v2 的 entries 或 0.8.x 的 items），没有就返回空 */
  private LegacyRead readLegacyFile() {
    List<SellEntry> result = new ArrayList<>();
    if (!legacyFile.isFile()) {
      return new LegacyRead(result, 0, false);
    }
    SConfig conf = new SConfig(legacyFile, SConfig.TYPES.YAMLc);
    try {
      conf.reload();
    } catch (Exception e) {
      jlogger.warn("JSell 无法读取旧存档 %s：%s", legacyFile.getPath(), e.getLocalizedMessage());
      return new LegacyRead(result, 0, false);
    }
    int broken = 0;
    for (Object row : conf.getList(ROOT, List.of())) {
      SellEntry entry = decodeRow(row, SellGroups.DEFAULT, true);
      if (entry == null) {
        broken++;
        continue;
      }
      result.add(entry);
    }
    for (String line : conf.getListOfString(LEGACY_ROOT, List.of())) {
      SellEntry entry = decodeLegacy(line);
      if (entry == null) {
        broken++;
        continue;
      }
      result.add(entry);
    }
    if (broken > 0) {
      jlogger.warn("JSell 旧存档里有 %s 条记录无法解析，已跳过（%s）；该文件不会被归档，"
          + "请修好或手动挪走后再启动", broken, legacyFile.getPath());
    }
    return new LegacyRead(result, broken, true);
  }

  /**
   * 把 {@code items.yml} 改名成 {@code items.legacy.bak}
   * <p>
   * 不改名的话下次重载会再并一遍。但有解析失败的记录时<b>不动它</b>：改名虽然不丢数据，
   * 却会让那几条彻底从服务里消失，管理员还以为迁移成功了。
   */
  private void archiveLegacy(LegacyRead read) {
    if (!legacyFile.isFile()) {
      return;
    }
    if (!read.readable() || read.broken() > 0) {
      jlogger.warn("JSell 旧存档 %s 未归档（读取失败或有无法解析的记录），"
          + "迁移只做了一半，请处理后重试", legacyFile.getPath());
      return;
    }
    File target = new File(legacyFile.getParentFile(), LEGACY_ARCHIVE);
    if (target.isFile()) {
      deleteQuietly(target);
    }
    if (legacyFile.renameTo(target)) {
      jlogger.info("JSell 旧存档已归档为 %s（确认 groups/ 里内容无误后可以删除）", target.getName());
      return;
    }
    jlogger.warn("JSell 无法把 %s 归档为 %s —— 请手动挪走或删除，否则每次重载都会再合并一次",
        legacyFile.getPath(), target.getName());
  }

  private static void deleteQuietly(File file) {
    if (file != null && file.isFile() && !file.delete()) {
      jlogger.warn("JSell 删不掉旧文件 %s", file.getPath());
    }
  }

  // ──────────────────────────────────────────────
  // 编码：一条记录 ←→ 一个有序 Map
  // ──────────────────────────────────────────────

  /**
   * 一条记录 → 一个有序 Map
   * <p>
   * 顺序刻意按「人最想先看到什么」排：id / 物品 / 价格 …… 命令放最后。
   * 空列表与默认值不写，文件才不会一半是空数组。{@code group} 不写——它就是文件名。
   */
  @Nullable
  private static Map<String, Object> encodeRow(SellEntry entry) {
    String item = SellEntry.encodeItem(entry.template());
    if (item == null) {
      return null;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", entry.id().toString());
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

  /**
   * 一个 Map → 一条记录；缺字段用默认值，模板坏了返回 null
   *
   * @param fallbackGroup 条目归属的组
   * @param readGroupKey  是否采用行内的 {@code group}（只有单文件时代的老存档需要）
   */
  @Nullable
  private static SellEntry decodeRow(@Nullable Object raw, String fallbackGroup,
      boolean readGroupKey) {
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
      entry.group(readGroupKey ? asString(row.get("group"), fallbackGroup) : fallbackGroup);
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
  // 编码：旧格式（0.8.x，仅供迁移）
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
