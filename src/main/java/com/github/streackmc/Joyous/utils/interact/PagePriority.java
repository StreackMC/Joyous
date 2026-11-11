package com.github.streackmc.Joyous.utils.interact;

/**
 * 页面打开优先度
 * <p>
 * 描述「新页面要显示时，怎么处置当前页面、以及新页面自己排在哪里」。
 * 四条取值构成一个完整的决策表，没有隐含默认值——调用方必须显式选一个。
 * <p>
 * <b>选用建议</b>：除非目的很明确（例如「替换掉当前编辑器」「必须排到玩家现有队列后面」），
 * 一般都选 {@link #INSTANT}。它是唯一一个既能保证玩家立刻看到、又不会毁掉他手上进度的选项。
 *
 * @author Neonai
 * @since 0.7.0
 */
public enum PagePriority {

  /**
   * 立即显示，并<b>挂起</b>当前页面
   * <p>
   * 当前页面的界面立刻关闭，但流程不结束：它被压回队列头部，等新页面结束后自动恢复。
   * 只有在当前页面 {@link JoyousPage#keepable()} 为 {@code true} 时才成立；
   * 否则退化为 {@link #REPLACE}（只记日志，不打扰玩家）。
   * <p>
   * 适用：系统提示、二次确认、聊天输入——这些东西插一下就走，玩家还要回到原来的界面。
   */
  INSTANT("立即显示，当前页面挂起并可恢复"),

  /**
   * 立即显示，并<b>结束</b>当前页面
   * <p>
   * 当前页面的界面立刻关闭，并走完它自己的终止流程（退款 / 结算 / 落库等）。
   * 队列中原有的条目不受影响。
   * <p>
   * 适用：目标是「替代」而不是「暂停」的场景，例如玩家死亡界面、强制踢回主菜单。
   */
  REPLACE("立即显示，当前页面流程结束"),

  /**
   * 排到队列尾部
   * <p>
   * 当前页面完全不受影响（不关闭、不暂停、不改变其计时器），新页面等轮到自己才显示。
   * <p>
   * 适用：低优先级的通知、活动公告——玩家手头的事做完自然会看到。
   */
  NORMAL("排到队列尾部，当前页面不受影响"),

  /**
   * 插到队列头部
   * <p>
   * 与 {@link #NORMAL} 一样不影响当前页面，但会插到所有已排队条目的前面，
   * 当前页面一结束就轮到自己。
   * <p>
   * 适用：比普通通知更急、但又不该打断玩家的内容，例如「你有一条待领取的补偿」。
   */
  INSERT("插到队列头部，当前页面不受影响");

  private final String description;

  PagePriority(String description) {
    this.description = description;
  }

  /** 人类可读的语义说明，用于日志与诊断 */
  public String description() {
    return description;
  }

  /**
   * 是否属于「需要立刻改变当前页面」的一类
   *
   * @return {@link #INSTANT} 与 {@link #REPLACE} 返回 true
   */
  public boolean immediate() {
    return this == INSTANT || this == REPLACE;
  }
}
