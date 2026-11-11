package com.github.streackmc.Joyous.utils.interact;

/**
 * 页面在仲裁器里的四种状态
 * <p>
 * 转移路径见 {@link PageArbiter} 的类注释；只有 {@link #DETACHED} 是吸收态。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public enum PageState {

  /** 已创建、还没显示过，躺在队列里等 */
  PENDING,

  /** 正在显示（每个玩家最多一个） */
  VISIBLE,

  /** 显示过、被更高优先度挤下去了，流程仍在继续 */
  SUSPENDED,

  /** 已终止，不会再变化 */
  DETACHED;

  /** 是否已终止（吸收态） */
  public boolean terminal() {
    return this == DETACHED;
  }

  /** 是否还在队列里等着 */
  public boolean queued() {
    return this == PENDING || this == SUSPENDED;
  }
}
