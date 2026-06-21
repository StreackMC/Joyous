package com.github.streackmc.Joyous;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.streackmc.Joyous.APIHolders.APIHoldersMain;
import com.github.streackmc.Joyous.Entroprix.EntroprixMain;
import com.github.streackmc.Joyous.EnvExport.EnvExport;
import com.github.streackmc.Joyous.Mails.MailsMain;
import com.github.streackmc.Joyous.PlayerTitle.PlayerTitleMain;
import com.github.streackmc.Joyous.SMenu.SMenuMain;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.errors.IgnoredException;
import com.github.streackmc.StreackLib.self.manager;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.github.streackmc.StreackLib.utils.SEventCentral;
import com.github.streackmc.StreackLib.utils.SFile;

public class entry extends JavaPlugin {

  /** 模块列表，统一注册，之后自动进行处理 */
  public static final class Models {
    public static final Map<String, JoyousModel> models = new HashMap<String, JoyousModel>();

    private static void addModel(JoyousModel m) {
      models.put(m.MODEL_NAME(), m);
    }

    static {// 在这里统一注册模块，下方自动遍历处理。
      addModel(new APIHoldersMain());
      addModel(new EntroprixMain());
      addModel(new EnvExport());
      addModel(new PlayerTitleMain());
      addModel(new SMenuMain());
      addModel(new MailsMain());
    }
  }

  // public LiteralArgumentBuilder<CommandSourceStack> commandTree = Commands.literal("api-holders");

  @Override
  public void onEnable() {
    /* 获取全局变量 */
    Joyous.plugin = this;
    logger.plugin = this;
    Joyous.dataPath = this.getDataFolder();
    Joyous.lifeCycleManager = this.getLifecycleManager();
    Joyous.pluginManager = this.getServer().getPluginManager();
    
    // 初始化配置对象
    saveDefaultConfig();
    Joyous.conf = new SConfig(Joyous.dataPath.toPath().resolve("config.yml").toFile(), "yml");
    Joyous.i18n = new i18n(Joyous.dataPath);
    if (Files.exists(Joyous.dataPath.toPath().resolve("language.new.yml"))) {
      logger.warn(Joyous.i18n.tr("system.i18n.update"), Joyous.dataPath.toPath().resolve("language.new.yml").toAbsolutePath());
    }
    try {
      Joyous.confDefault = new SConfig(Joyous.getResourceAsFile("/config.yml"), "yml");
    } catch (Exception e1) {
      try {
        logger.warn("无法载入内联配置文件，正使用空配置替代……：", e1);
        Joyous.confDefault = new SConfig("","yml","joyous");
      } catch (Exception e2) {
        // 真到这了也没必要继续运行了
        logger.severe("无法载入内联配置文件，也无法使用空配置替代：", e2);
        Joyous.pluginManager.disablePlugin(this);
      }
    }
    try {
      Joyous.confBuild = new SConfig(Joyous.getResourceAsFile("/plugin.yml"), "yml");
    } catch (Exception e1) {
      try {
        logger.warn("无法载入构建信息文件，正使用空配置替代……：", e1);
        Joyous.confBuild = new SConfig("","yml","joyous");
      } catch (Exception e2) {
        // 真到这了也没必要继续运行了
        logger.severe("无法载入构建信息文件，也无法使用空配置替代：", e2);
        Joyous.pluginManager.disablePlugin(this);
      }
    }

    /* 检查依赖 */
    try {
      CheckDependencies();
    } catch (RuntimeException e) {
      logger.severe(e.getLocalizedMessage(), e);
      Joyous.pluginManager.disablePlugin(this);
      return;
    }

    /* 初始化配置文件相关 */
    CheckConfigUpdate(); // 检查更新
    AdaptConfigReloadNotification(); // 自动重载事件监听并提示

    /* 子模块 */
    logger.info("正在启用子模块...");
    Models.models.forEach((name, model) -> {
      try {
        if (!Joyous.conf.getBoolean(name + ".enabled", true)) {
          logger.debug("由于配置文件，已禁用 " + name);
          return;
        }
        logger.info("正在启用 " + name);
        model.onEnable();
      } catch (IgnoredException e1) {
        logger.debug("中断" + e1.getLocalizedMessage() + "\n", e1.getStackTrace().toString());
      } catch (Exception e2) {
        logger.severe("启用 %s 时发生错误：%s", name, e2.getLocalizedMessage(), e2);
      }
    });
    logger.info("已尝试启用全部子模块");
  }

  @Override
  public void onDisable() {
    logger.info("正在禁用子模块...");
    Models.models.forEach((name, model) -> {
      try {
        logger.info("正在禁用 " + name);
        model.onDisable();
      } catch (Exception e) {
        logger.warn("禁用 %s 时发生错误：%s", name, e.getLocalizedMessage(), e);
      } finally {
        logger.info("已尝试禁用 " + name);
      }
    });
    logger.info("已尝试禁用全部子模块");
  }

  private void CheckConfigUpdate() {
    logger.info("正在检查配置文件：" + new File(Joyous.dataPath, "config.yml").getPath());
    int diff = Joyous.getConfigVerisonDiff();
    if (diff == 0) {
      logger.info("配置文件版本正常，无需更新。");
      return;
    }
    if (diff > 0) {
      logger.warn("你的配置文件版本过高？请勿自行修改或强行应用高版本配置文件，否则可能引发意料之外的错误。当前版本：" + Joyous.conf.getInt("version", 0) + "，适配版本："
          + Joyous.confDefault.getLong("config-version", 000000L));
    }
    if (diff < 0) {
      logger.severe("注意：你的配置文件版本过低，请参阅config.new.yml修改你的配置文件；现在未配置的项将使用默认值。当前版本：" + Joyous.conf.getInt("version", 0)
          + "，适配版本：" + Joyous.confDefault.getLong("config-version", 000000L));
      try {
        if (!SFile.cp(Joyous.conf.getFile(), new File(Joyous.dataPath, "config.new.yml")))
          throw new IOException("无法复制配置文件");
      } catch (Exception e) {
        logger.severe("配置文件更新失败：" + e, e);
      }
    }
  }

  private void CheckDependencies() throws RuntimeException {
    /* 检测 StreackLib */
    Plugin StreackLib_paper = Joyous.pluginManager.getPlugin("StreackLib");
    if (StreackLib_paper == null || !StreackLib_paper.isEnabled()) {
      throw new RuntimeException("启用失败：未检测到StreackLib");
    }
    if (Joyous.isDebugMode()) {
      logger.debug("检测到StreackLib，版本：" + StreackLib.ENV.buildConf.getString("version"));
      logger.warn("你正在StreackLib中使用调试模式并已继承到Joyous中，因此会收到更多信息。");
    }

    /* 检测 PlaceholderAPI */
    if (!Joyous.pluginManager.isPluginEnabled("PlaceholderAPI")) {
      logger.info("未检测到PlaceholderAPI，相关功能将无法使用。");
      Joyous.PlaceholderService = new PHAPI(false);
    } else {
      Joyous.PHAPI_available = true;
      Joyous.PlaceholderService = new PHAPI(true);
      logger.debug("检测到PlaceholderAPI，版本：" + Joyous.pluginManager.getPlugin("PlaceholderAPI").getDescription().getVersion());
    }
  }

  private void AdaptConfigReloadNotification() {
    Joyous.conf.setAutoReload(true);
    SEventCentral.addEventListener(SConfig.EVENTS.CHANGED, event -> {
      if (event.CALLER_ID.equals(Joyous.conf.INSTANCE_ID)) {
        logger.info("已重载配置");
        logger.debug("测试性读取： APIHolders.path.status = %s", APIHoldersMain.CONF.statusPath());
      };
      if (event.CALLER_ID.equals(Joyous.confDefault.INSTANCE_ID) || event.CALLER_ID.equals(Joyous.confBuild.INSTANCE_ID)) {
        logger.warn("缓存的临时配置文件被修改，这会导致意外的行为！谁干的？ " + manager.getCaller(manager.getCallerMethod.NO_STREACKLIB));
      };
    });
  }

  public entry() {}
}