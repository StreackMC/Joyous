package com.github.streackmc.Joyous.utils.interact;

/**
 * 页面终止的原因
 * <p>
 * 只在 {@link JoyousPage#onDetach(PageContext, DetachReason)} 里出现，
 * 消费端据此决定「要不要退款 / 要不要落库 / 要不要提示玩家」。
 *
 * @author Neonai
 * @since 0.7.0
 */
public enum DetachReason {

  /** 页面自己结束，或玩家主动关闭 */
  CLOSED,

  /** 被更高优先度的页面挤掉（含 {@code INSTANT} 遇到不可保留页面时的降级） */
  REPLACED,

  /** 还没显示就被丢弃（队列已满、单次转移次数超限） */
  DISCARDED,

  /** 轮到自己出队时 {@link JoyousPage#stillValid()} 返回 false */
  INVALID,

  /** 排队超过时限（只作用于 {@link PageState#PENDING}） */
  TIMEOUT,

  /** 玩家退出游戏 */
  QUIT,

  /** 插件停用 */
  SHUTDOWN
}
