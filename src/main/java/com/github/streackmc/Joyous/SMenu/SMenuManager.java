package com.github.streackmc.Joyous.SMenu;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;

import com.github.streackmc.Joyous.Joyous;

public class SMenuManager {
  public final long cacheTtlMillis;
  public final Map<String, SMenuEntry> MENU_CACHE = new HashMap<>();
  public final Map<String, Long> MENU_CACHE_EXPIRE = new HashMap<>();

 private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Plugin plugin;
  
  public SMenuManager(Plugin plugin, long ttl) {
    this.plugin = plugin;
    this.cacheTtlMillis = ttl;
    startAsyncCleanupTask();
  }

  private void startAsyncCleanupTask() {
    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
      long now = System.currentTimeMillis();

      lock.writeLock().lock(); // 写锁
      try {
        Iterator<Map.Entry<String, Long>> iterator = MENU_CACHE_EXPIRE.entrySet().iterator();
        while (iterator.hasNext()) {
          Map.Entry<String, Long> entry = iterator.next();
          if (now >= entry.getValue()) {
            MENU_CACHE.remove(entry.getKey());
            iterator.remove();
          }
        }
      } finally {
        lock.writeLock().unlock();
      }
    }, 20L * 60, 20L * 60);
  }

  /**
   * 为玩家打开菜单
   * @throws IllegalArgumentException 菜单格式错误
   */
  public void openMenuFor(String menuPath, Player player) throws IllegalArgumentException {
    String menu = SMenuEntry.resolvePath(menuPath);
    SMenuEntry menuData;
    lock.readLock().lock();
    try {
      SMenuEntry entry = MENU_CACHE.get(menu);
      if (entry != null && (System.currentTimeMillis() <= MENU_CACHE_EXPIRE.get(menu))) {
        // 存在缓存
        menuData = entry;
      } else {
        // 缓存过期或不存在
        MENU_CACHE.remove(menu);
        MENU_CACHE_EXPIRE.remove(menu);
        menuData = new SMenuEntry(menu);
        MENU_CACHE.put(menu, menuData);
        MENU_CACHE_EXPIRE.put(menu, System.currentTimeMillis() + cacheTtlMillis);
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } finally {
      lock.readLock().unlock();
    }

    // 现在拿到了菜单，根据玩家类型打开。
    if (isBedrockPlayer(player)) {
      
    } else {
      // 构造箱子 UI
      int lines = menuData.getRootConfig().getInt("lines", 3);
      if (lines < 1 || lines > 6) {
        lines = 3;
      }
      Inventory inventory = Bukkit.createInventory(null, lines * 9, "");

      // 放置按钮
      menuData.getJavaButtons().forEach((btn) -> {
        int slot = (btn.x - 1) * 9 + btn.y;
        if (
          btn.perm == null
          || btn.perm.isBlank()
          || (player.hasPermission(btn.perm) && !btn.permUnhave)
          || (!player.hasPermission(btn.perm) && btn.permUnhave)
        ) {
          inventory.setItem(slot, btn.item);
        }
      });

      // 打开这个箱子
      player.openInventory(inventory);
      //TODO: 监听点击事件，执行按钮动作
    }
  }

  public static boolean isBedrockPlayer(Player p) {
    if (Joyous.pluginManager.isPluginEnabled("Geyser-Spigot") && Joyous.pluginManager.isPluginEnabled("floodgate")) {
      return GeyserApi.api().isBedrockPlayer(p.getUniqueId());
    } else {
      return false;
    }
  }
}
