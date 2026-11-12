package com.github.streackmc.Joyous.JwardChest;

import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;
import com.github.streackmc.Joyous.utils.interact.chestui.AbstractGuiPage;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

/**
 * 战利品箱编辑子界面的公共基类
 * <p>
 * 五个子界面（容器类型 / 行数 / 最大抽出数量 / 数量分布 / 待选物品）共享三件事：
 * 都挂在同一个容器设置上、都能退回主编辑界面、都能就地发起一次文本输入。
 * 这些放在这里，子类只写「画什么、点了算什么」。
 * <p>
 * 跳转一律用 {@link PagePriorityEnum#REPLACE}：子界面与主界面是「换一页」的关系，
 * 不是叠加关系，所以被换掉的那一页直接终止（而不是挂起在队列里等着回来）。
 * 文本输入则用 {@link PagePriorityEnum#INSTANT}——子界面被挂起，输入结束后自动回到原来的位置。
 *
 * @author Neonai
 * @since 0.7.0
 */
abstract class WardSubPage extends AbstractGuiPage {

  protected final WardEditorGui editor;
  protected final WardProfileStore store;
  protected final UUID profileId;
  @Nullable
  protected final Location block;

  WardSubPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
      @Nullable Location block) {
    this.editor = editor;
    this.store = store;
    this.profileId = profileId;
    this.block = block;
  }

  @Override
  public String purpose() {
    return profileId.toString();
  }

  /** 子界面的内容全部来自设置文件，随时可以重画 */
  @Override
  public boolean keepable() {
    return true;
  }

  /** 每次渲染都重新取：别的地方（例如刚提交的输入）可能已经改过设置 */
  protected WardProfile profile() {
    return store.effective(profileId);
  }

  /** 保存并落盘：一次交互一次写文件 */
  protected void save(WardProfile profile) {
    store.save(profile);
    store.flush();
  }

  /** 换回主编辑界面 */
  protected void backToEditor(Player player) {
    PageArbiter.get().open(player,
        new WardEditorPage(editor, store, profileId, block), PagePriorityEnum.REPLACE);
  }

  /** 换到另一个子界面 */
  protected void open(Player player, WardSubPage page) {
    PageArbiter.get().open(player, page, PagePriorityEnum.REPLACE);
  }

  /**
   * 就地发起一次文本输入
   * <p>
   * 本页会被<b>挂起</b>（而不是关掉），输入提交、取消或超时后自动回到这里；
   * 提示文案由聊天输入层统一组装，这里只说明「问什么、旧值是什么」。
   */
  protected void openChat(IPageContext ctx, String field, ChatPrompt prompt,
      Consumer<String> accept) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    PageArbiter.get().open(player,
        new WardFieldChatPage(key() + "." + field, purpose(), prompt, accept),
        PagePriorityEnum.INSTANT);
  }

  /** 数值解析（输入非法时回落到原值） */
  protected static int intOrDefault(String value, int fallback) {
    return WardEditorGui.parseIntOrDefault(value, fallback);
  }

  /** 一句玩家可见的反馈 */
  protected static void tell(Player player, String key, Object... args) {
    player.sendMessage(i18n.tr(key, args));
  }
}
