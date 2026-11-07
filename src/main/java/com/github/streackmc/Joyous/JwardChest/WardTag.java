package com.github.streackmc.Joyous.JwardChest;

import javax.annotation.Nullable;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.Joyous;

/**
 * 物品上的「战利品标记」
 * <p>
 * 本模块抽出的每一条结果都会在物品的持久化数据组件（{@code custom_data}）里写一个
 * {@code joyous:jc_rarity}，值是珍惜度 id。这样<b>品质跟着物品走</b>，而不是靠「物品在哪个槽位」
 * 这种位置映射：
 * <ul>
 *   <li>玩家把结果搬到别的容器、丢在地上、塞进自己的箱子，品质都不会丢；</li>
 *   <li>容器里出现不是本模块产出的物品时，读不到标记，就统一按
 *       {@link WardRarity#fallback()} 处理（配置项 {@code JwardChest.fallback-rarity}）；</li>
 *   <li>搜索时长与紧急配色只需要问「这一格现在装的是什么」，不需要再维护一张
 *       「第几条结果在哪个槽」的平行表。</li>
 * </ul>
 * 标记只是<b>建议值</b>而不是授权凭证：玩家可以复制或搬运带标记的物品，所以读取方一律容忍
 * 「id 认不出来」并回落到 fallback，绝不因为标记异常而中断流程。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardTag {

  /** 物品上的品质标记键 */
  public static final String RARITY_KEY = "jc_rarity";

  private WardTag() {
  }

  private static NamespacedKey rarityKey() {
    return new NamespacedKey(Joyous.plugin, RARITY_KEY);
  }

  /**
   * 把珍惜度写进物品
   *
   * @param item   目标物品（会被原地修改并返回）
   * @param rarity 珍惜度，为 null 时原样返回
   * @return 同一个物品实例，便于链式调用
   */
  public static ItemStack stamp(ItemStack item, @Nullable WardRarity rarity) {
    if (item == null || item.getType().isAir() || rarity == null) {
      return item;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return item;
    }
    meta.getPersistentDataContainer().set(rarityKey(), PersistentDataType.STRING, rarity.id());
    item.setItemMeta(meta);
    return item;
  }

  /**
   * 读物品上的珍惜度
   * <p>
   * 读不到标记、或者标记里的 id 已经不在当前声明里时，返回 {@link WardRarity#fallback()}。
   *
   * @param item 待读取的物品，可为 null
   * @return 珍惜度；永不为 null
   */
  public static WardRarity rarityOf(@Nullable ItemStack item) {
    WardRarity tagged = taggedRarity(item);
    return tagged == null ? WardRarity.fallback() : tagged;
  }

  /**
   * 严格读取物品上的珍惜度（不做兜底）
   *
   * @param item 待读取的物品，可为 null
   * @return 物品上标注的珍惜度；没有标记/无法识别返回 null
   */
  @Nullable
  public static WardRarity taggedRarity(@Nullable ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return null;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return null;
    }
    String id = meta.getPersistentDataContainer().get(rarityKey(), PersistentDataType.STRING);
    return WardRarity.find(id);
  }

  /**
   * 是否带有本模块写入的品质标记 —— <b>只认「标记在不在」，不管 id 还认不认得</b>
   * <p>
   * 这是「这件物品是不是本模块产出的」的判据（配置 {@code session.allow-foreign-place}
   * 关掉后，不带的物品不许放进容器）。
   * <p>
   * 之所以不去校验 id 是否还在 <b>rarities</b> 声明里：管理员删掉某一档之后，之前抽出去的那些
   * 物品仍然是「我们产出的」，不该突然变成外来物。至于搜索时长，{@link #rarityOf(ItemStack)}
   * 读不到 id 时会自动回落到 {@link WardRarity#fallback()}，两者互不影响。
   *
   * @param item 待判定的物品，可为 null
   */
  public static boolean hasTag(@Nullable ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return false;
    }
    String id = meta.getPersistentDataContainer().get(rarityKey(), PersistentDataType.STRING);
    return id != null && !id.isBlank();
  }
}
