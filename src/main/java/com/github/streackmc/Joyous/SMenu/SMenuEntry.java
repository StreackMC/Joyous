package com.github.streackmc.Joyous.SMenu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;
import com.github.streackmc.StreackLib.utils.SConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * SMenu 菜单数据实体
 * <p>
 * 解析并缓存单个 {@code .jmenu} 菜单文件的内容，提供 Java/基岩版按钮列表。
 * 菜单文件使用 JSON 格式（支持注释和尾随逗号），详细格式参见 {@code SMenu.default.json}。
 * 支持 404 回退：当请求的菜单不存在时，自动尝试 {@code 404.jmenu}。
 *
 * @author kdxiaoyi
 * @since 0.6.0
 */
public class SMenuEntry {

  /** 合法路径字符正则 */
  private static final Pattern PATH_PATTERN = Pattern.compile("[a-zA-Z0-9.]*");

  /** 菜单路径（缓存键） */
  private final String menuPath;
  /** Java 版按钮列表 */
  private final List<JavaButton> javaButtons = new ArrayList<>();
  /** 基岩版按钮列表 */
  private final List<BedrockButton> bedrockButtons = new ArrayList<>();
  /** 菜单标题（已解析颜色） */
  private final String title;
  /** 菜单行数（仅 Java 版，1-6） */
  private final int lines;

  /**
   * 加载并解析一个菜单文件
   *
   * @param menuPath 菜单路径（相对于菜单目录，不含 {@code .jmenu} 后缀）
   * @throws IllegalArgumentException 文件不存在或格式错误
   */
  public SMenuEntry(String menuPath) throws IllegalArgumentException {
    this.menuPath = menuPath;

    // 1. 定位菜单文件
    Path filePath = SMenuMain.MENU_PATH.resolve(menuPath + ".jmenu");
    if (Files.notExists(filePath)) {
      // 尝试 404 回退
      Path fallback = SMenuMain.MENU_PATH.resolve("404.jmenu");
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

    // 3. 解析基本属性（含 PAPI 占位符，无玩家上下文时仅解析服务端占位符）
    this.title = MCColor.parse(Joyous.i18n.getPHparsed(null, conf.getString("title", "菜单")));
    int rawLines = conf.getInt("lines", 3);
    if (rawLines < 1 || rawLines > 6) rawLines = 3;
    this.lines = rawLines;

    // 4. 解析 Java 版按钮
    parseJavaButtons(conf);

    // 5. 解析基岩版按钮
    parseBedrockButtons(conf);
  }

  // ──────────────────────────────────────────────
  // JSON 解析
  // ──────────────────────────────────────────────

  /** 槽位键格式校验：行号+列号，各 1 位数字 */
  private static final Pattern SLOT_PATTERN = Pattern.compile("^(\\d)(\\d)$");

  private void parseJavaButtons(SConfig conf) {
    Map<String, Object> buttons = conf.getSection("java-buttons");
    if (buttons == null || buttons.isEmpty()) return;

    for (var entry : buttons.entrySet()) {
      String slotKey = entry.getKey();
      var m = SLOT_PATTERN.matcher(slotKey);
      if (!m.matches()) {
        logger.warn("菜单 [%s] 中的按钮键 [%s] 格式无效，应为行号+列号（如 12）", menuPath, slotKey);
        continue;
      }
      int x = Integer.parseInt(m.group(1)); // 行（1-indexed）
      int y = Integer.parseInt(m.group(2)); // 列（1-indexed）
      if (x < 1 || x > 6 || y < 1 || y > 9) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 超出边界 (1-6行, 1-9列)", menuPath, slotKey);
        continue;
      }

      // 使用 SConfig 的嵌套路径读取按钮字段
      String btnPrefix = "java-buttons." + slotKey;
      String displayPrefix = btnPrefix + ".display";
      String dispStr = conf.getString(displayPrefix + ".id");
      if (dispStr == null || dispStr.isBlank()) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 缺少物品 ID", menuPath, slotKey);
        continue;
      }

      Material material = Material.getMaterial(dispStr.toUpperCase());
      if (material == null) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 使用了未知物品 [%s]", menuPath, slotKey, dispStr);
        continue;
      }
      ItemStack item = new ItemStack(material);

      // 附魔光效
      boolean enchant = conf.getBoolean(displayPrefix + ".enchant");
      if (enchant) {
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
      }

      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
        List<String> tooltipRaw = conf.getListOfString(displayPrefix + ".tooltip");
        if (tooltipRaw != null && !tooltipRaw.isEmpty()) {
          var serializer = LegacyComponentSerializer.legacySection();
          // 首行为标题，其余为 Lore；先解析 PAPI（无玩家时仅解析服务端占位符），再解析颜色代码
          String papiName = Joyous.i18n.getPHparsed(null, tooltipRaw.get(0));
          meta.displayName(ensureReset(
              serializer.deserialize(MCColor.parse("§r" + papiName))));
          if (tooltipRaw.size() > 1) {
            List<Component> lore = new ArrayList<>();
            for (int i = 1; i < tooltipRaw.size(); i++) {
              String text = tooltipRaw.get(i);
              if (text == null) continue;
              String papiLine = Joyous.i18n.getPHparsed(null, text);
              lore.add(ensureReset(
                  serializer.deserialize(MCColor.parse("§r" + papiLine))));
            }
            meta.lore(lore);
          }
        }
        if (enchant) {
          meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
      }

      // 权限
      String perm = conf.getString(btnPrefix + ".perm");
      boolean permUnhave = false;
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;

      // 动作
      String action = conf.getString(btnPrefix + ".action", "");
      String param = conf.getString(btnPrefix + ".param");

      javaButtons.add(new JavaButton(x, y, item, perm, permUnhave, action, param));
    }
  }

  @SuppressWarnings("unchecked")
  private void parseBedrockButtons(SConfig conf) {
    List<Object> buttons = conf.getList("bedrock-buttons");
    if (buttons == null || buttons.isEmpty()) return;

    for (Object obj : buttons) {
      if (!(obj instanceof Map btnMap)) continue;
      Object displayObj = btnMap.get("display");
      if (!(displayObj instanceof Map dispMap)) continue;

      String text = String.valueOf(dispMap.getOrDefault("text", ""));
      if (text.isBlank()) continue;

      String icon = dispMap.containsKey("icon") ? String.valueOf(dispMap.get("icon")) : null;

      // 权限
      String perm = btnMap.containsKey("perm") ? String.valueOf(btnMap.get("perm")) : null;
      boolean permUnhave = false;
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;

      String action = String.valueOf(btnMap.getOrDefault("action", ""));
      String param = btnMap.containsKey("param") ? String.valueOf(btnMap.get("param")) : null;

      bedrockButtons.add(new BedrockButton(text, icon, perm, permUnhave, action, param));
    }
  }

  // ──────────────────────────────────────────────
  // 内部记录
  // ──────────────────────────────────────────────

  /** Java 版按钮 */
  public record JavaButton(
      /** 行（1-indexed） */
      int x,
      /** 列（1-indexed） */
      int y,
      /** 按钮物品（已解析颜色、附魔） */
      ItemStack item,
      /** 权限节点，{@code null} 表示不校验 */
      String perm,
      /** 是否反向校验（{@code !} 前缀） */
      boolean permUnhave,
      /** 动作类型：{@code menu} / {@code cmd} / {@code op} / {@code con} / {@code url} / 其它 */
      String action,
      /** 动作参数 */
      String param
  ) {}

  /** 基岩版按钮 */
  public record BedrockButton(
      /** 按钮文字 */
      String text,
      /** 图标路径（资源包路径或 {@code url:...} 格式） */
      String icon,
      /** 权限节点，{@code null} 表示不校验 */
      String perm,
      /** 是否反向校验（{@code !} 前缀） */
      boolean permUnhave,
      /** 动作类型 */
      String action,
      /** 动作参数 */
      String param
  ) {}

  // ──────────────────────────────────────────────
  // 访问器
  // ──────────────────────────────────────────────

  /** 获取菜单标题（已解析颜色代码） */
  public String getTitle() { return title; }

  /** 获取 Java 版行数（1-6） */
  public int getLines() { return lines; }

  /** 获取菜单路径（缓存键） */
  public String getMenuPath() { return menuPath; }

  /** 获取 Java 版按钮列表 */
  public List<JavaButton> getJavaButtons() { return javaButtons; }

  /** 获取基岩版按钮列表 */
  public List<BedrockButton> getBedrockButtons() { return bedrockButtons; }

  /**
   * 解析一行菜单文本，确保未显式设置的文字装饰被重置为 {@code false}
   * <p>
   * {@link LegacyComponentSerializer#legacySection()} 遇到 {@code §r} 时仅将装饰设为
   * {@link TextDecoration.State#NOT_SET}，但某些场景下 {@code NOT_SET} 可能被渲染为已启用。
   * 此方法将 {@code NOT_SET} 的装饰显式设为 {@code FALSE} 以规避该问题。
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
