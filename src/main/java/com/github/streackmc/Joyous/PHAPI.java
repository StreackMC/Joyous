package com.github.streackmc.Joyous;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class PHAPI extends PlaceholderExpansion {
  private List<PHAPI.ModelsPHAPI> usableParser = new ArrayList<>();

  public void registerParser(PHAPI.ModelsPHAPI handler) {
    usableParser.add(handler);
  }

  public void unregisiterParser(PHAPI.ModelsPHAPI handler) {
    usableParser.remove(usableParser.indexOf(handler));
  }

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

    String result = null;
    for (ModelsPHAPI h : usableParser) {
      result = parse(h, player, params);
      if (result != null && !result.isEmpty() && !result.isBlank())
        break;// 竞争解析
    }

    return result;
  }
  
  /** 以指定处理器处理 Placeholder */
  public String parse(ModelsPHAPI provider, Player player, @NotNull String params) {
    if (provider == null)
      return null;
    return provider.onPlaceholderRequest(player, params);
  }
}
