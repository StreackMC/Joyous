package com.github.streackmc.Joyous.JSell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 可售列表的持久化（SConfig + YAML）
 * <p>
 * 落盘位置：{@code models/JSell/items.yml}。
 * <p>
 * <b>为什么整条编码成一行而不是分层写字？</b>
 * 一是「物品模板」必须原样保留 NBT，Base64 是最省事的容器；二是条目里有命令原文这种自由文本，
 * 塞进 YAML 结构里会牵扯转义与缩进；三是当前这版 StreackLib 的 {@code putNested} 把
 * <b>第一个点</b>当成「最后一个点」（{@code getIndexOfNormalDot} 命中即 {@code return i}），
 * 于是任何 ≥3 层的写入都会把前缀重复进叶子键：
 * <pre>
 *   putString("items.&lt;uuid&gt;.item", v)
 *   → items: { &lt;uuid&gt;: { "&lt;uuid&gt;.item": v } }   // 读回来找不到
 * </pre>
 * 所以这里只用一个扁平列表：一行一条、整条 Base64，键 {@code items} 连一个点都没有。
 * <p>
 * <b>写配置的通用规则（当前 StreackLib 版本）</b>：路径最多一个点（如 {@code profiles.<uuid>}），
 * 更深的层级请把子结构做成 Map 用 {@link SConfig#putSection(String, java.util.Map)} 一次写入；
 * 读不受此限（{@code getNested} 是逐段拆的）。注意这与 {@code put(key, value)} 无关——
 * 那个是 {@code cache.put(key, value)}，连一层都不拆，且已被标记 deprecated，不应使用。
 * 本文只用到严格类型 API，唯一的写是单个 {@code putListOfString}。
 * <p>
 * 代价是文件不能手改（改坏了这条就会被跳过并记 WARN）——但可售列表本来就是给游戏内编辑界面用的。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class SellEntryStore {

  private static final String FILE_NAME = "items.yml";
  /** 扁平列表的键名 */
  private static final String ROOT = "items";
  /** 编码格式版本；改动编码方式时递增，旧数据会被跳过 */
  private static final int FORMAT_VERSION = 1;
  /** 命令分隔符（NUL 不可能出现在聊天栏输入里） */
  private static final String COMMAND_SEPARATOR = "\u0000";

  private final File file;
  private final SConfig conf;
  /** 条目按登记顺序保存，匹配时保持「先登记先命中」的直觉顺序 */
  private final Map<UUID, SellEntry> entries = new LinkedHashMap<>();
  /** 按材质分组：界面每重绘一次要判定几十个物品，不能每次都扫全表 */
  private final Map<Material, List<SellEntry>> byMaterial = new java.util.HashMap<>();
  private boolean indexDirty = true;
  private volatile boolean dirty;

  public SellEntryStore(File dataDir) {
    this.file = new File(new File(dataDir, "models/JSell"), FILE_NAME);
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    this.conf = new SConfig(file, SConfig.TYPES.YAML);
    // 批量写入后手动 save()，避免每次改动都落盘
    this.conf.setWriteMode(SConfig.WRITE_MODE.INERTIA);
    reload();
    if (!file.exists()) {
      writeNow();
    }
  }

  /** 从磁盘重新读取全部可售物品 */
  public synchronized void reload() {
    entries.clear();
    try {
      conf.reload();
    } catch (Exception e) {
      jlogger.warn("JSell 无法读取可售列表 %s：%s", file.getPath(), e.getLocalizedMessage());
    }
    List<String> encoded = conf.getListOfString(ROOT, List.of());
    int broken = 0;
    for (String line : encoded) {
      SellEntry entry = decode(line);
      if (entry == null) {
        broken++;
        continue;
      }
      entries.put(entry.id(), entry);
    }
    if (broken > 0) {
      jlogger.warn("JSell 可售列表里有 %s 条记录无法解析，已跳过（文件：%s）", broken,
          file.getPath());
    }
    dirty = false;
    indexDirty = true;
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

  @Nullable
  public synchronized SellEntry byId(UUID id) {
    return entries.get(id);
  }

  /**
   * 找出命中该物品的条目
   * <p>
   * <b>先严格后包含</b>：严格相等是最具体的规则，如果先跑「至少包含」，
   * 一条「任意钻石剑」就会把「指定附魔的钻石剑」那条更精确的规则盖掉。
   * 已从价值列表移除的条目直接跳过（等价于不可售）。
   *
   * @param item 待判定物品
   * @return 命中的条目；没有则 null
   */
  @Nullable
  public synchronized SellEntry find(@Nullable ItemStack item) {
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
    for (SellMatch mode : new SellMatch[] { SellMatch.STRICT, SellMatch.CONTAINS }) {
      for (SellEntry entry : candidates) {
        if (entry.removed() || entry.match() != mode) {
          continue;
        }
        if (entry.matches(item)) {
          return entry;
        }
      }
    }
    return null;
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
      List<String> encoded = new ArrayList<>(entries.size());
      for (SellEntry entry : entries.values()) {
        String line = encode(entry);
        if (line != null) {
          encoded.add(line);
        }
      }
      conf.putListOfString(ROOT, encoded);
      conf.save();
      dirty = false;
    } catch (Exception e) {
      jlogger.warn("JSell 无法保存可售列表 %s：%s", file.getPath(), e.getLocalizedMessage());
    }
  }

  // ──────────────────────────────────────────────
  // 编码
  // ──────────────────────────────────────────────

  /**
   * 一条记录 → Base64
   * <pre>
   *   byte   版本
   *   UTF    条目 UUID
   *   UTF    物品模板的 Base64（ItemStack.serializeAsBytes，NBT 完整保留）
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
  static String encode(SellEntry entry) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(FORMAT_VERSION);
      out.writeUTF(entry.id().toString());
      out.writeUTF(SellEntry.encodeItem(entry.template()));
      out.writeDouble(entry.unitValue());
      out.writeInt(entry.limitCount());
      out.writeLong(entry.limitSeconds());
      out.writeUTF(entry.match().id());
      out.writeUTF(String.join(COMMAND_SEPARATOR, entry.commands()));
      out.writeUTF(String.join(COMMAND_SEPARATOR, entry.perItemCommands()));
      out.writeBoolean(entry.removed());
      out.writeLong(entry.createdAt());
    } catch (Exception e) {
      jlogger.warn("JSell 编码可售物品失败：%s", e.getLocalizedMessage());
      return null;
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  /** Base64 → 一条记录；格式不认识或物品模板损坏返回 null */
  @Nullable
  static SellEntry decode(@Nullable String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    try (DataInputStream in = new DataInputStream(
        new ByteArrayInputStream(Base64.getDecoder().decode(line.trim())))) {
      if (in.readByte() != FORMAT_VERSION) {
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
      List<String> commands = split(in.readUTF());
      List<String> perItem = split(in.readUTF());
      boolean removed = in.readBoolean();
      long created = in.readLong();

      SellEntry entry = new SellEntry(id, template, value, created);
      entry.limitCount(limitCount);
      entry.limitSeconds(limitSeconds);
      entry.match(match);
      entry.commands(commands);
      entry.perItemCommands(perItem);
      entry.removed(removed);
      return entry;
    } catch (Exception e) {
      return null;
    }
  }

  private static List<String> split(@Nullable String raw) {
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
