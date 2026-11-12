package com.github.streackmc.Joyous.utils.interact;

/**
 * 页面显示/隐藏的观察者
 * <p>
 * 给「不属于页面、但需要在页面出现在玩家面前时做点什么」的组件用。典型例子是聊天栏输入：
 * 提示文案该长什么样由 transport 决定，页面只提供语义（问什么、旧值是什么），
 * 所以 transport 必须知道「这一页刚刚显示出来了」才能把提示发出去。
 * <p>
 * <b>不要在这里做业务</b>。它是旁路通知，回调失败不会影响仲裁器的转移流程；
 * 需要在页面生命周期里做的事，仍然写在 {@link IJoyousPage#onAttach}/{@link IJoyousPage#onResume} 里。
 * <p>
 * 回调都在主线程执行，且一定发生在页面状态已更新之后（{@code onPageShown} 时 {@link #state()}
 * 为 {@code VISIBLE}，{@code onPageHidden} 时已经是 {@code SUSPENDED} 或 {@code DETACHED}）。
 *
 * @author Neonai
 * @since 0.7.0
 */
public interface IPageObserver {

  /**
   * 页面开始显示（首次显示或从挂起恢复）
   *
   * @param ctx 页面上下文
   */
  default void onPageShown(IPageContext ctx) {
  }

  /**
   * 页面不再显示（被挂起或被终止）
   *
   * @param ctx 页面上下文
   */
  default void onPageHidden(IPageContext ctx) {
  }
}
