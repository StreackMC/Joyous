package com.github.streackmc.Joyous.utils.interact.input;

/**
 * 聊天栏输入页面的便捷基类
 * <p>
 * 只做一件固定的事：声明「不可保留」——打到一半被别的页面挤下去，玩家刚打的字就没了，
 * 与其让他看到一个空输入框，不如结束掉、让他重新发起。
 * <p>
 * <b>子类不需要、也不应该发送任何聊天消息</b>。提示、可点击的「粘贴旧值」、取消与超时提示
 * 全部由 transport（{@link ChatInput}）统一组装，页面只回答三个问题：
 * <ol>
 *   <li>{@link #key()} / {@link #purpose()} —— 我是谁（用于去重与定位）；</li>
 *   <li>{@link #prompt(org.bukkit.entity.Player)} —— <b>问什么</b>、<b>旧值是什么</b>；</li>
 *   <li>{@link #onInput(com.github.streackmc.Joyous.utils.interact.IPageContext, String)} —— 拿到文本后做什么。</li>
 * </ol>
 * 超时计时也归 transport：页面不需要知道「等多久算没人理我」，除非要覆盖
 * {@link IChatInteract#timeoutSeconds()}。
 * <p>
 * <b>不需要手动「把界面开回来」</b>：聊天输入用 {@code INSTANT} 压在编辑界面之上，
 * 关掉它以后仲裁器会自动恢复被挂起的编辑界面。
 *
 * @author Neonai
 * @since 0.7.0
 */
public abstract class AbstractChatPage implements IChatInteract {

  @Override
  public boolean keepable() {
    return false;
  }
}
