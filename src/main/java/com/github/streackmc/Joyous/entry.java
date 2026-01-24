package com.github.streackmc.Joyous;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.streackmc.Joyous.APIHolders.APIHoldersMain;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.HTTPServer;
import com.github.streackmc.StreackLib.utils.SConfig;

public class entry extends JavaPlugin {

  // public LiteralArgumentBuilder<CommandSourceStack> commandTree = Commands.literal("api-holders");

  @Override
  public void onEnable() {
    logger.info("正在启用APIHolders...");
    Joyous.plugin = this;
    Joyous.dataPath = this.getDataFolder();
    /* 检查依赖 */
    try {
      CheckDependencies();
    } catch (RuntimeException e) {
      logger.severe(e.getLocalizedMessage());
      e.printStackTrace();
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    /* 读入 StreackLib:HTTPServer */
    APIHoldersMain.httpServer = StreackLib.getHttpServer();
    if (APIHoldersMain.httpServer == null) {
      logger.severe("启用失败：StreackLib的HTTPServer模块无法启用或无法与之通信。");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    /* 检查更新 */
    CheckConfigUpdate();
    /* 开启HTTPServer */
    APIHoldersMain.onEnable();
  }

  @Override
  public void onDisable() {
    logger.info("正在禁用APIHolders...");
    try {
      APIHoldersMain.onDisable();
    } catch (Exception ignored) {
    }
  }

  private void CheckConfigUpdate() {
    logger.info("正在检查配置文件：" + new File(Joyous.dataPath, "config.yml").getPath());
    int diff = Joyous.getConfigVerisonDiff();
    if (diff > 0) {
      logger.warn("你的配置文件版本过高？请勿自行修改或强行应用高版本配置文件，否则可能引发意料之外的错误。当前版本：" + StreackLib.conf.getInt("version", 0) + "，适配版本："
          + Joyous.CONFIG_VERSION);
    }
    if (diff < 0) {
      logger.severe("注意：你的配置文件版本过低，请参阅config.new.yml修改你的配置文件；现在未配置的项将使用默认值。当前版本：" + StreackLib.conf.getInt("version", 0)
          + "，适配版本：" + Joyous.CONFIG_VERSION);
      try (
          InputStream is = this.getResource("config.yml");
          OutputStream os = Files.newOutputStream(new File(Joyous.dataPath, "config.new.yml").toPath());) {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0) {
          os.write(buffer, 0, length);
        }
        os.close();
      } catch (Exception e) {
        logger.severe("配置文件更新失败：" + e.getLocalizedMessage());
        e.printStackTrace();
      }
    }
  }

  private void CheckDependencies() throws RuntimeException {
    /* 检测 StreackLib */
    Plugin StreackLib_paper = Bukkit.getPluginManager().getPlugin("StreackLib");
    if (StreackLib_paper == null || !StreackLib_paper.isEnabled()) {
      throw new RuntimeException("启用失败：未检测到StreackLib");
    }
    /* 检测 PlaceholderAPI */
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      throw new RuntimeException("启用失败：未检测到PlaceholderAPI");
    }
  }


  private entry() {}
}