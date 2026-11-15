package com.github.streackmc.Joyous.JSell.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;

/**
 * 可售物品的分组与它的两个权限
 * <p>
 * 分组是条目上的一个标签（{@link SellEntry#group()}），没有单独的「组对象」——
 * 组由条目派生，所以删光某组的条目，这个组就自然消失（存档里对应的
 * {@code models/JSell/groups/<组名>.yml} 也会被删掉），不需要额外的清理逻辑。
 *
 * <h3>权限</h3>
 * 每个组两个节点，命名遵循 {@code joyous.<model>.<subaction>}：
 * <ul>
 *   <li>{@code joyous.jsell.group.<组名>.sellable} —— 能不能<b>卖</b>这一组的物品
 *       （拿不到就当它不可售：不计价、不结算、界面上标注不可售）；</li>
 *   <li>{@code joyous.jsell.group.<组名>.viewable} —— 能不能在<b>总览</b>里看到这一组。</li>
 * </ul>
 * 两者互相独立：可以「看得到但卖不掉」（展示用），也可以「卖得掉但总览里不列」（隐藏入口）。
 * 默认都是 {@code true}，否则升级后所有玩家会突然发现自己卖不了东西。
 *
 * @author Neonai
 * @since 0.4.0
 */
public final class SellGroups {

  /** 「全部组」的筛选哨兵，不是一个真实的组名 */
  public static final String ALL = "all";
  /** 没有指定分组时的归属 */
  public static final String DEFAULT = "default";

  /** 组名允许的字符与长度 */
  private static final String LEGAL = "[^a-z0-9_-]";
  /** 已经注册过的组名（避免重复 addPermission） */
  private static final Set<String> REGISTERED = new LinkedHashSet<>();

  private SellGroups() {
  }

  /**
   * 规范化组名
   * <p>
   * 组名会进权限节点，所以只允许小写字母、数字、下划线与连字符；其余字符直接丢弃。
   * 空串、{@code all}（筛选哨兵）与非法字符清洗后为空的名字，一律回落到 {@link #DEFAULT}。
   */
  public static String normalize(@Nullable String raw) {
    if (raw == null) {
      return DEFAULT;
    }
    String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll(LEGAL, "");
    if (cleaned.isEmpty() || cleaned.equals(ALL)) {
      return DEFAULT;
    }
    return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
  }

  /** 是否是一个可用的组名（用于聊天栏输入校验） */
  public static boolean isValid(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll(LEGAL, "");
    return !cleaned.isEmpty() && !cleaned.equals(ALL) && cleaned.equals(raw.trim().toLowerCase(Locale.ROOT));
  }

  /** 某个组某个动作的权限节点 */
  public static String node(String group, String action) {
    return "joyous.jsell.group." + normalize(group) + "." + action;
  }

  public static String sellableNode(String group) {
    return node(group, "sellable");
  }

  public static String viewableNode(String group) {
    return node(group, "viewable");
  }

  /**
   * 注册这些组的两个权限节点
   * <p>
   * 组的出现是动态的（管理员随时能改出新的组名），所以不能只在启用阶段注册一次；
   * 每次载入/新增条目后都过一遍，已经注册过的会被跳过。
   */
  public static void register(Collection<String> groups) {
    for (String group : groups) {
      String normalized = normalize(group);
      if (!REGISTERED.add(normalized)) {
        continue;
      }
      Joyous.addPermissions(
          PermDef.all(sellableNode(normalized), "出售「" + normalized + "」分组的物品"),
          PermDef.all(viewableNode(normalized), "在总览中查看「" + normalized + "」分组"));
    }
  }

  /** 该玩家能否卖出这一组的物品 */
  public static boolean canSell(@Nullable Player player, @Nullable String group) {
    return player == null || player.hasPermission(sellableNode(group == null ? DEFAULT : group));
  }

  /** 该玩家能否在总览里看到这一组 */
  public static boolean canView(@Nullable Player player, @Nullable String group) {
    return player == null || player.hasPermission(viewableNode(group == null ? DEFAULT : group));
  }

  /** 从一批条目里收集出现过的组名（升序，{@code default} 排在最前） */
  public static List<String> groupsOf(Collection<SellEntry> entries) {
    Set<String> names = new LinkedHashSet<>();
    for (SellEntry entry : entries) {
      names.add(entry.group());
    }
    List<String> result = new ArrayList<>(names.size());
    if (names.remove(DEFAULT)) {
      result.add(DEFAULT);
    }
    List<String> rest = new ArrayList<>(names);
    rest.sort(String::compareTo);
    result.addAll(rest);
    return result;
  }

  /**
   * 玩家在总览里能看到的组（含 {@link #ALL}）
   * <p>
   * 当前筛选值是 {@code all}，或某个组已经不存在时，回落到 {@code all}。
   */
  public static String resolveFilter(@Nullable Player player, @Nullable String requested,
      Collection<String> allGroups) {
    if (requested == null || requested.isBlank() || requested.equalsIgnoreCase(ALL)) {
      return ALL;
    }
    String normalized = normalize(requested);
    for (String group : allGroups) {
      if (group.equals(normalized) && canView(player, group)) {
        return group;
      }
    }
    return ALL;
  }
}
