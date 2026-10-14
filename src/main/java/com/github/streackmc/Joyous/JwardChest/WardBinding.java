package com.github.streackmc.Joyous.JwardChest;

import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.github.streackmc.Joyous.jlogger;

/**
 * 方块绑定关系（区块 PDC 持久化）
 * <p>
 * 方块本身没有 PDC，绑定关系写在所在<b>区块</b>的 PDC 里，键名包含方块坐标，
 * 值是该容器设置的唯一 UUID。区块 PDC 随区块一起存盘，因此天然持久化。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardBinding {

  private WardBinding() {
  }

  /** 生成该方块在区块 PDC 中的键名 */
  private static NamespacedKey keyOf(Block block) {
    String name = "bind_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    return new NamespacedKey(JwardChestMain.plugin(), name);
  }

  private static PersistentDataContainer containerOf(Block block) {
    return block.getChunk().getPersistentDataContainer();
  }

  /**
   * 绑定：把容器 UUID 写入该方块所在区块的 PDC
   *
   * @param block 目标方块
   * @param id    容器 UUID
   */
  public static void write(Block block, UUID id) {
    containerOf(block).set(keyOf(block), PersistentDataType.STRING, id.toString());
  }

  /**
   * 读取绑定
   *
   * @param block 目标方块
   * @return 容器 UUID；未绑定或数据损坏时返回 null
   */
  @Nullable
  public static UUID read(Block block) {
    try {
      String raw = containerOf(block).get(keyOf(block), PersistentDataType.STRING);
      if (raw == null || raw.isBlank())
        return null;
      return UUID.fromString(raw);
    } catch (Exception e) {
      jlogger.debug("JwardChest 读取方块绑定失败 @ %s：%s", block.getLocation(), e.getLocalizedMessage());
      return null;
    }
  }

  /**
   * 解除绑定
   *
   * @param block 目标方块
   * @return 之前是否存在绑定
   */
  public static boolean remove(Block block) {
    if (read(block) == null)
      return false;
    containerOf(block).remove(keyOf(block));
    return true;
  }

  /** 是否已绑定 */
  public static boolean isBound(Block block) {
    return read(block) != null;
  }
}
