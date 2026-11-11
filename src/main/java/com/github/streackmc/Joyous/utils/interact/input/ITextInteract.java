package com.github.streackmc.Joyous.utils.interact.input;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.interact.IJoyousPage;
import com.github.streackmc.Joyous.utils.interact.IPageContext;

/**
 * 文本输入类页面
 * <p>
 * 与容器界面并列的第二种页面：不画格子，只要一段文字。具体怎么拿到这段文字
 * 由子接口决定（聊天栏 / 铁砧 / 将来的告示牌），对消费端来说都只是
 * 「给个初值 → 收到文本 / 被取消」。
 * <p>
 * 实现约定：
 * <ul>
 *   <li>{@link #onInput} 一定在<b>主线程</b>执行——聊天事件是异步的，调度由 transport 负责；</li>
 *   <li>校验失败不要去关页面，直接给玩家一句提示、让他在同一个输入里重来；</li>
 *   <li>输入页不可保留（{@link IJoyousPage#keepable()} 默认 false）：打到一半被挤下去，内容就丢了。</li>
 * </ul>
 *
 * @author Neonai
 * @since 0.7.0
 */
public interface ITextInteract extends IJoyousPage {

  /**
   * 输入框的初值（旧值），可为空
   * <p>
   * 聊天栏只用它做「点击粘贴旧值」的提示；铁砧会把它填进文本框。
   */
  default String initialValue(Player viewer) {
    return "";
  }

  /**
   * 收到一次输入
   *
   * @param ctx  上下文（可用来关掉自己）
   * @param text 已 trim 过的文本，不会是 null
   */
  void onInput(IPageContext ctx, String text);

  /** 玩家取消（输入 {@code cancel}、按 ESC 或超时之后）—— 需要回滚时在这里做 */
  default void onCancel(IPageContext ctx) {
  }
}
