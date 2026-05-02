package com.github.streackmc.Joyous;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

public class PHAPI {
  protected final List<JoyousPHAPIhandler> usableParser = new ArrayList<>();
  public final boolean available;
  public final PHAPI_Backend expansion;

  public void registerParser(JoyousPHAPIhandler handler) {
    usableParser.add(handler);
  }

  public void unregisiterParser(JoyousPHAPIhandler handler) {
    usableParser.remove(usableParser.indexOf(handler));
  }

  public PHAPI(boolean usable) {
    this.available = usable;
    if (usable) {
      this.expansion = new PHAPI_Backend();
      this.expansion.register();
    } else {
      this.expansion = null;
    }
  }

  /** 以指定处理器处理 Placeholder */
  public String parse(JoyousPHAPIhandler provider, Player player, @NotNull String params) {
    if (provider == null)
      return null;
    return provider.onPlaceholderRequest(player, params);
  }
}
