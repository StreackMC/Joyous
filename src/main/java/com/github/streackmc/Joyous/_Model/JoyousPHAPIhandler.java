package com.github.streackmc.Joyous._Model;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface JoyousPHAPIhandler {
  public String onPlaceholderRequest(Player player, @NotNull String params);
}
