package com.github.streackmc.Joyous;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.streackmc.Joyous.Entroprix.EntroprixMain;
import com.github.streackmc.Joyous.PlayerTitle.PlayerTitleMain;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PHAPI extends PlaceholderExpansion {
  public PHAPI() {
  }

  public interface ModelsPHAPI {
    public String onPlaceholderRequest(Player player, @NotNull String params);
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

    String Entroprix = parse(EntroprixMain.PlaceholderService, player, params);
    if (Entroprix != null) return Entroprix;
    String PlayerTitle = parse(PlayerTitleMain.PlaceholderService, player, params);
    if (PlayerTitle != null) return PlayerTitle;
  
    return null;
  }
  
  private String parse(ModelsPHAPI provider, Player player, @NotNull String params) {
    if (provider == null)
      return null;
    return provider.onPlaceholderRequest(player, params);
  }
}
