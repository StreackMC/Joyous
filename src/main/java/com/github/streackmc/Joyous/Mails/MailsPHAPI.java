package com.github.streackmc.Joyous.Mails;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

/**
 * Mails 模块 PlaceholderAPI 拓展（预留）
 * 
 * @since 0.0.1
 */
public class MailsPHAPI implements JoyousPHAPIhandler {
  MailsPHAPI() {
  }

  @Override
  public String onPlaceholderRequest(Player player, @NotNull String params) {
    // 预留：后续可在此实现邮件相关占位符
    return null;
  }
}
