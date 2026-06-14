package com.github.streackmc.Joyous.SMenu;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * SMenu 菜单数据实体
 * <p>
 * 解析并缓存单个 {@code .jmenu} 菜单文件的内容，提供 Java/基岩版按钮列表。
 * 菜单文件使用 JSON 格式，详细格式参见 {@code SMenu.default.json}。
 * 支持 404 回退：当请求的菜单不存在时，自动尝试 {@code 404.jmenu}。
 *
 * @author kdxiaoyi
 * @since 0.0.1
 */
public class SMenuEntry {

  /** 合法路径字符正则 */
  private static final Pattern PATH_PATTERN = Pattern.compile("[a-zA-Z0-9.]*");

  /** 菜单路径（缓存键） */
  private final String menuPath;
  /** 原始 JSON 根对象 */
  private final JSONObject root;
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

    // 2. 解析 JSON
    try (FileReader reader = new FileReader(filePath.toFile())) {
      JSONParser parser = new JSONParser();
      this.root = (JSONObject) parser.parse(reader);
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析菜单 " + menuPath + "：" + e.getLocalizedMessage(), e);
    }

    // 3. 解析基本属性
    Object titleObj = root.get("title");
    this.title = MCColor.parse(titleObj != null ? String.valueOf(titleObj) : "菜单");
    Object linesObj = root.get("lines");
    int rawLines = linesObj instanceof Number n ? n.intValue() : 3;
    if (rawLines < 1 || rawLines > 6) rawLines = 3;
    this.lines = rawLines;

    // 4. 解析 Java 版按钮
    parseJavaButtons();

    // 5. 解析基岩版按钮
    parseBedrockButtons();
  }

  // ──────────────────────────────────────────────
  // JSON 解析
  // ──────────────────────────────────────────────

  /** 槽位键格式校验：行号+列号，各 1 位数字 */
  private static final java.util.regex.Pattern SLOT_PATTERN =
      java.util.regex.Pattern.compile("^(\\d)(\\d)$");

  @SuppressWarnings("unchecked")
  private void parseJavaButtons() {
    JSONObject buttons = (JSONObject) root.get("java-buttons");
    if (buttons == null) return;

    for (Object keyObj : buttons.keySet()) {
      String slotKey = (String) keyObj;
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

      JSONObject btn = (JSONObject) buttons.get(slotKey);
      if (btn == null) continue;

      JSONObject display = (JSONObject) btn.get("display");
      if (display == null) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 缺少 display 配置", menuPath, slotKey);
        continue;
      }

      String itemId = (String) display.get("id");
      if (itemId == null || itemId.isBlank()) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 缺少物品 ID", menuPath, slotKey);
        continue;
      }

      Material material = Material.getMaterial(itemId.toUpperCase());
      if (material == null) {
        logger.warn("菜单 [%s] 中的按钮 [%s] 使用了未知物品 [%s]", menuPath, slotKey, itemId);
        continue;
      }
      ItemStack item = new ItemStack(material);

      // 附魔光效
      boolean enchant = Boolean.TRUE.equals(display.get("enchant"));
      if (enchant) {
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
      }

      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
        var tooltipRaw = (List<String>) display.get("tooltip");
        if (tooltipRaw != null && !tooltipRaw.isEmpty()) {
          var serializer = LegacyComponentSerializer.legacySection();
          // 首行为标题，其余为 Lore
          meta.displayName(serializer.deserialize(MCColor.parse(tooltipRaw.get(0))));
          if (tooltipRaw.size() > 1) {
            List<Component> lore = new ArrayList<>();
            for (int i = 1; i < tooltipRaw.size(); i++) {
              lore.add(serializer.deserialize(MCColor.parse(tooltipRaw.get(i))));
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
      String perm = (String) btn.get("perm");
      boolean permUnhave = false;
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;

      // 动作
      String action = String.valueOf(btn.getOrDefault("action", ""));
      String param = (String) btn.get("param");

      javaButtons.add(new JavaButton(x, y, item, perm, permUnhave, action, param));
    }
  }

  @SuppressWarnings("unchecked")
  private void parseBedrockButtons() {
    JSONArray buttons = (JSONArray) root.get("bedrock-buttons");
    if (buttons == null) return;

    for (Object obj : buttons) {
      JSONObject btn = (JSONObject) obj;
      JSONObject display = (JSONObject) btn.get("display");
      if (display == null) continue;

      String text = (String) display.get("text");
      if (text == null || text.isBlank()) continue;

      String icon = (String) display.get("icon");

      // 权限
      String perm = (String) btn.get("perm");
      boolean permUnhave = false;
      if (perm != null && perm.startsWith("!")) {
        permUnhave = true;
        perm = perm.substring(1);
      }
      if (perm != null && perm.isBlank()) perm = null;

      String action = String.valueOf(btn.getOrDefault("action", ""));
      String param = (String) btn.get("param");

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

  /** 获取原始 JSON 根对象 */
  public JSONObject getRootConfig() { return root; }

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
