package com.github.streackmc.Joyous.JSell.data;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * 一个分组自己的设置（展示名 / unit 覆写 / economy 覆写）
 *
 * <h3>为什么要「覆写」而不是「必须有值」</h3>
 * 绝大多数分组根本不需要自己的单位与付款方式，它们就该跟着模块配置走。所以每个字段都是
 * <b>可空</b>的：{@code null} = 未设置 = 继承 {@code JSell.*} 里的全局值。
 * 界面上显示成「继承（当前：金币）」，管理员填 {@code unset} 就能回到继承状态。
 *
 * <h3>default 是特殊的</h3>
 * {@code default} 组代表「没有分组的那些物品」，它的值就是全局值本身。给它做覆写等于
 * 让全局配置出现两个真相，所以 {@link #canOverride()} 对它是 {@code false}，
 * 只有展示名可以设置（那是纯展示，不影响语义）。
 *
 * <h3>存在哪</h3>
 * 就存在该组自己的存档文件 {@code models/JSell/groups/<组名>.yml} 的顶层：
 * <pre>
 * display: "农作物"          # 展示名，不写就用组名
 * unit:
 *   name: "金币"
 * economy:
 *   commands:
 *   - "eco give %player% %amount%"
 *   decimals: 2
 * </pre>
 * 组名本身仍然是文件名，这三个键只是<b>附加信息</b>，删掉就等于全部回到继承。
 *
 * @author Neonai
 * @since 0.4.1
 */
public final class SellGroupSettings {

  private final String group;
  /** 展示名；{@code null} = 直接用组名 */
  private String display;
  /** unit 名称覆写；{@code null} = 继承全局 */
  private String unitName;
  /** 付款命令覆写；{@code null} = 继承全局 */
  private List<String> economyCommands;
  /** 金额小数位覆写；{@code null} = 继承全局 */
  private Integer decimals;

  public SellGroupSettings(String group) {
    this.group = SellGroups.normalize(group);
  }

  /** 规范化后的组名（= 文件名） */
  public String group() {
    return group;
  }

  /** 是否允许覆写 unit / economy：{@code default} 就是全局本身，不允许 */
  public boolean canOverride() {
    return !SellGroups.isDefault(group);
  }

  /** 是否有任何设置（全空 = 这个组不需要在存档里写设置段） */
  public boolean isEmpty() {
    return display == null && unitName == null && economyCommands == null && decimals == null;
  }

  // ──────────────────────────────────────────────
  // 展示名
  // ──────────────────────────────────────────────

  @Nullable
  public String display() {
    return display;
  }

  /** 设置展示名；空白 = 清除（回到显示组名） */
  public void display(@Nullable String value) {
    this.display = blankToNull(value);
  }

  // ──────────────────────────────────────────────
  // unit 覆写
  // ──────────────────────────────────────────────

  @Nullable
  public String unitName() {
    return canOverride() ? unitName : null;
  }

  /** 设置 unit 名称；空白 = 恢复继承 */
  public void unitName(@Nullable String value) {
    this.unitName = canOverride() ? blankToNull(value) : null;
  }

  // ──────────────────────────────────────────────
  // economy 覆写
  // ──────────────────────────────────────────────

  /** 付款命令覆写；{@code null} = 继承。返回的是只读副本 */
  @Nullable
  public List<String> economyCommands() {
    return canOverride() && economyCommands != null ? List.copyOf(economyCommands) : null;
  }

  /** 设置付款命令；{@code null} 或空列表 = 恢复继承（「不发钱」请用全局配置表达） */
  public void economyCommands(@Nullable List<String> value) {
    if (!canOverride()) {
      this.economyCommands = null;
      return;
    }
    List<String> cleaned = new ArrayList<>();
    if (value != null) {
      for (String line : value) {
        if (line != null && !line.isBlank()) {
          cleaned.add(line.trim());
        }
      }
    }
    this.economyCommands = cleaned.isEmpty() ? null : cleaned;
  }

  @Nullable
  public Integer decimals() {
    return canOverride() ? decimals : null;
  }

  /** 设置小数位；{@code null} = 恢复继承，取值范围与模块配置一致（0-6） */
  public void decimals(@Nullable Integer value) {
    this.decimals = canOverride() && value != null
        ? Math.max(0, Math.min(6, value))
        : null;
  }

  @Nullable
  private static String blankToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Override
  public String toString() {
    return "SellGroupSettings(" + group + ", display=" + display + ", unit=" + unitName
        + ", economy=" + (economyCommands == null ? "-" : economyCommands.size() + " 条")
        + ", decimals=" + decimals + ")";
  }
}
