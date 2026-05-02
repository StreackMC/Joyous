package com.github.streackmc.Joyous.SMenu;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.SConfig;

public class SMenuEntry {
  private final SConfig rootConfig;
  // 存储 Java 版按钮
  private final List<ButtonJE> javaButtons = new ArrayList<>();
  // 存储基岩版按钮：按顺序列表
  private final List<ButtonBE> bedrockButtons = new ArrayList<>();

  public static class ButtonJE {
    /** 行号（1-6） */
    public int x;
    /** 列号（1-9） */
    public int y;
    /** 物品堆 */
    public ItemStack item;
    /** 所需权限，null 表示无权限要求 */
    @Nullable
    public String perm;
    /** true = 缺失权限时显示（反选），false = 拥有权限时显示 */
    public boolean permUnhave;
    /** 点击动作 */
    public ButtonAction action;

    /**
     * @param slotKey 槽位键，如 "12"
     * @param raw     按钮原始数据 Map
     */
    @SuppressWarnings("unchecked")
    public ButtonJE(String slotKey, Map<String, Object> raw) {
      // 解析槽位为行列
      if (slotKey == null || slotKey.length() !=2) {
        throw new IllegalArgumentException("无效的槽位键: " + slotKey);
      }
      this.x = Integer.parseInt(slotKey.substring(0, 1));
      if (this.x < 1 || this.x > 6) {
        throw new IllegalArgumentException("行号必须在1-6之间: " + this.x);
      }
      this.y = Integer.parseInt(slotKey.substring(1, 2));
      if (this.y < 1 || this.y > 9) {
        throw new IllegalArgumentException("列号必须在1-9之间: " + this.y);
      }

      // 解析 display.id
      Object displayObj = raw.get("display");
      if (!(displayObj instanceof Map)) {
        throw new IllegalArgumentException("缺少 display 字段或类型错误");
      }
      Map<String, Object> display = (Map<String, Object>) displayObj;
      String itemId = (String) display.get("id");
      if (itemId == null || itemId.isEmpty()) {
        throw new IllegalArgumentException("display.id 不能为空");
      }
      Material material = Material.matchMaterial(itemId);
      if (material == null) {
        throw new IllegalArgumentException("未知物品ID: " + itemId);
      }
      this.item = new ItemStack(material);

      // dispaly的剩余部分
      ItemMeta meta = this.item.getItemMeta();
      if (meta != null) {

        // 处理附魔光效
        Boolean enchant = (Boolean) display.get("enchant");
        if (enchant != null && enchant) {
          meta.setEnchantmentGlintOverride(true);
        }

        // 处理 Tooltip（Lore）
        Object tooltipObj = display.get("tooltip");
        if (tooltipObj instanceof List) {
          List<?> tooltips = (List<?>) tooltipObj;
          if (!tooltips.isEmpty()) {
            meta.setItemName(String.valueOf(tooltips.getFirst()));
            tooltips.remove(0);
            List<String> lore = new ArrayList<>();
            for (Object line : tooltips) {
              lore.add(String.valueOf(line));
            }
            meta.setLore(lore);
          }
        }
        this.item.setItemMeta(meta);
      }

      // 解析权限
      String permRaw = (String) raw.get("perm");
      if (permRaw != null && !permRaw.isEmpty()) {
        if (permRaw.startsWith("!")) {
          this.permUnhave = true;
          this.perm = permRaw.substring(1);
          if (this.perm.isEmpty())
            this.perm = null;
        } else {
          this.permUnhave = false;
          this.perm = permRaw;
        }
      } else {
        this.permUnhave = false;
        this.perm = null;
      }

      // 解析动作
      String actionType = (String) raw.get("action");
      String param = (String) raw.get("param");
      this.action = new ButtonAction(actionType, param);
    }
  }

  public static class ButtonBE {
    /** 显示文本（支持颜色代码） */
    public String text;
    /** 图标路径（可为空） */
    @Nullable
    public String icon;
    /** 所需权限，null 表示无要求 */
    @Nullable
    public String perm;
    /** true = 缺失权限时显示，false = 拥有权限时显示 */
    public boolean permUnhave;
    /** 点击动作 */
    public ButtonAction action;

    public ButtonBE(Map<String, Object> raw) {
      // 解析 display
      Object displayObj = raw.get("display");
      if (!(displayObj instanceof Map)) {
        throw new IllegalArgumentException("缺少 display 字段或类型错误");
      }
      Map<String, Object> display = (Map<String, Object>) displayObj;
      String textVal = (String) display.get("text");
      if (textVal == null || textVal.isEmpty()) {
        throw new IllegalArgumentException("display.text 不能为空");
      }
      this.text = textVal;
      this.icon = (String) display.get("icon");

      // 解析权限（同 Java 版规则）
      String permRaw = (String) raw.get("perm");
      if (permRaw != null && !permRaw.isEmpty()) {
        if (permRaw.startsWith("!")) {
          this.permUnhave = true;
          this.perm = permRaw.substring(1);
          if (this.perm.isEmpty())
            this.perm = null;
        } else {
          this.permUnhave = false;
          this.perm = permRaw;
        }
      } else {
        this.permUnhave = false;
        this.perm = null;
      }

      // 解析动作
      String actionType = (String) raw.get("action");
      String param = (String) raw.get("param");
      this.action = new ButtonAction(actionType, param);
    }
  }

  public static class ButtonAction {
    public final String type;
    public final String param;

    public ButtonAction(String t, String p) {
      String upper = Objects.requireNonNullElse(t, "").toUpperCase();
      switch (upper) {
        case "MENU":
          this.type = TYPES.MENU;
          break;
        case "CMD":
          this.type = TYPES.CMD;
          break;
        case "OP":
          this.type = TYPES.CMD_OP;
          break;
        case "CON":
          this.type = TYPES.CMD_CONSOLE;
          break;
        case "URL":
          this.type = TYPES.URL;
          break;
        default:
          this.type = TYPES.NONE;
          break;
      }
      this.param = (p == null) ? "" : p;
    }

    public static final class TYPES {
      public static final String MENU = "MENU";
      public static final String CMD = "CMD";
      public static final String CMD_OP = "OP";
      public static final String CMD_CONSOLE = "CON";
      public static final String URL = "URL";
      public static final String NONE = "NONE";
    }
  }

  public static String resolvePath(String p) {
    try {
      Path menu = SMenuMain.MENU_PATH.resolve(p + ".jmenu");
      //  无需保证文件存在，文件不存在时SConfig自动回退一个空的文件，之后解析时自动判定无效。
      if (!menu.toAbsolutePath().startsWith(SMenuMain.MENU_PATH.toAbsolutePath())) {
        throw new SecurityException("Invalid path: " + menu.toString());
      }
      return menu.toString();
    } catch (Exception e) {
      return SMenuMain.MENU_PATH.resolve("404.jmenu").toString();
    }
  }

  /**
   * 加载并解析菜单文件
   * 
   * @param path 菜单相对路径（不含 .jmenu 后缀）
   * @throws SecurityException 路径非法
   */
  public SMenuEntry(String path) throws SecurityException {
    rootConfig = new SConfig(resolvePath(path), SConfig.TYPES.JSONC);

    // 解析 Java 版按钮
    Map<String, Object> javaButtonsSection = rootConfig.getSection("java-buttons");
    if (javaButtonsSection != null) {
      for (Map.Entry<String, Object> entry : javaButtonsSection.entrySet()) {
        String slotKey = entry.getKey();
        Object value = entry.getValue();
        if (!(value instanceof Map)) {
          continue; // 格式错误，跳过
        }
        try {
          ButtonJE btn = new ButtonJE(slotKey, (Map<String, Object>) value);
          javaButtons.add(btn);
        } catch (Exception e) {
          logger.warning("打开菜单[" + path + "]时无法解析Java版按钮[" + slotKey + "]:" + e.getMessage(), e);
        }
      }
    }

    // 解析基岩版按钮（有序数组）
    List<Object> bedrockList = rootConfig.getList("bedrock-buttons");
    if (bedrockList != null) {
      for (Object obj : bedrockList) {
        if (!(obj instanceof Map)) {
          continue;
        }
        try {
          ButtonBE btn = new ButtonBE((Map<String, Object>) obj);
          bedrockButtons.add(btn);
        } catch (Exception e) {
          logger.warning("打开菜单[" + path + "]时无法解析基岩版按钮:" + e.getMessage(), e);
        }
      }
    }
  }

  // ========== 提供给外部的数据访问接口 ==========
  /**
   * 获取所有 Java 版按钮
   */
  public List<ButtonJE> getJavaButtons() {
    return Collections.unmodifiableList(javaButtons);
  }

  /**
   * 获取所有基岩版按钮，按配置文件顺序
   */
  public List<ButtonBE> getBedrockButtons() {
    return Collections.unmodifiableList(bedrockButtons);
  }

  /**
   * 获取原始 SConfig 实例（如需高级操作）
   */
  public SConfig getRootConfig() {
    return rootConfig;
  }
}