package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

/**
 * 页面钩子的上下文
 * <p>
 * 生命周期钩子的第一个参数都是它。除了取玩家与自身信息，还提供两个「合法的小动作」：
 * 结束自己、把自己提到队头。
 * <p>
 * 注意：钩子内再次 {@link PageArbiter#open} 是允许的，但那个请求会排在当前转移<b>之后</b>处理，
 * 不会造成递归（见 {@code PageArbiter} 的重入规则）。
 *
 * @author Neonai
 * @since 0.7.0
 */
public interface IPageContext extends IPageHandle {

  /**
   * 所属玩家
   * <p>
   * 正常运行时恒非 null；纯 JVM 自检里可能是 null，页面实现不应在构造期依赖它。
   */
  @Nullable
  Player player();

  /**
   * 把自己提到队列头部（下次轮到自己时优先）
   * <p>
   * 仅对排队中的页面有意义；可见时是空操作。慎用——它会插到别人的前面。
   */
  void toFront();
}
