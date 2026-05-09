package com.github.streackmc.Joyous;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PHAPI_Backend extends PlaceholderExpansion {
  public PHAPI_Backend() {
  }

  @Override
  @NotNull
  public String getIdentifier() {
    return "joyous"; // %joyous_xxx% 的前缀
  }

  @Override
  @NotNull
  public String getAuthor() {
    return "kdxiaoyi & StreackMC Team";
  }

  @Override
  @NotNull
  public String getVersion() {
    return Joyous.getVersion();
  }

  @Override
  public boolean persist() {
    return true; // 插件重载时不卸载此占位符
  }

  @Override
  public boolean canRegister() {
    return true; // 是否可以注册
  }

  @Override
  @Nullable
  public String onPlaceholderRequest(Player player, @NotNull String params) {
    logger.debug("传入PHAPI请求: %s", params);

    String result = null;
    for (JoyousPHAPIhandler h : Joyous.PlaceholderService.usableParser) {
      result = Joyous.PlaceholderService.parse(h, player, params);
      if (result != null && !result.isEmpty() && !result.isBlank())
        break;// 竞争解析
    }

    return result;
  }

  public String parseText(String text, Player player) {
    return PlaceholderAPI.setPlaceholders(player, text);
  }
}