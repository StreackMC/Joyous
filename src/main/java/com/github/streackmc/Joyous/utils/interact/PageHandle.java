package com.github.streackmc.Joyous.utils.interact;

/**
 * 交给调用方的页面句柄
 * <p>
 * 只暴露「看状态」和「结束自己」两件事，不允许绕过仲裁器改状态。
 * 句柄在页面终止后依然有效（`state()` 返回 {@link PageState#DETACHED}），可以安全地长期持有。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public interface PageHandle {

  /** 页面标识（与 {@link JoyousPage#key()} 一致） */
  String key();

  /** 页面目的（与 {@link JoyousPage#purpose()} 一致），用于同 id 不同用途的去重判断 */
  String purpose();

  /** 当前状态 */
  PageState state();

  /** 是否还活着（未终止） */
  default boolean active() {
    return !state().terminal();
  }

  /**
   * 结束自己
   * <p>
   * 可见时等价于「玩家关闭界面」，会触发终止流程并让队列里的下一个页面顶上；
   * 排队中则直接从队列移除。重复调用返回 false。
   *
   * @return 本次是否真的结束了它
   */
  boolean close();
}
