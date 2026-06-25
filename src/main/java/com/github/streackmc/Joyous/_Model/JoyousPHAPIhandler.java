package com.github.streackmc.Joyous._Model;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface JoyousPHAPIhandler {
  /**
   * 处理你的 PHAPI
   * @param player 玩家参数，可为 Null
   * @param params 传入参数，"joyous_"前缀被 PHAPI 过滤，null 被 Joyous for PHAPI 前置处理器过滤
   * @return 返回空字符串或者 Null 表示无法解析
   */
  public String onPlaceholderRequest(@Nullable Player player, @NotNull String params);
}
