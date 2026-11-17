package com.github.streackmc.Joyous.Common.limiter;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * {@code custom_data} 读取的隔离层——Item-NBT-API 是**可选**运行时依赖。
 *
 * <p>为什么单独一层：{@code de.tr7zw.nbtapi.*} 的类在 NBTAPI 插件缺席时并不存在于
 * classpath 上，若监听器直接引用会在类解析阶段抛 {@link NoClassDefFoundError}，
 * 连带整个事件注册失败。这里把引用收敛到一处，并且只在检测通过后才进入。
 *
 * <p>NBT-API 可用时走 {@code NBT.get(ItemStack, ...)}：1.20.5+ 起它访问的就是
 * {@code custom_data} 组件本身，能拿到**顶层键**（数据包 / {@code /give} 写入的那一层），
 * 同时把 {@code PublicBukkitValues} 的子键一并展开——后者正是 Paper API
 * {@code ItemMeta#getPersistentDataContainer()} 的落点。两者合一即为完整覆盖。
 *
 * <p>不可用时返回 {@code null}，由调用方回退到 Paper 的 PDC 视图（只覆盖
 * {@code PublicBukkitValues}）。
 *
 * @author Neonai
 * @author kdxiaoyi
 * @since 0.4.1
 */
public final class NbtCustomData {

  private static final String PLUGIN_NAME = "NBTAPI";

  /** Paper API 在 custom_data 里存放 PDC 的固定键名。 */
  static final String BUKKIT_VALUES_KEY = "PublicBukkitValues";

  private static final boolean AVAILABLE = detect();

  private NbtCustomData() {
  }

  private static boolean detect() {
    try {
      if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
        return false;
      }
      Class.forName("de.tr7zw.nbtapi.NBT");
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** NBT-API 是否可用（决定 custom_data 能否读到顶层键）。 */
  public static boolean isAvailable() {
    return AVAILABLE;
  }

  /**
   * 物品 {@code custom_data} 里的全部键：顶层键 + {@code PublicBukkitValues} 下的键。
   *
   * @return 键集合（形如 {@code namespace:key}）；NBT-API 不可用或读取失败时返回
   *         {@code null}，表示「请回退到 PDC」
   */
  static Set<String> keys(ItemStack stack) {
    if (!AVAILABLE || stack == null || stack.getType().isAir()) {
      return null;
    }
    try {
      return keysViaNbt(stack);
    } catch (Throwable ignored) {
      // 运行期异常不该让一次点击崩掉整个监听器：降级到 PDC 即可
      return null;
    }
  }

  private static Set<String> keysViaNbt(ItemStack stack) {
    // 全限定名：确保 NBT 类只在真正调用时才被解析
    return de.tr7zw.nbtapi.NBT.get(stack, nbt -> {
      Set<String> keys = new HashSet<>();
      if (nbt == null || !nbt.hasNBTData()) {
        return keys;
      }
      // 1.20.5+：这里的 nbt 就是 custom_data 组件的内容
      keys.addAll(nbt.getKeys());
      var bukkitValues = nbt.getCompound(BUKKIT_VALUES_KEY);
      if (bukkitValues != null) {
        keys.addAll(bukkitValues.getKeys());
      }
      return keys;
    });
  }
}
