package com.github.streackmc.Joyous.JSell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.datacomponent.DataComponentType;

/**
 * 可售物品的匹配模式
 * <p>
 * 管理员登记一条可售物品时，登记的是<b>一个模板</b>；玩家放进来的是「实例」。二者怎么算同一个物品，
 * 由这里决定：
 * <ul>
 *   <li>{@link #STRICT} —— 严格相等：类型与<b>全部</b>物品数据都必须一致，只是不比较堆叠数量。
 *       语义等同于 {@link ItemStack#isSimilar(ItemStack)}。</li>
 *   <li>{@link #CONTAINS} —— 至少包含：类型必须相同，且<b>模板上有的东西实例上也必须有</b>；
 *       实例多出来的部分不影响匹配。</li>
 * </ul>
 * 两种模式都<b>不比较堆叠数量</b>——数量只影响价值，不影响「是不是这个物品」。
 *
 * <h3>为什么不用 {@code ItemStack#serialize()}</h3>
 * 那是「存档用」的序列化结果，它的结构随版本变（1.21.5 起从 {@code type/meta} 变成了
 * {@code id/components}），拿它做子集比较等于把匹配逻辑绑在某个版本的存档格式上。
 * 这里改成两部分：
 * <ol>
 *   <li>「可叠加」的几项用手写规则：PDC 子集、Lore 前缀、附魔等级 ≥ 模板、自定义名一致；</li>
 *   <li>其余<b>所有</b>数据组件（属性、无法破坏、模型、药水、旗帜图案……）逐项比对——
 *       模板上有就必须一致，实例多出来的组件不管。这样新版本加了新组件也自动被覆盖，
 *       不需要我们跟着改代码。</li>
 * </ol>
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public enum SellMatch {

  /** 严格相等（忽略数量） */
  STRICT("strict"),
  /** 至少包含传入物品的 NBT */
  CONTAINS("contains");

  private final String id;

  SellMatch(String id) {
    this.id = id;
  }

  /** 配置/存档里用的名字 */
  public String id() {
    return id;
  }

  /** 解析名字；不认识时回落到 {@link #STRICT} */
  public static SellMatch parse(@Nullable String raw) {
    String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (SellMatch mode : values()) {
      if (mode.id.equals(value)) {
        return mode;
      }
    }
    return STRICT;
  }

  /** 全部名字（给聊天栏提示用） */
  public static List<String> ids() {
    List<String> list = new ArrayList<>(values().length);
    for (SellMatch mode : values()) {
      list.add(mode.id);
    }
    return list;
  }

  /** 下一个模式（详情界面里点击切换） */
  public SellMatch next() {
    SellMatch[] all = values();
    return all[(ordinal() + 1) % all.length];
  }

  /**
   * 判断实例是否命中模板
   *
   * @param item     玩家放进来/要出售的物品
   * @param template 管理员登记的模板
   * @return 是否为同一个物品
   */
  public boolean matches(@Nullable ItemStack item, @Nullable ItemStack template) {
    if (item == null || template == null) {
      return false;
    }
    if (item.getType().isAir() || template.getType().isAir()) {
      return false;
    }
    if (item.getType() != template.getType()) {
      return false;
    }
    if (this == STRICT) {
      // isSimilar 比较类型 + 物品数据，但**忽略堆叠数量**，正是「严格相等」想要的语义
      return item.isSimilar(template);
    }
    return contains(item, template);
  }

  /** 「至少包含」的判定：模板上出现的每一处物品数据都必须在实例上成立 */
  private static boolean contains(ItemStack item, ItemStack template) {
    ItemMeta required = template.getItemMeta();
    ItemMeta actual = item.getItemMeta();
    if (required == null) {
      // 模板没有 meta，实例有 meta 也算命中（「多出来」的部分不管）
      return true;
    }
    if (actual == null) {
      actual = new ItemStack(item.getType()).getItemMeta();
      if (actual == null) {
        return true;
      }
    }

    // ① 自定义名
    if (required.hasDisplayName() && !Objects.equals(required.displayName(), actual.displayName())) {
      return false;
    }

    // ② Lore：模板必须是指定行数的前缀（「物品说明里前几行一致」）
    List<net.kyori.adventure.text.Component> requiredLore = required.lore();
    if (requiredLore != null && !requiredLore.isEmpty()) {
      List<net.kyori.adventure.text.Component> actualLore = actual.lore();
      if (actualLore == null || actualLore.size() < requiredLore.size()) {
        return false;
      }
      for (int i = 0; i < requiredLore.size(); i++) {
        if (!Objects.equals(requiredLore.get(i), actualLore.get(i))) {
          return false;
        }
      }
    }

    // ③ 附魔：模板的每一项都要在实例上存在，且等级不低于模板
    for (Map.Entry<Enchantment, Integer> enchant : required.getEnchants().entrySet()) {
      if (actual.getEnchantLevel(enchant.getKey()) < enchant.getValue()) {
        return false;
      }
    }
    // ③b 附魔书：存起来的附魔走的是另一套 getter
    if (required instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta requiredBook) {
      for (Map.Entry<Enchantment, Integer> enchant : requiredBook.getStoredEnchants().entrySet()) {
        int level = (actual instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta actualBook)
            ? actualBook.getStoredEnchantLevel(enchant.getKey())
            : 0;
        if (level < enchant.getValue()) {
          return false;
        }
      }
    }

    // ④ PDC：模板的每个键都要在实例上有同名同值的项（实例可以多）
    PersistentDataContainer requiredPdc = required.getPersistentDataContainer();
    PersistentDataContainer actualPdc = actual.getPersistentDataContainer();
    for (org.bukkit.NamespacedKey key : requiredPdc.getKeys()) {
      if (!pdcValueEquals(requiredPdc, actualPdc, key)) {
        return false;
      }
    }

    // ⑤ 其余数据组件逐项比对（上面处理过的几项跳过）
    for (DataComponentType type : template.getDataTypes()) {
      if (handledSeparately(type)) {
        continue;
      }
      if (!(type instanceof DataComponentType.Valued<?> valued)) {
        // 无值组件（如「无法破坏」的开关位）：只要求「有没有」一致
        if (item.hasData(type) != template.hasData(type)) {
          return false;
        }
        continue;
      }
      Object wanted = template.getData(cast(valued));
      Object found = item.getData(cast(valued));
      if (!Objects.equals(wanted, found)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 已经在手写规则里处理过的组件
   * <p>
   * 按<b>组件键名</b>判断而不是拿常量比较：{@code CUSTOM_DATA} 这类常量并不总在 API 里暴露
   * （1.21.8 的 {@code DataComponentTypes} 就没有），但组件本身一定会出现在 {@code getDataTypes()} 里。
   */
  private static boolean handledSeparately(DataComponentType type) {
    String key = type.getKey().getKey();
    return switch (key) {
      case "custom_data", "custom_name", "lore", "enchantments", "stored_enchantments" -> true;
      default -> false;
    };
  }

  @SuppressWarnings("unchecked")
  private static DataComponentType.Valued<Object> cast(DataComponentType.Valued<?> type) {
    return (DataComponentType.Valued<Object>) type;
  }

  /**
   * 单个 PDC 键的值是否相等
   * <p>
   * Bukkit 没有提供「读出这个键是什么类型」的方法（{@code getType} 并不在 API 里），
   * 所以按<b>常见类型逐个试</b>：先用模板侧能读出来的那个类型作为判据，再拿同一类型去读实例侧。
   * 都不匹配时退化成「实例上只要有这个键就算命中」——嵌套容器就属于这种情况。
   */
  private static boolean pdcValueEquals(PersistentDataContainer required,
      PersistentDataContainer actual, org.bukkit.NamespacedKey key) {
    if (!actual.has(key)) {
      return false;
    }
    for (PersistentDataType<?, ?> type : PDC_TYPES) {
      if (!required.has(key, castType(type))) {
        continue;
      }
      Object wanted = required.get(key, castType(type));
      Object found = actual.get(key, castType(type));
      return Objects.equals(wanted, found);
    }
    if (required.has(key, PersistentDataType.TAG_CONTAINER)) {
      PersistentDataContainer a = actual.get(key, PersistentDataType.TAG_CONTAINER);
      PersistentDataContainer b = required.get(key, PersistentDataType.TAG_CONTAINER);
      if (a == null || b == null) {
        return false;
      }
      try {
        return java.util.Arrays.equals(b.serializeToBytes(), a.serializeToBytes());
      } catch (Exception e) {
        return false;
      }
    }
    // 未知类型：键存在即视为命中（宁宽也不把合法物品判成不可售）
    return true;
  }

  /** 会被逐个尝试的标量类型 */
  private static final java.util.List<PersistentDataType<?, ?>> PDC_TYPES = java.util.List.of(
      PersistentDataType.STRING, PersistentDataType.BYTE, PersistentDataType.SHORT,
      PersistentDataType.INTEGER, PersistentDataType.LONG, PersistentDataType.FLOAT,
      PersistentDataType.DOUBLE, PersistentDataType.BOOLEAN, PersistentDataType.BYTE_ARRAY,
      PersistentDataType.INTEGER_ARRAY, PersistentDataType.LONG_ARRAY);

  @SuppressWarnings("unchecked")
  private static <P, C> PersistentDataType<P, C> castType(PersistentDataType<?, ?> type) {
    return (PersistentDataType<P, C>) type;
  }
}
