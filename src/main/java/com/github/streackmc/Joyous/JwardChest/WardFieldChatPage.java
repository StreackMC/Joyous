package com.github.streackmc.Joyous.JwardChest;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.input.AbstractChatPage;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

/**
 * 战利品箱的一个「改一个值」输入页
 * <p>
 * 每个字段（预设代号、标题、粗筛时长……）都只是「问一句话 + 拿到结果后交给谁」，
 * 没有自己的状态，所以这一类字段共用一个实现即可。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardFieldChatPage extends AbstractChatPage {

  private final String key;
  private final String purpose;
  private final ChatPrompt prompt;
  private final Consumer<String> accept;

  WardFieldChatPage(String key, String purpose, ChatPrompt prompt, Consumer<String> accept) {
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

  @Override
  public void onInput(IPageContext ctx, String text) {
    accept.accept(text);
    ctx.close();
  }
}
