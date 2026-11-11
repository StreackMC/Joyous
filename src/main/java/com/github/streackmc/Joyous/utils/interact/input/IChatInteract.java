package com.github.streackmc.Joyous.utils.interact.input;

import org.bukkit.entity.Player;

/**
 * 聊天栏输入页面
 * <p>
 * 比 {@link ITextInteract} 多一样东西：给玩家看的那条提示。文本怎么拿到不在这里决定，
 * 由 {@link ChatInput} 截获聊天事件后回调。
 *
 * @author Neonai
 * @since 0.7.0
 */
public interface IChatInteract extends ITextInteract {

  /** 提示内容（标签、旧值、额外提示行） */
  ChatPrompt prompt(Player viewer);

  /** 等待多久算超时（秒），默认 60 */
  default int timeoutSeconds() {
    return 60;
  }
}
