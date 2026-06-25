package com.github.streackmc.Joyous.SMenu;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.MCColor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * SMenu 管理器
 * <p>
 * 负责菜单文件的缓存、菜单打开与动作执行。
 * 自动区分 Java 版（箱子 GUI）和基岩版（Floodgate 表单）。
 * 当服务器未安装 Floodgate 时，基岩版按钮部分自动跳过。
 * 线程安全：使用 {@link ReentrantReadWriteLock} 保护缓存。
 *
 * @author kdxiaoyi
 * @since 0.2.0
 */
public class SMenuManager {

  /** 缓存 TTL（毫秒） */
  public final long cacheTtlMillis;
  /** 菜单缓存 <路径, 解析后的菜单> */
  public final Map<String, SMenuEntry> MENU_CACHE = new HashMap<>();
  /** 缓存过期时间 <路径, 过期时间戳> */
  public final Map<String, Long> MENU_CACHE_EXPIRE = new HashMap<>();
  /** 玩家当前打开的菜单 <玩家UUID, 菜单> */
  public final Map<UUID, SMenuEntry> ACTIVE_MENUS = new HashMap<>();

  /** 读写锁 */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Plugin plugin;
  /** Floodgate 是否可用（惰性检测） */
  private volatile Boolean floodgateAvailable = null;

  public SMenuManager(Plugin plugin, long ttl) {
    this.plugin = plugin;
    this.cacheTtlMillis = ttl;
    startAsyncCleanupTask();
  }

  // ──────────────────────────────────────────────
  // 缓存维护
  // ──────────────────────────────────────────────

  /** 异步定时清理过期缓存（每 60s 执行一次） */
  private void startAsyncCleanupTask() {
    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
      long now = System.currentTimeMillis();
      lock.writeLock().lock();
      try {
        Iterator<Map.Entry<String, Long>> it = MENU_CACHE_EXPIRE.entrySet().iterator();
        while (it.hasNext()) {
          var entry = it.next();
          if (now >= entry.getValue()) {
            MENU_CACHE.remove(entry.getKey());
            it.remove();
          }
        }
      } finally {
        lock.writeLock().unlock();
      }
    }, 20L * 60, 20L * 60);
  }

  /**
   * 获取（或加载）一个菜单，优先使用缓存
   *
   * @param menuPath 标准化后的菜单路径
   * @return 解析后的菜单
   * @throws IllegalArgumentException 菜单不存在或格式错误
   */
  private SMenuEntry loadMenu(String menuPath) throws IllegalArgumentException {
    lock.readLock().lock();
    try {
      SMenuEntry cached = MENU_CACHE.get(menuPath);
      if (cached != null && System.currentTimeMillis() <= MENU_CACHE_EXPIRE.getOrDefault(menuPath, 0L)) {
        return cached;
      }
    } finally {
      lock.readLock().unlock();
    }

    // 缓存过期或不存在，重新加载
    SMenuEntry fresh = new SMenuEntry(menuPath);
    lock.writeLock().lock();
    try {
      MENU_CACHE.put(menuPath, fresh);
      MENU_CACHE_EXPIRE.put(menuPath, System.currentTimeMillis() + cacheTtlMillis);
    } finally {
      lock.writeLock().unlock();
    }
    return fresh;
  }

  /**
   * 强制刷新指定菜单的缓存
   *
   * @param menuPath 标准化后的菜单路径
   */
  public void invalidateCache(String menuPath) {
    lock.writeLock().lock();
    try {
      MENU_CACHE.remove(menuPath);
      MENU_CACHE_EXPIRE.remove(menuPath);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** 清空所有缓存 */
  public void invalidateAllCache() {
    lock.writeLock().lock();
    try {
      MENU_CACHE.clear();
      MENU_CACHE_EXPIRE.clear();
    } finally {
      lock.writeLock().unlock();
    }
  }

  // ──────────────────────────────────────────────
  // 打开菜单
  // ──────────────────────────────────────────────

  /**
   * 为玩家打开菜单
   * <p>
   * 自动判断玩家客户端类型：基岩版使用 Floodgate SimpleForm，Java 版使用箱子 GUI。
   *
   * @param menuPath 菜单路径（可为空，表示关闭菜单）
   * @param player   目标玩家
   * @throws IllegalArgumentException 菜单路径非法或菜单加载失败
   */
  public void openMenuFor(String menuPath, Player player) throws IllegalArgumentException {
    if (menuPath == null || menuPath.isEmpty()) {
      player.closeInventory();
      return;
    }

    String resolved = SMenuEntry.resolvePath(menuPath);
    if (resolved.isEmpty()) {
      player.closeInventory();
      return;
    }

    SMenuEntry menuData = loadMenu(resolved);

    if (isBedrockPlayer(player)) {
      openBedrockMenu(menuData, player);
    } else {
      openJavaMenu(menuData, player);
    }
  }

  // ──────────────────────────────────────────────
  // Java 版：箱子 GUI
  // ──────────────────────────────────────────────

  /** 为 Java 版玩家打开箱子 GUI 菜单 */
  private void openJavaMenu(SMenuEntry menuData, Player player) {
    int lines = menuData.getLines();
    String title = menuData.getTitle();
    // 解析 PAPI 占位符（带玩家上下文，替换 %player_name% 等玩家相关占位符）
    String papiTitle = Joyous.i18n.getPHparsed(player, title);

    Inventory inventory = Bukkit.createInventory(null, lines * 9,
        LegacyComponentSerializer.legacySection().deserialize(MCColor.parse(papiTitle)));

    // 放置按钮（按权限过滤，逐玩家构建以解析 PAPI）
    for (var btn : menuData.getJavaButtons()) {
      int slot = (btn.x() - 1) * 9 + (btn.y() - 1);
      if (checkPermission(player, btn.perm(), btn.permUnhave())) {
        inventory.setItem(slot, btn.buildItem(player));
      }
    }

    // 记录当前菜单并打开
    ACTIVE_MENUS.put(player.getUniqueId(), menuData);
    player.openInventory(inventory);
    player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.5f, 1.0f);
  }

  /** 获取玩家当前打开的菜单，若没有则返回 null */
  public SMenuEntry getActiveMenu(Player player) {
    return ACTIVE_MENUS.get(player.getUniqueId());
  }

  /** 移除玩家的活跃菜单记录 */
  public void removeActiveMenu(Player player) {
    ACTIVE_MENUS.remove(player.getUniqueId());
  }

  // ──────────────────────────────────────────────
  // 基岩版：Floodgate 表单
  // ──────────────────────────────────────────────

  /**
   * 为基岩版玩家打开 Floodgate SimpleForm 菜单
   * <p>
   * 如果 Floodgate 不可用，此方法会静默跳过。
   */
  private void openBedrockMenu(SMenuEntry menuData, Player player) {
    if (!isFloodgateAvailable()) {
      logger.debug("Floodgate 不可用，跳过基岩版菜单 [%s] 的打开", menuData.getMenuPath());
      return;
    }

    try {
      // 逐玩家解析 PAPI 后，去除所有颜色代码供基岩版表单使用
      String title = MCColor.remove(Joyous.i18n.getPHparsed(player, menuData.getTitle()));
      var buttons = menuData.getBedrockButtons();

      // 过滤可用按钮
      var validButtons = buttons.stream()
          .filter(btn -> checkPermission(player, btn.perm(), btn.permUnhave()))
          .toList();

      var formBuilder = org.geysermc.cumulus.form.SimpleForm.builder()
          .title(title)
          .content("");

      for (var btn : validButtons) {
        String text = MCColor.strip(btn.text());
        if (btn.icon() != null && !btn.icon().isBlank()) {
          if (btn.icon().startsWith("url:")) {
            formBuilder = formBuilder.button(text,
                org.geysermc.cumulus.util.FormImage.Type.URL,
                btn.icon().substring(4));
          } else {
            formBuilder = formBuilder.button(text,
                org.geysermc.cumulus.util.FormImage.Type.PATH,
                btn.icon());
          }
        } else {
          formBuilder = formBuilder.button(text);
        }
      }

      var finalButtons = validButtons;

      formBuilder = formBuilder.validResultHandler((form, response) -> {
        int clickedId = response.clickedButtonId();
        if (clickedId >= 0 && clickedId < finalButtons.size()) {
          var btn = finalButtons.get(clickedId);
          executeAction(player, btn.action(), btn.param());
        }
      });

      formBuilder = formBuilder.closedOrInvalidResultHandler((form, response) -> {
        // 玩家关闭了菜单，什么也不做
      });

      org.geysermc.floodgate.api.FloodgateApi.getInstance()
          .sendForm(player.getUniqueId(), formBuilder.build());

    } catch (Exception e) {
      logger.warn("无法为基岩版玩家 [%s] 打开菜单 [%s]：%s",
          player.getName(), menuData.getMenuPath(), e.getLocalizedMessage(), e);
    }
  }

  // ──────────────────────────────────────────────
  // 按钮动作执行
  // ──────────────────────────────────────────────

  /**
   * 执行按钮动作
   *
   * @param player 目标玩家
   * @param action 动作类型：{@code menu} / {@code cmd} / {@code op} / {@code con} / {@code url}
   * @param param  动作参数
   */
  public void executeAction(Player player, String action, String param) {
    if (action == null || action.isBlank()) return;

    // 替换占位符
    String resolvedParam = (param != null)
        ? param.replace("%player%", player.getName())
              .replace("%uuid%", player.getUniqueId().toString())
        : "";

    switch (action) {
      case "menu" -> {
        try {
          openMenuFor(resolvedParam, player);
        } catch (IllegalArgumentException e) {
          player.sendMessage(MCColor.parse("&c菜单错误：" + e.getLocalizedMessage()));
          logger.warn("玩家 [%s] 打开子菜单失败：[%s] %s",
              player.getName(), resolvedParam, e.getLocalizedMessage());
        }
      }
      case "cmd" -> player.performCommand(resolvedParam);
      case "op" -> {
        boolean wasOp = player.isOp();
        if (!wasOp) player.setOp(true);
        try {
          Bukkit.dispatchCommand(player, resolvedParam);
        } catch (Exception e) {
          logger.warn("以 OP 身份为 [%s] 执行命令 [%s] 失败：%s",
              player.getName(), resolvedParam, e.getLocalizedMessage());
        } finally {
          if (!wasOp) player.setOp(false);
        }
      }
      case "con" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolvedParam);
      case "url" -> {
        if (!isBedrockPlayer(player)) {
          player.sendMessage(
              Component.text(MCColor.parse(Joyous.i18n.tr("smenu.open_url", param)))
                  .color(NamedTextColor.AQUA)
                  .decorate(TextDecoration.UNDERLINED)
                  .clickEvent(ClickEvent.openUrl(resolvedParam)));
        } else {
          player.sendMessage(MCColor.parse(Joyous.i18n.tr("smenu.open_url_be", param)));
        }
      }
      default -> logger.debug("菜单按钮动作 [%s] 未被识别，无操作", action);
    }
  }

  // ──────────────────────────────────────────────
  // 权限校验
  // ──────────────────────────────────────────────

  /**
   * 检查玩家是否有权限看到/使用按钮
   *
   * @param player    玩家
   * @param perm      权限节点，null 或空表示不校验
   * @param permUnhave true 表示反选（拥有权限则不可见）
   * @return true 表示可见/可用
   */
  public static boolean checkPermission(Player player, String perm, boolean permUnhave) {
    if (perm == null || perm.isBlank()) return true;
    boolean has = player.hasPermission(perm);
    return permUnhave ? !has : has;
  }

  // ──────────────────────────────────────────────
  // 客户端类型检测
  // ──────────────────────────────────────────────

  /** Floodgate 是否可用（惰性检测，仅首次判断） */
  public boolean isFloodgateAvailable() {
    if (floodgateAvailable == null) {
      floodgateAvailable = Joyous.pluginManager.isPluginEnabled("Geyser-Spigot")
          && Joyous.pluginManager.isPluginEnabled("floodgate");
    }
    return floodgateAvailable;
  }

  /**
   * 判断玩家是否为基岩版
   * <p>
   * 当 Floodgate 未安装时始终返回 {@code false}。
   */
  public boolean isBedrockPlayer(Player player) {
    if (!isFloodgateAvailable()) return false;
    try {
      return org.geysermc.geyser.api.GeyserApi.api().isBedrockPlayer(player.getUniqueId());
    } catch (Exception e) {
      logger.debug("检测基岩版玩家失败：%s", e.getLocalizedMessage());
      return false;
    }
  }
}
