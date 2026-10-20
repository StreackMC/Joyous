package com.github.streackmc.Joyous.JMenu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * JMenu 菜单数据实体
 * <p>
 * 解析并缓存单个 {@code .jmenu} 菜单文件的内容，提供 Java/基岩版按钮列表。
 * 菜单文件使用 JSON 格式（支持注释和尾随逗号），详细格式参见 {@code JMenu.default.json}。
 * 支持 404 回退：当请求的菜单不存在时，自动尝试 {@code 404.jmenu}。
 * <p>
 * 当前适配<b>描述文件 v2</b>（{@code jmev: 2}）；v1 文件仍能原样解析（v2 只是加键）。
 * <ul>
 *   <li>{@code jmev} / {@code jme} —— 版本与文件类型标识，只做提示，不因此拒绝加载；</li>
 *   <li>{@code display.model} —— 物品模型映射（v2 新增），按服务端版本在
 *       {@code legacy_model_data}（≤1.21.3）与 {@code custom_model_data} + {@code item_model}
 *       （≥1.21.4）之间自动分流；</li>
 *   <li>{@code else} —— 权限不满足时画在原槽位上的回退按钮（v2 新增），
 *       其 {@code perm} / {@code else} 按描述文件忽略；</li>
 *   <li>动作不是 {@code menu/cmd/op/con/url} 时：Java 版点击无反应，
 *       基岩版渲染成文本段落而不是按钮。</li>
 * </ul>
 *
 * @author kdxiaoyi
 * @since 0.6.0
 */
public class JMenuEntry {

  /** 本实现支持的描述文件版本 */
  public static final int SUPPORTED_VERSION = 2;

  /** 合法路径字符正则 */
  private static final Pattern PATH_PATTERN = Pattern.compile("[a-zA-Z0-9.]*");

  /** 槽位键格式校验：行号+列号，各 1 位数字 */
  private static final Pattern SLOT_PATTERN = Pattern.compile("^(\\d)(\\d)$");

  /** 会被当作「动作」处理的取值；其余（含空）在 v2 里表示「点击无反应 / 文本段落」 */
  private static final Set<String> KNOWN_ACTIONS = Set.of("menu", "cmd", "op", "con", "url");

  /** 按钮物品上的「不可获得」标记（沿用既有约定） */
  private static final NamespacedKey UNOBTAINABLE_KEY = new NamespacedKey("streack", "unobtainable");

  /** 菜单路径（缓存键） */
  private final String menuPath;
  /** 描述文件版本（缺失按 1 处理） */
  private final int version;
  /** Java 版按钮列表 */
  private final List<JavaButton> javaButtons = new ArrayList<>();
  /** 基岩版按钮列表 */
  private final List<BedrockButton> bedrockButtons = new ArrayList<>();
  /** 菜单标题（原始文本，含 {@code &} 颜色码和可能的 PAPI 占位符） */
  private final String title;
  /** 菜单行数（仅 Java 版，1-6） */
  private final int lines;

  /**
   * 加载并解析一个菜单文件
   *
   * @param menuPath 菜单路径（相对于菜单目录，不含 {@code .jmenu} 后缀）
   * @throws IllegalArgumentException 文件不存在或格式错误
   */
  public JMenuEntry(String menuPath) throws IllegalArgumentException {
    this.menuPath = menuPath;

    // 1. 定位菜单文件
    Path filePath = JMenuMain.MENU_PATH.resolve(menuPath.replace(".", "/") + ".jmenu");
    if (Files.notExists(filePath)) {
      // 尝试 404 回退
      Path fallback = JMenuMain.MENU_PATH.resolve("404.jmenu");
      if (Files.notExists(fallback)) {
        throw new IllegalArgumentException("菜单 " + menuPath + " 不存在，且无 404 回退");
      }
      filePath = fallback;
    }

    // 2. 用 SConfig 加载（jsonc 支持注释和尾随逗号）
    SConfig conf;
    try {
      conf = new SConfig(filePath, "jsonc");
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析菜单 " + menuPath + "：" + e.getLocalizedMessage(), e);
    }

    // 3. 版本标识（只做提示，不因此拒绝加载）
    this.version = conf.getInt("jmev", 1);
    checkVersion(conf);

    // 4. 解析基本属性（标题存原始文本，PAPI 和颜色在打开菜单时逐玩家解析）
    this.title = conf.getString("title", "菜单");
    int rawLines = conf.getInt("lines", 3);
    if (rawLines < 1 || rawLines > 6) rawLines = 3;
    this.lines = rawLines;

    // 5. 解析 Java 版按钮
    parseJavaButtons(conf);

    // 6. 解析基岩版按钮
    parseBedrockButtons(conf);
  }

  /**
   * 校验描述文件版本标识
   * <p>
   * 不认识的版本不拒绝加载——v2 只是加键，旧实现读到多余的键会忽略；
   * 反过来「新版本文件 + 旧插件」也只是少了新特性，不该整张菜单打不开。
   */
  private void checkVersion(SConfig conf) {
    String kind = conf.getString("jme", "");
    if (kind != null && !kind.isBlank() && !"menu".equals(kind.trim())) {
      jlogger.warn("菜单 [%s] 的 jme 标识为 [%s]，本实现期望 menu，仍按菜单解析", menuPath, kind);
    }
    if (version > SUPPORTED_VERSION) {
      jlogger.warn("菜单 [%s] 的描述文件版本为 %s，高于本实现支持的 %s，未识别的键会被忽略",
          menuPath, version, SUPPORTED_VERSION);
    } else if (version < 1) {
      jlogger.debug("菜单 [%s] 的 jmev 为 %s，按 v1 处理", menuPath, version);
    }
  }

  // ──────────────────────────────────────────────
  // JSON 解析
  // ──────────────────────────────────────────────

  private void parseJavaButtons(SConfig conf) {
    Map<String, Object> buttons = conf.getSection("java-buttons");
    if (buttons == null || buttons.isEmpty()) return;

    int maxSlot = lines * 9;

    for (var entry : buttons.entrySet()) {
      String slotKey = entry.getKey();
      var m = SLOT_PATTERN.matcher(slotKey);
      if (!m.matches()) {
        jlogger.warn("菜单 [%s] 中的按钮键 [%s] 格式无效，应为行号+列号（如 12）", menuPath, slotKey);
        continue;
      }
      int x = Integer.parseInt(m.group(1)); // 行（1-indexed）
      int y = Integer.parseInt(m.group(2)); // 列（1-indexed）
      if (x < 1 || x > 6 || y < 1 || y > 9) {
        jlogger.warn("菜单 [%s] 中的按钮 [%s] 超出边界 (1-6行, 1-9列)", menuPath, slotKey);
        continue;
      }
      if ((x - 1) * 9 + (y - 1) >= maxSlot) {
        // 描述文件：「无论如何超出边界的按钮等同没写」
        jlogger.debug("菜单 [%s] 中的按钮 [%s] 超出本菜单的 %s 行界面，已忽略", menuPath, slotKey, lines);
        continue;
      }

      JavaButton btn = parseJavaButton(conf, "java-buttons." + slotKey, x, y, 0);
      if (btn != null) {
        javaButtons.add(btn);
      }
    }
  }

  /**
   * 解析一个 Java 版按钮
   *
   * @param prefix 该按钮在配置里的路径前缀
   * @param x      行（1-indexed）
   * @param y      列（1-indexed）
   * @param depth  嵌套深度：0 = 顶层，1 = {@code else} 里的回退按钮
   * @return 按钮；必填项缺失或非法时返回 null
   */
  @Nullable
  private JavaButton parseJavaButton(SConfig conf, String prefix, int x, int y, int depth) {
    String displayPrefix = prefix + ".display";

    // 物品材质（去掉命名空间）
    String rawId = conf.getString(displayPrefix + ".id");
    if (rawId == null || rawId.isBlank()) {
      jlogger.warn("菜单 [%s] 中的按钮 [%s] 缺少物品 ID", menuPath, prefix);
      return null;
    }
    Material material = resolveMaterial(rawId);
    if (material == null) {
      jlogger.warn("菜单 [%s] 中的按钮 [%s] 使用了未知物品 [%s]", menuPath, prefix, rawId);
      return null;
    }

    boolean enchant = conf.getBoolean(displayPrefix + ".enchant");
    List<String> tooltipRaw = conf.getListOfString(displayPrefix + ".tooltip");
    ModelSpec model = parseModel(conf, displayPrefix + ".model");

    // 权限：只有顶层生效；else 里的 perm 按描述文件「无效」
    String perm = null;
    boolean permUnhave = false;
    if (depth == 0) {
      perm = conf.getString(prefix + ".perm");
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;
    }

    // 动作
    String action = conf.getString(prefix + ".action", "");
    String param = conf.getString(prefix + ".param");

    // 回退按钮：只在顶层解析一层（else 里的 else 无效）
    JavaButton fallback = null;
    if (depth == 0) {
      Map<String, Object> elseSection = conf.getSection(prefix + ".else");
      if (elseSection != null && !elseSection.isEmpty()) {
        fallback = parseJavaButton(conf, prefix + ".else", x, y, depth + 1);
      }
    }

    return new JavaButton(x, y, material, enchant, tooltipRaw, model, perm, permUnhave, action,
        param, fallback);
  }

  private void parseBedrockButtons(SConfig conf) {
    List<Object> buttons = conf.getList("bedrock-buttons");
    if (buttons == null || buttons.isEmpty()) return;

    for (Object obj : buttons) {
      BedrockButton btn = parseBedrockButton(obj, 0);
      if (btn != null) {
        bedrockButtons.add(btn);
      }
    }
  }

  /**
   * 解析一个基岩版按钮
   *
   * @param raw   原始对象（从 {@code bedrock-buttons} 列表里取出的 map）
   * @param depth 嵌套深度：0 = 顶层，1 = {@code else} 里的回退按钮
   */
  @Nullable
  private BedrockButton parseBedrockButton(@Nullable Object raw, int depth) {
    if (!(raw instanceof Map<?, ?> btnMap)) {
      return null;
    }
    Object displayObj = btnMap.get("display");
    if (!(displayObj instanceof Map<?, ?> dispMap)) {
      return null;
    }

    String text = dispMap.containsKey("text") ? String.valueOf(dispMap.get("text")) : "";
    if (text.isBlank()) {
      return null;
    }
    String icon = dispMap.containsKey("icon") ? String.valueOf(dispMap.get("icon")) : null;

    // 权限：只有顶层生效
    String perm = null;
    boolean permUnhave = false;
    if (depth == 0) {
      perm = btnMap.containsKey("perm") ? String.valueOf(btnMap.get("perm")) : null;
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;
    }

    String action = btnMap.containsKey("action") ? String.valueOf(btnMap.get("action")) : "";
    String param = btnMap.containsKey("param") ? String.valueOf(btnMap.get("param")) : null;

    // 「动作不是菜单列出的那几种」→ 渲染为文本段落（v1 的说明里就是这么写的，只是此前未实现）
    boolean paragraph = !isKnownAction(action);

    BedrockButton fallback = null;
    if (depth == 0) {
      Object elseObj = btnMap.get("else");
      if (elseObj instanceof Map<?, ?> elseMap && !elseMap.isEmpty()) {
        fallback = parseBedrockButton(elseObj, depth + 1);
      }
    }

    return new BedrockButton(text, icon, perm, permUnhave, action, param, paragraph, fallback);
  }

  // ──────────────────────────────────────────────
  // 物品模型映射（v2）
  // ──────────────────────────────────────────────

  /**
   * 解析 {@code display.model}
   * <p>
   * 三个键各管一段版本：{@code legacy_model_data} 给 ≤1.21.3，
   * {@code custom_model_data} + {@code item_model} 给 ≥1.21.4。两套都写也不冲突，
   * 由 {@link ModelSpec#apply(ItemMeta)} 按当前服务端挑一套。
   */
  private ModelSpec parseModel(SConfig conf, String prefix) {
    Map<String, Object> section = conf.getSection(prefix, null);
    if (section == null || section.isEmpty()) {
      return ModelSpec.EMPTY;
    }

    int legacyModelData = conf.getInt(prefix + ".legacy_model_data", 0);
    NamespacedKey itemModel = parseModelKey(conf.getString(prefix + ".item_model"));

    List<Float> floats = new ArrayList<>();
    List<Boolean> flags = new ArrayList<>();
    List<String> strings = new ArrayList<>();
    List<Color> colors = new ArrayList<>();
    Map<String, Object> cmd = conf.getSection(prefix + ".custom_model_data", null);
    if (cmd != null && !cmd.isEmpty()) {
      collectFloats(cmd.get("floats"), floats);
      collectFlags(cmd.get("flags"), flags);
      collectStrings(cmd.get("strings"), strings);
      collectColors(cmd.get("colors"), colors);
    }

    if (legacyModelData <= 0 && itemModel == null && floats.isEmpty() && flags.isEmpty()
        && strings.isEmpty() && colors.isEmpty()) {
      return ModelSpec.EMPTY;
    }
    return new ModelSpec(legacyModelData, itemModel, List.copyOf(floats), List.copyOf(flags),
        List.copyOf(strings), List.copyOf(colors));
  }

  /** 解析 {@code item_model} 键；空串或非法返回 null */
  @Nullable
  private NamespacedKey parseModelKey(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    try {
      return value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
    } catch (Exception e) {
      jlogger.warn("菜单 [%s] 的 item_model [%s] 非法，已忽略：%s", menuPath, value,
          e.getLocalizedMessage());
      return null;
    }
  }

  /** 收集浮点列表（容忍单值与字符串） */
  private void collectFloats(@Nullable Object raw, List<Float> out) {
    for (Object one : asList(raw)) {
      if (one instanceof Number n) {
        out.add(n.floatValue());
      } else if (one != null) {
        try {
          out.add(Float.parseFloat(String.valueOf(one).trim()));
        } catch (NumberFormatException ignored) {
          jlogger.debug("菜单 [%s] 的 custom_model_data.floats 有无法解析的值 [%s]", menuPath, one);
        }
      }
    }
  }

  /** 收集布尔列表（容忍单值与 "true"/"false"） */
  private void collectFlags(@Nullable Object raw, List<Boolean> out) {
    for (Object one : asList(raw)) {
      if (one instanceof Boolean b) {
        out.add(b);
      } else if (one != null) {
        String v = String.valueOf(one).trim();
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
          out.add(Boolean.parseBoolean(v));
        } else {
          jlogger.debug("菜单 [%s] 的 custom_model_data.flags 有无法解析的值 [%s]", menuPath, one);
        }
      }
    }
  }

  /** 收集字符串列表（容忍单值） */
  private void collectStrings(@Nullable Object raw, List<String> out) {
    for (Object one : asList(raw)) {
      if (one != null) {
        out.add(String.valueOf(one));
      }
    }
  }

  /**
   * 收集颜色列表
   * <p>
   * 组件里的 colors 是 RGB 整数（如 {@code 16711680}）；这里额外容忍 {@code #RRGGBB}
   * 与 {@code 0xRRGGBB} 的写法，方便手写菜单。
   */
  private void collectColors(@Nullable Object raw, List<Color> out) {
    for (Object one : asList(raw)) {
      Integer rgb = toRgb(one);
      if (rgb == null) {
        jlogger.debug("菜单 [%s] 的 custom_model_data.colors 有无法解析的值 [%s]", menuPath, one);
        continue;
      }
      out.add(Color.fromRGB(rgb));
    }
  }

  @Nullable
  private static Integer toRgb(@Nullable Object raw) {
    if (raw instanceof Number n) {
      return n.intValue() & 0xFFFFFF;
    }
    if (raw == null) {
      return null;
    }
    String v = String.valueOf(raw).trim();
    try {
      if (v.startsWith("#")) {
        return Integer.parseInt(v.substring(1), 16) & 0xFFFFFF;
      }
      if (v.toLowerCase(Locale.ROOT).startsWith("0x")) {
        return Integer.parseInt(v.substring(2), 16) & 0xFFFFFF;
      }
      return Integer.parseInt(v) & 0xFFFFFF;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 把「单值或列表」统一成列表；null 视为空 */
  private static List<?> asList(@Nullable Object raw) {
    if (raw == null) {
      return List.of();
    }
    return raw instanceof List<?> list ? list : List.of(raw);
  }

  /** 解析物品材质：去掉命名空间后按大写匹配 */
  @Nullable
  private static Material resolveMaterial(String rawId) {
    String id = rawId.trim().toUpperCase(Locale.ROOT);
    int sep = id.indexOf(':');
    if (sep >= 0) {
      if (sep + 1 >= id.length()) {
        return null; // 只有命名空间
      }
      id = id.substring(sep + 1);
    }
    return Material.getMaterial(id);
  }

  /** 动作是否属于「已知动作」（其余值在 v2 里表示无反应 / 文本段落） */
  static boolean isKnownAction(@Nullable String action) {
    return action != null && KNOWN_ACTIONS.contains(action.trim().toLowerCase(Locale.ROOT));
  }

  // ──────────────────────────────────────────────
  // 内部记录
  // ──────────────────────────────────────────────

  /**
   * 物品模型映射（描述文件 v2 的 {@code display.model}）
   * <p>
   * 两套写法互不干扰：旧服务端只认 {@code legacyModelData}（整数 custom_model_data），
   * 新服务端只认 {@code itemModel} + {@code customModelData} 组件（1.21.4+ 的模型烘焙）。
   *
   * @param legacyModelData 整数 custom_model_data，{@code 0} 表示不设置（仅 ≤1.21.3 生效）
   * @param itemModel       {@code item_model} 组件键，{@code null} 表示不设置（仅 ≥1.21.4 生效）
   * @param floats          {@code custom_model_data.floats}
   * @param flags           {@code custom_model_data.flags}
   * @param strings         {@code custom_model_data.strings}
   * @param colors          {@code custom_model_data.colors}
   */
  public record ModelSpec(
      int legacyModelData,
      @Nullable NamespacedKey itemModel,
      List<Float> floats,
      List<Boolean> flags,
      List<String> strings,
      List<Color> colors) {

    /** 空映射（不写任何模型组件） */
    public static final ModelSpec EMPTY =
        new ModelSpec(0, null, List.of(), List.of(), List.of(), List.of());

    /** 是否声明了 custom_model_data 的任一子列表 */
    public boolean hasComponentData() {
      return !floats.isEmpty() || !flags.isEmpty() || !strings.isEmpty() || !colors.isEmpty();
    }

    /**
     * 按当前服务端版本把映射写进物品
     * <p>
     * ≥1.21.4 用 {@code item_model} 与 {@code custom_model_data} 组件；更早的版本只能写整数
     * custom_model_data（那套老机制在 1.21.4+ 依然存在，只是新模型烘焙不再读它）。
     * 版本判断来自 {@link JMenuMain#isNewerModelUsable()}。
     */
    public void apply(ItemMeta meta) {
      if (JMenuMain.isNewerModelUsable()) {
        if (itemModel != null) {
          meta.setItemModel(itemModel);
        }
        if (hasComponentData()) {
          CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
          if (cmd == null) {
            jlogger.warn("无法写入 custom_model_data 组件（服务端未提供），已忽略菜单里的模型数据");
          } else {
            if (!floats.isEmpty()) cmd.setFloats(floats);
            if (!flags.isEmpty()) cmd.setFlags(flags);
            if (!strings.isEmpty()) cmd.setStrings(strings);
            if (!colors.isEmpty()) cmd.setColors(colors);
            meta.setCustomModelDataComponent(cmd);
          }
        }
      } else if (legacyModelData > 0) {
        meta.setCustomModelData(legacyModelData);
      }
    }
  }

  /** Java 版按钮 */
  public record JavaButton(
      /** 行（1-indexed） */
      int x,
      /** 列（1-indexed） */
      int y,
      /** 按钮物品材质 */
      Material materialType,
      /** 是否有附魔光效 */
      boolean enchant,
      /** 原始 tooltip 文本（用于逐玩家 PAPI 解析） */
      List<String> tooltipRaw,
      /** 物品模型映射 */
      ModelSpec model,
      /** 权限节点，{@code null} 表示不校验 */
      @Nullable String perm,
      /** 是否反向校验（{@code !} 前缀） */
      boolean permUnhave,
      /** 动作类型：{@code menu} / {@code cmd} / {@code op} / {@code con} / {@code url} / 其它 */
      String action,
      /** 动作参数 */
      @Nullable String param,
      /** 权限不满足时显示的回退按钮（{@code else}），可为 null */
      @Nullable JavaButton fallback
  ) {
    /** 该按钮在箱子界面里的槽位（0 起） */
    public int slot() {
      return (x - 1) * 9 + (y - 1);
    }

    /** 该玩家实际看到的按钮：自己有权限就是自己，否则退回 {@code else}；两者都没有则为 {@code null} */
    @Nullable
    public JavaButton effectiveFor(@Nullable Player player) {
      if (JMenuManager.checkPermission(player, perm, permUnhave)) {
        return this;
      }
      return fallback;
    }

    /** 动作不是已知项时「点击无反应」（v2 语义），此时连菜单都不该关 */
    public boolean isNoop() {
      return !isKnownAction(action);
    }

    /**
     * 为指定玩家构建按钮物品（逐玩家解析 PAPI 占位符）
     *
     * @param player 目标玩家，为 {@code null} 时仅解析服务端占位符
     * @return 适用于该玩家的 ItemStack
     */
    public ItemStack buildItem(@Nullable Player player) {
      ItemStack newItem = new ItemStack(materialType);
      if (enchant) {
        newItem.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
      }
      ItemMeta meta = newItem.getItemMeta();
      if (meta != null) {
        if (enchant) {
          meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (tooltipRaw != null && !tooltipRaw.isEmpty()) {
          var serializer = LegacyComponentSerializer.legacySection();
          meta.displayName(ensureReset(serializer.deserialize(
              MCColor.parse("§r" + i18n.getPHparsed(player, tooltipRaw.get(0))))));
          if (tooltipRaw.size() > 1) {
            List<Component> lore = new ArrayList<>();
            for (int i = 1; i < tooltipRaw.size(); i++) {
              String text = tooltipRaw.get(i);
              if (text == null) {
                continue;
              }
              lore.add(ensureReset(serializer.deserialize(
                  MCColor.parse("§r" + i18n.getPHparsed(player, text)))));
            }
            meta.lore(lore);
          }
        }
        // 物品模型（v2）：按服务端版本写整数 CMD 或 item_model + 组件
        model.apply(meta);
        meta.getPersistentDataContainer().set(UNOBTAINABLE_KEY, PersistentDataType.STRING, "instant");
        newItem.setItemMeta(meta);
      }
      return newItem;
    }
  }

  /** 基岩版按钮 */
  public record BedrockButton(
      /** 按钮文字 */
      String text,
      /** 图标路径（资源包路径或 {@code url:...} 格式） */
      @Nullable String icon,
      /** 权限节点，{@code null} 表示不校验 */
      @Nullable String perm,
      /** 是否反向校验（{@code !} 前缀） */
      boolean permUnhave,
      /** 动作类型 */
      String action,
      /** 动作参数 */
      @Nullable String param,
      /** v2：动作不是已知项时应渲染为文本段落而不是按钮 */
      boolean paragraph,
      /** 权限不满足时显示的回退按钮（{@code else}），可为 null */
      @Nullable BedrockButton fallback
  ) {
    /** 该玩家实际看到的条目：自己有权限就是自己，否则退回 {@code else}；两者都没有则为 {@code null} */
    @Nullable
    public BedrockButton effectiveFor(@Nullable Player player) {
      if (JMenuManager.checkPermission(player, perm, permUnhave)) {
        return this;
      }
      return fallback;
    }
  }

  // ──────────────────────────────────────────────
  // 访问器
  // ──────────────────────────────────────────────

  /** 获取菜单标题（原始文本） */
  public String getTitle() {
    return title;
  }

  /** 获取 Java 版行数（1-6） */
  public int getLines() {
    return lines;
  }

  /** 获取菜单路径（缓存键） */
  public String getMenuPath() {
    return menuPath;
  }

  /** 描述文件版本（{@code jmev}，缺失按 1） */
  public int getVersion() {
    return version;
  }

  /** 获取 Java 版按钮列表 */
  public List<JavaButton> getJavaButtons() {
    return javaButtons;
  }

  /** 获取基岩版按钮列表 */
  public List<BedrockButton> getBedrockButtons() {
    return bedrockButtons;
  }

  /**
   * 解析一行菜单文本，确保未显式设置的文字装饰被重置为 {@code false}
   * <p>
   * {@link LegacyComponentSerializer#legacySection()} 遇到 {@code §r} 时仅将装饰设为
   * {@link TextDecoration.State#NOT_SET}，但某些场景下 {@code NOT_SET} 可能被渲染为已启用。
   * 此方法将 {@code NOT_SET} 的装饰显式设为 {@code FALSE} 以规避该问题。
   */
  static Component ensureReset(Component component) {
    var style = component.style();
    return component.style(s -> {
      for (TextDecoration dec : TextDecoration.values()) {
        if (style.decoration(dec) == TextDecoration.State.NOT_SET) {
          s.decoration(dec, false);
        }
      }
    });
  }

  // ──────────────────────────────────────────────
  // 路径解析工具
  // ──────────────────────────────────────────────

  /**
   * 解析并标准化菜单路径
   * <p>
   * 将用户输入的路径标准化：去除 {@code .jmenu} 扩展名、去除首尾斜杠、
   * 去除空白、验证字符合法性。合法字符仅限：{@code a-z A-Z 0-9 .}。
   *
   * @param input 用户输入的路径
   * @return 标准化后的路径；空字符串表示「关闭菜单」
   * @throws IllegalArgumentException 路径包含非法字符
   */
  public static String resolvePath(String input) throws IllegalArgumentException {
    if (input == null || input.isBlank()) {
      return "";
    }

    String path = input.strip();

    // 去除扩展名
    if (path.endsWith(".jmenu")) {
      path = path.substring(0, path.length() - 6);
    }

    // 去除首尾斜杠
    while (path.startsWith("/") || path.startsWith("\\")) {
      path = path.substring(1);
    }
    while (path.endsWith("/") || path.endsWith("\\")) {
      path = path.substring(0, path.length() - 1);
    }

    // 验证合法性
    if (!PATH_PATTERN.matcher(path).matches()) {
      throw new IllegalArgumentException("菜单路径只能含有 a-z A-Z 0-9 . 这些字符");
    }

    return path;
  }
}
