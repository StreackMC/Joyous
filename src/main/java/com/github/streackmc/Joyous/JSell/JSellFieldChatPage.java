package com.github.streackmc.Joyous.JSell;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.input.AbstractChatPage;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

/**
 * 管理员界面的一次「改一个值」输入
 * <p>
 * 每个字段（单价、限额、窗口、命令、确认删除……）都只是「问一句话 + 拿到结果交给谁」，
 * 没有自己的状态，所以共用一个实现。
 * <p>
 * 提示文案由 {@code ChatInput} 统一组装；本类只说明「问什么、旧值是什么、要追加哪几行提示」。
 * 输入页以 {@code INSTANT} 压在列表/详情之上，提交、取消或超时后会自动回到原来的界面。
 *
 * @author Neonai
 * @since 0.8.0
 */
final class JSellFieldChatPage extends AbstractChatPage {

  private final String key;
  private final String purpose;
  private final ChatPrompt prompt;
  private final Consumer<String> accept;

  JSellFieldChatPage(String key, String purpose, ChatPrompt prompt, Consumer<String> accept) {
    this.key = key;
    this.purpose = purpose;
    this.prompt = prompt;
    this.accept = accept;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public String purpose() {
    return purpose;
  }

  @Override
  public ChatPrompt prompt(Player viewer) {
    return prompt;
  }

  /** 沿用模块配置的超时（{@code jsell.command.chat-input-timeout}） */
  @Override
  public int timeoutSeconds() {
    return JSellMain.CONF.chatInputTimeoutSeconds();
  }

  @Override
  public void onInput(IPageContext ctx, String text) {
    accept.accept(text);
    ctx.close();
  }
}
