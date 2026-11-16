package com.github.streackmc.Joyous.utils.model_abstract;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface JoyousPHAPIhandler {
  /**
   * 处理你的 PHAPI
   * @param player 玩家参数，可为 null
   * @param params 传入参数，"joyous_"前缀被 PHAPI 过滤，null 被 Joyous for PHAPI 前置处理器过滤
   * @return 返回 null 表示无法解析
   */
  public String onPlaceholderRequest(@Nullable org.bukkit.entity.Player player, @NotNull String params);
}
