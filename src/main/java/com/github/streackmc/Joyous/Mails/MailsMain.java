package com.github.streackmc.Joyous.Mails;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.types.SMail;
import com.github.streackmc.StreackLib.utils.SFile;

/**
 * 邮件模块
 * 
 * @author kdxiaoyi
 * @since 0.0.1
 */
public class MailsMain extends JoyousModel {
  public String MODEL_NAME() {
    return "Mails";
  }

  public MailsMain() {
  };

  /** 配置文件路径 */
  volatile static Path CONF_PATH = Joyous.dataPath.toPath().resolve(NAMES.CONF_FILE);
  /** 邮件模板文件路径 */
  volatile static Path TEMPLATE_PATH = Joyous.dataPath.toPath().resolve(NAMES.TEMPLATE_FOLDER);
  /** 配置文件 */
  public static SConfig mailConf;

  /** 临时禁用关服邮件（仅本次服务器运行有效） */
  public volatile static boolean shutdownMailDisabled = false;

  public final static class NAMES {
    /** 配置文件名 */
    public final static String CONF_FILE = "models/Mails.yml";
    /** 数据目录名 */
    public final static String TEMPLATE_FOLDER = "models/Mails/";
    /** 插件内置文件目录 */
    public final static String INTERNAL_ASSETS = "assets/Mails/";
    /** 权限前缀 */
    public final static String PERMISSION_PREFIX = "joyous.mails.";
  };

  // 命令服务
  public volatile static MailsCommand CommandService = new MailsCommand();

  @Override
  public final void onEnable() {
    Joyous.addPermissions(PermDef.none("joyous.mails", "Mails 模块权限节点"));
    if (Files.notExists(CONF_PATH)) {
      try {
        jlogger.debug("检查到 %s 不存在，自动新建默认文件", CONF_PATH);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.CONF_FILE), CONF_PATH.toFile());
      } catch (Exception e) {
        jlogger.err("警告：无法写入 %s ： %s", NAMES.CONF_FILE, e.getLocalizedMessage(), e);
      }
    }
    try {
      mailConf = new SConfig(CONF_PATH.toFile(), "yml");
      mailConf.setAutoReload(true);
    } catch (Exception e) {
      jlogger.err("Mails | 无法加载邮件配置: %s", e.getLocalizedMessage());
      return;
    }

    // 确保关服邮件模板文件存在
    Path shutdownTemplate = TEMPLATE_PATH.resolve("shutdown.html");
    if (Files.notExists(shutdownTemplate)) {
      try {
        jlogger.debug("检查到 %s 不存在，自动新建默认模板", shutdownTemplate);
        SFile.mv(Joyous.getResourceAsFile("/" + NAMES.INTERNAL_ASSETS + "shutdown.html"), shutdownTemplate.toFile());
      } catch (Exception e) {
        jlogger.err("警告：无法写入关服邮件模板 %s ： %s", shutdownTemplate, e.getLocalizedMessage(), e);
      }
    }

    CommandService.register();
  }
  
  @Override
  public final void onDisable() {
    try {
      sendShutdownMail();
    } catch (Exception e) {
      jlogger.err("Mails | 关服邮件发送异常: %s", e.getLocalizedMessage(), e);
    }
    CommandService = null;
  }

  /** 发送关服邮件 */
  private void sendShutdownMail() {
    if (shutdownMailDisabled) {
      jlogger.info("Mails | 关服邮件已被临时禁用（/jmail tempswitch shutdown）");
      return;
    }

    if (!mailConf.getBoolean("shutdown.enabled", true)) {
      jlogger.debug("Mails | 关服邮件未启用（shutdown.enabled = false）");
      return;
    }

    String profile = mailConf.getString("shutdown.profile", "default");
    List<String> to = mailConf.getListOfString("shutdown.to");
    String subject = mailConf.getString("shutdown.subject", "Server Shutdown");
    String name = mailConf.getString("shutdown.name", "Server");
    boolean highPriority = mailConf.getBoolean("shutdown.high-pirority", false);

    if (to == null || to.isEmpty()) {
      jlogger.warn("Mails | 关服邮件未设置收件人（shutdown.to）");
      return;
    }

    try {
      String body = buildShutdownBody(name);
      SMail mail = SMail.builder(profile)
          .bcc(to)
          .subject(subject)
          .body(body, true)
          .priority(highPriority ? 1 : 0)
          .build();
      mail.send();
      jlogger.info("Mails | 关服邮件已发送至 %s", String.join(", ", to));
    } catch (IllegalArgumentException e) {
      jlogger.err("Mails | 发送关服邮件失败: 时间格式错误，发现了 %s", e.getLocalizedMessage(), e);
    } catch (Exception e) {
      jlogger.err("Mails | 发送关服邮件失败: %s", e.getLocalizedMessage(), e);
    }
  }

  /** 构建关服邮件 HTML 正文（从模板文件读取） */
  private String buildShutdownBody(String serverName) throws IllegalArgumentException {
    Path templatePath = TEMPLATE_PATH.resolve("shutdown.html");
    String template;
    try {
      template = Files.readString(templatePath);
    } catch (IOException e) {
      jlogger.warn("Mails | 无法读取关服邮件模板 %s，使用内联默认值", templatePath);
      template = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Server Shutdown</title></head>"
          + "<body style='font-family: Arial, sans-serif; padding: 20px;'>"
          + "<h2>Server Shutdown</h2>"
          + "<p>Server <strong>%name%</strong> has shut down.</p>"
          + "<hr><p style='color: #888; font-size: 12px;'>Auto sent by Joyous Mails module</p>"
          + "</body></html>";
    }
    return template
        .replace("%name%", serverName != null ? serverName : "")
        .replace("%time%", StreackLib.formatTime(null, mailConf.getString("shutdown.time", "yyyy-MM-dd HH:mm:ss")));
  }
}
