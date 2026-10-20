package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * GUI 基础设施
 * <p>
 * 提供三样东西：区分「我们的界面」的 {@link Holder}、黑/白玻璃板占位物品的构造，
 * 以及标题与物品文本的统一处理。占位物品带一个 PDC 标记，
 * 用于在点击/拖拽时把它们和我们自己的真实结果区分开。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardGui {

  /** 占位物品的 PDC 标记键：{@code joyous} */
  public static final String PLACEHOLDER_KEY = "joyous";
  /** 未搜索（黑色玻璃板） */
  public static final String TAG_UNSEARCHED = "jc_placeholder_unsearched";
  /** 搜索中（白色玻璃板） */
  public static final String TAG_SEARCHING = "jc_placeholder_searching";

  /** 状态名（写进 {@code custom_model_data.strings[1]}） */
  public static final String STATE_UNSEARCHED = "unsearched";
  /** 状态名：搜索中 */
  public static final String STATE_SEARCHING = "searching";

  /** 全部状态名 */
  private static final String[] STATES = { STATE_UNSEARCHED, STATE_SEARCHING };

  private WardGui() {
  }

  // ──────────────────────────────────────────────
  // 界面识别
  // ──────────────────────────────────────────────

  /** 我们的界面类型 */
  public enum Kind {
    /** 编辑界面主界面 */
    EDITOR,
    /** 容器类型选择子菜单 */
    CONTAINER_PICKER,
    /** 待选物品编辑子菜单 */
    ITEM_EDITOR,
    /** 各品质最大抽出数量编辑 */
    MAX_DRAW,
    /** 数量分布编辑 */
    DISTRIBUTION,
    /** 运行时自定义容器 */
    RUNTIME,
    /** 预览 */
    PREVIEW
  }

  /** 自定义 {@link InventoryHolder}，用于把界面与它的上下文绑在一起 */
  public static final class Holder implements InventoryHolder {
    private final Kind kind;
    private final UUID profileId;
    private final Object payload;
    private Inventory inventory;

    public Holder(Kind kind, UUID profileId, @Nullable Object payload) {
      this.kind = kind;
      this.profileId = profileId;
      this.payload = payload;
    }

    public Kind kind() {
      return kind;
    }

    public UUID profileId() {
      return profileId;
    }

    /** 附加上下文（如页码、会话引用），可为 null */
    @Nullable
    public Object payload() {
      return payload;
    }

    public void attach(Inventory inventory) {
      this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  /** 是否为我们的界面；不是则返回 null */
  @Nullable
  public static Holder holderOf(@Nullable Inventory inventory) {
    if (inventory == null)
      return null;
    InventoryHolder holder = inventory.getHolder();
    return (holder instanceof Holder h) ? h : null;
  }

  // ──────────────────────────────────────────────
  // 占位物品
  // ──────────────────────────────────────────────

  /**
   * 未搜索占位（黑色玻璃板）
   * <p>
   * 命名与 Lore 取自配置，PDC 标记为 {@link #TAG_UNSEARCHED}，
   * 对应约定里的 {@code custom_data={joyous:"jc_placeholder_unsearched"}}。
   */
  public static ItemStack unsearchedPane() {
    return pane(Material.BLACK_STAINED_GLASS_PANE, STATE_UNSEARCHED,
        JwardChestMain.CONF.placeholderNoneRarity(), null, null);
  }

  /**
   * 「搜索中」占位的一帧
   * <p>
   * {@code material} 与 {@code text} 是<b>同一次计算的两个产物</b>，所以「进入紧急窗口」时
   * 玻璃板颜色与文字颜色必然同步变化——不需要调用方再单独问一次「现在紧急了吗」。
   * <p>
   * 文本里已经编码了颜色，因此两帧相等就代表这一格画出来不会有任何变化，
   * 调用方可以据此跳过更新（这是把发包量压下来的关键）。
   *
   * @param rarity   该槽结果的珍惜度（写进 {@code custom_model_data.strings[0]}）
   * @param material 玻璃板材质
   * @param text     带颜色码的显示文本
   */
  public record Frame(WardRarity rarity, Material material, String text) {
  }

  /**
   * 粗筛阶段的一帧：<b>带剩余秒数</b>
   * <p>
   * 文本刻意做成<b>量化</b>的：非紧急阶段只显示整秒（每秒才变一次），紧急阶段才用 0.1s 精度，
   * 于是同色同秒的重复帧会被调用方丢掉。
   *
   * @param rarity          结果珍惜度（该阶段用「最高珍惜度」）
   * @param remainMillis    剩余毫秒
   * @param durationSeconds 本次粗筛总时长（用于按比例算紧急窗口）
   * @return 该时刻的帧；剩余为负时按 0 处理
   */
  public static Frame prefilterFrame(WardRarity rarity, long remainMillis,
      double durationSeconds) {
    return frameOf(rarity, remainMillis, durationSeconds, true);
  }

  /**
   * 逐结果阶段的一帧：<b>不显示秒数</b>，只在最后一段窗口内换成该结果珍惜度的颜色
   * <p>
   * 一格一个倒计时读数字很吵，而这个阶段本来就「一格一格往下走」，颜色变化足以表达进度。
   * 于是文本在整个搜索过程中恒定 —— 每格只有「开局」与「变色」两次更新。
   *
   * @param rarity          该槽结果的珍惜度
   * @param remainMillis    剩余毫秒
   * @param durationSeconds 这一格的搜索总时长
   * @return 该时刻的帧
   */
  public static Frame slotFrame(WardRarity rarity, long remainMillis, double durationSeconds) {
    return frameOf(rarity, remainMillis, durationSeconds, false);
  }

  /**
   * 按帧构造「搜索中」占位物品
   * <p>
   * 状态固定为 {@link #STATE_SEARCHING}（资源包模型与「未搜索」共用一套键的约定不变），
   * 颜色差异只体现在材质与名字上。
   *
   * @param frame 由 {@link #prefilterFrame} / {@link #slotFrame} 算出的一帧
   */
  public static ItemStack searchingPane(Frame frame) {
    return pane(frame.material(), STATE_SEARCHING, frame.rarity().id(), frame.text(), null);
  }

  /**
   * 算出某一时刻的帧（材质 + 文本一次算完）
   *
   * @param withSeconds 是否显示剩余秒数（粗筛 true / 逐结果 false）
   */
  private static Frame frameOf(WardRarity rarity, long remainMillis, double durationSeconds,
      boolean withSeconds) {
    boolean urgent = isUrgent(remainMillis, durationSeconds);
    String color = urgent ? rarity.color() : "\u00a7f";
    String text = withSeconds
        ? color + seconds(remainMillis, urgent) + " s"
        : color + i18n.tr("jwardchest.container.searching");
    Material material = urgent ? rarity.glass() : Material.WHITE_STAINED_GLASS_PANE;
    return new Frame(rarity, material, text);
  }

  /** 剩余秒数的显示文本：紧急阶段用 0.1s 精度，其余整秒（量化以减少刷新） */
  private static String seconds(long remainMillis, boolean urgent) {
    double remain = Math.max(0L, remainMillis) / 1000.0;
    return String.format(java.util.Locale.ROOT, urgent ? "%.1f" : "%.0f", remain);
  }

  /**
   * 剩余时间是否已进入「紧急着色窗口」
   * <p>
   * 窗口按本次时长成比例算并夹取，见 {@link JwardChestMain.CONF#urgentWindowSeconds(double)}。
   */
  private static boolean isUrgent(long remainMillis, double durationSeconds) {
    double window = JwardChestMain.CONF.urgentWindowSeconds(durationSeconds);
    return window > 0.0 && Math.max(0L, remainMillis) / 1000.0 <= window;
  }

  /**
   * 通用玻璃板构造
   *
   * @param state     状态名（{@link #STATE_UNSEARCHED} / {@link #STATE_SEARCHING}）——
   *                  写进 {@code custom_model_data.strings[1]}，PDC 标记也由它推出来
   * @param rarityArg 品质位（珍惜度 id，或未搜索时的约定值）
   */
  private static ItemStack pane(Material material, String state, String rarityArg,
      @Nullable String nameOverride, @Nullable List<String> loreOverride) {
    String tag = tagOfState(state);
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return item;
    }

    String name = nameOverride != null ? nameOverride : JwardChestMain.CONF.placeholderName();
    if (name != null && !name.isBlank()) {
      meta.displayName(legacy(MCColor.parse(name)));
    }

    if (loreOverride != null) {
      if (!loreOverride.isEmpty()) {
        meta.lore(loreOverride.stream().map(l -> legacy(MCColor.parse(l))).toList());
      }
    } else {
      List<String> loreRaw = JwardChestMain.CONF.placeholderLore();
      List<Component> lore = new ArrayList<>(loreRaw.size());
      for (String line : loreRaw) {
        if (line == null)
          continue;
        lore.add(legacy(MCColor.parse(line)));
      }
      if (!lore.isEmpty()) {
        meta.lore(lore);
      }
    }

    applyModel(meta, rarityArg, state);
    meta.getPersistentDataContainer().set(
        new NamespacedKey(Joyous.plugin, PLACEHOLDER_KEY), PersistentDataType.STRING, tag);
    item.setItemMeta(meta);
    return item;
  }

  /**
   * 写「物品模型映射」的两个组件
   * <p>
   * 资源包侧只有<b>一个</b>模型定义（{@code placeholder.item-model}），它用
   * {@code minecraft:select} 读 {@code custom_model_data.strings} 来决定画哪张贴图：
   * <pre>
   *   strings[0] = 品质（珍惜度 id；未搜索阶段为 placeholder.none-rarity）
   *   strings[1] = 状态（unsearched / searching）
   * </pre>
   * 于是「N 档珍惜度 × 2 种状态」的贴图矩阵完全由资源包声明，插件不再按状态挑模型键，
   * 也不会出现「模型键在配置里、状态判断在代码里」两边对不上的情况。
   * <p>
   * {@code item-model} 留空时不写任何组件（客户端显示玻璃板本色）。
   *
   * @param rarityArg 品质位（珍惜度 id 或未搜索的约定值）
   * @param state     状态名（{@link #STATE_UNSEARCHED} / {@link #STATE_SEARCHING}）
   */
  private static void applyModel(ItemMeta meta, String rarityArg, String state) {
    NamespacedKey key = itemModelKey();
    if (key == null) {
      return;
    }
    meta.setItemModel(key);

    CustomModelDataComponent modelData = meta.getCustomModelDataComponent();
    if (modelData == null) {
      jlogger.warn("JwardChest 无法写入 custom_model_data（服务端未提供该组件），占位将退回默认贴图");
      return;
    }
    modelData.setStrings(List.of(rarityArg, state));
    meta.setCustomModelDataComponent(modelData);
  }

  /**
   * 解析配置里的 {@code item_model} 键
   * <p>
   * 带 {@code :} 的按「命名空间:路径」解析；不带 {@code :} 的视为 {@code minecraft} 命名空间下的路径。
   *
   * @return 模型键；未配置或非法返回 null
   */
  @Nullable
  private static NamespacedKey itemModelKey() {
    String raw = JwardChestMain.CONF.placeholderItemModel();
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    try {
      return value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
    } catch (Exception e) {
      // 配置里写了非法键（如大写、中文）时不要让整个搜索流程炸掉
      jlogger.warn("JwardChest 占位物品的 item-model [%s] 非法，已忽略：%s", value,
          e.getLocalizedMessage());
      return null;
    }
  }

  /**
   * 从组件反推「这是我们第几种状态的占位」
   * <p>
   * 判定条件两条都要满足：{@code item_model} 命中配置的键，且
   * {@code custom_model_data.strings[1]} 是我们认识的<b>状态</b>名——
   * 只看模型键不足以区分状态，只看字符串又可能撞上别人的物品。
   *
   * @return 状态名；不是我们的占位返回 null
   */
  @Nullable
  private static String modelState(ItemMeta meta) {
    NamespacedKey ours = itemModelKey();
    if (ours == null || !meta.hasItemModel() || !ours.equals(meta.getItemModel())) {
      return null;
    }
    List<String> strings = modelStrings(meta);
    if (strings.size() < 2) {
      return null;
    }
    String state = strings.get(1);
    for (String known : STATES) {
      if (known.equals(state)) {
        return state;
      }
    }
    return null;
  }

  /** 读 {@code custom_model_data.strings}；没有该组件时返回空列表 */
  private static List<String> modelStrings(ItemMeta meta) {
    if (!meta.hasCustomModelDataComponent()) {
      return List.of();
    }
    CustomModelDataComponent modelData = meta.getCustomModelDataComponent();
    List<String> strings = modelData == null ? null : modelData.getStrings();
    return strings == null ? List.of() : strings;
  }

  /** 状态名 → PDC 标记值 */
  private static String tagOfState(String state) {
    return STATE_UNSEARCHED.equals(state) ? TAG_UNSEARCHED : TAG_SEARCHING;
  }

  // ──────────────────────────────────────────────
  // 判定与文本
  // ──────────────────────────────────────────────

  /**
   * 是否为我们的占位物品（双重校验）
   * <p>
   * 信号一：物品 PDC 里的来源标记（{@code custom_data}，说明「这个物品由 Joyous 产生」）。
   * 信号二：{@code item_model} 命中我们配置的占位模型（资源包识别用的那一套）。
   * 任一命中即认定是我们的占位物品——这样即使某个环节被第三方插件抹掉，仍能拦住玩家取走占位。
   *
   * @param item 待判定物品，可为 null
   */
  public static boolean isPlaceholder(@Nullable ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return false;
    }
    if (tagOf(meta) != null) {
      return true;
    }
    return modelState(meta) != null;
  }

  /**
   * 占位物品的标记值；非占位返回 null
   * <p>
   * 优先取 PDC 里的来源标记；缺失时用 {@code item_model} 反推状态，
   * 与 {@link #isPlaceholder(ItemStack)} 的双重校验保持一致。
   */
  @Nullable
  public static String placeholderTag(@Nullable ItemStack item) {
    if (item == null || !item.hasItemMeta()) {
      return null;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return null;
    }
    String tag = tagOf(meta);
    if (tag != null) {
      return tag;
    }
    String state = modelState(meta);
    return state == null ? null : tagOfState(state);
  }

  /** 读 PDC 里的来源标记 */
  @Nullable
  private static String tagOf(ItemMeta meta) {
    return meta.getPersistentDataContainer()
        .get(new NamespacedKey(Joyous.plugin, PLACEHOLDER_KEY), PersistentDataType.STRING);
  }

  /**
   * 把带颜色码的文本转成 Component（文字装饰按原版物品语义处理）
   * <p>
   * <b>必须走这里而不是直接用 {@link LegacyComponentSerializer}</b>：物品的名称与 Lore
   * 在原版的默认样式是<b>斜体</b>，而 {@code deserialize} 出来的组件其
   * {@link TextDecoration} 一律是 {@link TextDecoration.State#NOT_SET}——
   * 未显式设置的斜体就会被原版按「开启」渲染。{@link #ensureReset(Component)}
   * 把「未显式设置」的装饰显式写成 {@code false}，于是文本与 JMenu 的按钮一样是正体。
   * 显式写了 {@code §l} 之类的仍然保留（那些是显式状态，不会被改写）。
   */
  public static Component legacy(String colorized) {
    return ensureReset(
        LegacyComponentSerializer.legacySection().deserialize(colorized == null ? "" : colorized));
  }

  /**
   * 确保未显式设置的文字装饰被重置为 {@code false}
   * <p>
   * 与 {@code JMenuEntry.ensureReset} 同源：{@code §r} 只把装饰设成 {@code NOT_SET}，
   * 而原版渲染物品名/悬浮文本时会把它当作「启用」，因此必须显式关掉。
   * 由于 Adventure 的样式对未设置的子节点是继承父级的，设置根组件的风格即可覆盖整棵树。
   */
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

  /** 界面标题：支持 & 颜色码与 PAPI */
  public static Component title(org.bukkit.entity.Player player, String raw) {
    String parsed = i18n.getPHparsed(player, raw == null ? "" : raw);
    return legacy(MCColor.parse(parsed));
  }
}
