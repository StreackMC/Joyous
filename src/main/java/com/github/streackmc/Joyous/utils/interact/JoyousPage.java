package com.github.streackmc.Joyous.utils.interact;

/**
 * 一个可被仲裁的页面
 * <p>
 * 仲裁器只管「什么时候轮到谁、被挤下去怎么办」，不管界面怎么画、事件怎么路由——
 * 那些由具体实现（箱子界面、聊天输入、告示牌……）自己在钩子里做。
 *
 * <h2>契约（实现方必须遵守）</h2>
 * <ol>
 *   <li><b>构造与入队阶段必须无副作用</b>：不注册监听、不写库、不给物品、不开界面。
 *       资源只能在 {@link #onAttach} 里获取。这样「还没显示就被丢弃」时才可以不调用任何钩子。</li>
 *   <li>{@link #onAttach} 与 {@link #onDetach} <b>成对且各最多一次</b>。</li>
 *   <li>{@link #onSuspend} 与 {@link #onResume} 成对，但可以发生多轮（被反复挤下去又恢复）。</li>
 *   <li>终止副作用（退款、结算、落库）只能在 {@link #onDetach} 里做，且必须能承受重复调用。</li>
 * </ol>
 *
 * <h2>KEEPABLE（可保留）</h2>
 * {@link #keepable()} 描述的是<b>被挤下去的那一页</b>能不能原样回来，
 * 判定口径是代码层面的四件事，任一不成立就必须返回 {@code false}：
 * <ol>
 *   <li><b>内容可重建</b>：界面里的东西全部由模型派生，视图销毁后能一模一样画回来；</li>
 *   <li><b>目的可延续</b>：这个页面的目标不是「一次性的」（例如一次性确认框，关掉即视为放弃）；</li>
 *   <li><b>时效性未绑定</b>：页面的输入或上下文不依附于某一次事件处理/回调窗口，
 *       隔一段时间再恢复仍然成立；</li>
 *   <li><b>无「关闭即结算」副作用</b>：关掉界面不等于结束流程。</li>
 * </ol>
 * 返回 {@code true} 却做不到上面任何一条，会让玩家遇到「看起来恢复了、其实状态已被结算过」的错乱，
 * 这比直接不保留更糟。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 * @see PagePriority
 */
public interface JoyousPage {

  /**
   * 页面标识
   * <p>
   * 同一玩家的去重按 {@code (key, purpose)} 判断：两者都相同才算重复。
   * 建议写成稳定的点分路径，例如 {@code "ward.runtime"}、{@code "sell.sell"}。
   */
  String key();

  /**
   * 页面目的
   * <p>
   * 同一个 {@link #key()} 可以有不同的目的（例如同一条目的「编辑」与「删除确认」），
   * 目的不同就不算重复，允许同时排队。默认空串表示「这个 key 只有一种用途」。
   */
  default String purpose() {
    return "";
  }

  /**
   * 是否允许被 {@link PagePriority#INSTANT} 挂起后放回队列
   * <p>
   * 默认 {@code false}：不可保留是安全侧——宁可让页面被正常结束，也不要留下半截状态。
   *
   * @see JoyousPage 类注释里的判定口径
   */
  default boolean keepable() {
    return false;
  }

  /**
   * 第一次显示时调用
   * <p>
   * 在这里开界面、建模型、起定时任务。此时 {@link PageContext#state()} 已经是
   * {@link PageState#VISIBLE}。
   */
  default void onAttach(PageContext ctx) {
  }

  /**
   * 被 {@link PagePriority#INSTANT} 挤下去时调用
   * <p>
   * 界面已被关闭，但流程不结束。应在这里停掉自己的定时任务，并保证模型完整保留。
   */
  default void onSuspend(PageContext ctx) {
  }

  /**
   * 从挂起状态回到前台时调用
   * <p>
   * 应重绘界面并从模型续算，<b>不要</b>重跑 {@link #onAttach} 里的初始化副作用。
   */
  default void onResume(PageContext ctx) {
  }

  /**
   * 终止时调用（每个页面最多一次）
   * <p>
   * 这里是唯一允许做终止副作用的地方。被丢弃的 {@link PageState#PENDING} 页面不会触发它，
   * 因为按契约那时还没有任何资源。
   */
  default void onDetach(PageContext ctx, DetachReason why) {
  }

  /**
   * 轮到自己出队时的有效性检查
   * <p>
   * 排队期间世界可能变了（方块被拆、条目被删、玩家换了位置），返回 {@code false} 会被直接丢弃
   * 并以 {@link DetachReason#INVALID} 终止，不会显示。
   */
  default boolean stillValid() {
    return true;
  }
}
