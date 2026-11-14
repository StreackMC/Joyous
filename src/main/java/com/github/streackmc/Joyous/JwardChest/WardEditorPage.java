package com.github.streackmc.Joyous.JwardChest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.chestui.AbstractGuiPage;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;
import com.github.streackmc.Joyous.utils.interact.DetachReasonEnum;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.PageArbiter;
import com.github.streackmc.Joyous.utils.interact.PagePriorityEnum;

import net.kyori.adventure.text.Component;

/**
 * 战利品箱的主编辑界面
 * <p>
 * 八个按钮，各自跳到一个子界面或发起一次文本输入。子界面仍由 {@link WardEditorGui}
 * 直接开（它们还没迁到页面层），所以跳转前先 {@code ctx.close()}——
 * 让页面在子界面打开之前就进入终止态，避免关闭事件被当成「玩家退出编辑」。
 * <p>
 * 文本输入则相反：以 {@code INSTANT} 压在编辑界面之上，编辑界面被<b>挂起</b>，
 * 输入结束后自动回到这里——不需要再写「把界面开回来」的回调。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardEditorPage extends AbstractGuiPage {

  static final String KEY = "ward.editor";

  private final WardEditorGui editor;
  private final WardProfileStore store;
  private final UUID profileId;
  @Nullable
  private final Location block;

  WardEditorPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
      @Nullable Location block) {
    this.editor = editor;
    this.store = store;
    this.profileId = profileId;
    this.block = block;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return profileId.toString();
  }

  /** 编辑界面的内容全部来自设置文件，可以随时重画 */
  @Override
  public boolean keepable() {
    return true;
  }

  @Override
  public InteractContainerEnum container() {
    return InteractContainerEnum.CHEST;
  }

  @Override
  public int rows() {
    return 3;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jwardchest.editor.gui_title"));
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    WardProfile profile = store.effective(profileId);
    canvas.clear();

    canvas.set(10, Canvas.button(profile.container().icon(),
        i18n.tr("jwardchest.editor.container_type", profile.container().displayName()),
        List.of(
            i18n.tr("jwardchest.editor.container_type_current", profile.container().id(), profile.slots()),
            i18n.tr("jwardchest.editor.click"))));

    String code = profile.inPreset() ? profile.presetCode() : WardProfile.UNSET_CODE;
    canvas.set(11, Canvas.button(Material.NAME_TAG,
        i18n.tr("jwardchest.editor.code_label"),
        List.of(
            i18n.tr("jwardchest.editor.code_current", code),
            i18n.tr("jwardchest.editor.code_hint"))));

    canvas.set(12, Canvas.button(Material.CHEST_MINECART,
        i18n.tr("jwardchest.editor.items_label"),
        List.of(
            i18n.tr("jwardchest.editor.items_count", profile.entryCount(), profile.totalWeight()),
            i18n.tr("jwardchest.editor.click"))));

    canvas.set(13, Canvas.button(Material.HOPPER,
        i18n.tr("jwardchest.editor.maxdraw"),
        WardEditorGui.maxDrawLore(profile)));

    canvas.set(14, Canvas.button(Material.OAK_SIGN,
        i18n.tr("jwardchest.editor.title_label"),
        List.of(
            i18n.tr("jwardchest.editor.title_current", profile.title()),
            i18n.tr("jwardchest.editor.click"))));

    canvas.set(15, Canvas.button(Material.CLOCK,
        i18n.tr("jwardchest.editor.prefilter"),
        List.of(
            i18n.tr("jwardchest.editor.prefilter_current",
                profile.prefilterEnabled() ? profile.prefilterSeconds() + "s" : "0"),
            i18n.tr("jwardchest.editor.click"))));

    canvas.set(16, Canvas.button(Material.SPYGLASS,
        i18n.tr("jwardchest.editor.preview"),
        List.of(
            i18n.tr("jwardchest.editor.preview_hint"),
            i18n.tr("jwardchest.editor.preview_hint_instant"))));

    canvas.set(17, Canvas.button(Material.COMPARATOR,
        i18n.tr("jwardchest.editor.distribution", profile.countDistribution().id()),
        List.of(
            i18n.tr("jwardchest.editor.distribution_hint"),
            i18n.tr("jwardchest.dist.count_label", profile.countDistribution().id()),
            i18n.tr("jwardchest.dist.amount_label", profile.amountDistribution().id()),
            i18n.tr("jwardchest.editor.click"))));
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    Player player = click.player();
    WardProfile profile = store.effective(profileId);

    switch (click.slot()) {
      case 10 -> open(player, new WardContainerPickerPage(editor, store, profileId, block));
      case 11 -> {
        String old = profile.inPreset() ? profile.presetCode() : WardProfile.UNSET_CODE;
        openChat(ctx, "code",
            ChatPrompt.of(i18n.tr("jwardchest.input.field.code"), old)
                .hint(i18n.tr("ui.input.unset_hint")),
            value -> {
              String resolved = value.equalsIgnoreCase("unset") ? WardProfile.UNSET_CODE : value;
              store.setPresetCode(profileId, resolved);
              player.sendMessage(i18n.tr("jwardchest.editor.code_updated", resolved));
              store.flush();
            });
      }
      case 12 -> open(player, new WardItemEditorPage(editor, store, profileId, block));
      case 13 -> open(player, new WardMaxDrawPage(editor, store, profileId, block));
      case 14 -> openChat(ctx, "title",
          ChatPrompt.of(i18n.tr("jwardchest.input.field.title"), profile.title()),
          value -> {
            WardProfile updated = store.effective(profileId);
            updated.title(value);
            store.save(updated);
            player.sendMessage(i18n.tr("jwardchest.editor.title_updated", value));
            store.flush();
          });
      case 15 -> openChat(ctx, "prefilter",
          ChatPrompt.of(i18n.tr("jwardchest.input.field.prefilter"),
              String.valueOf(profile.prefilterSeconds())),
          value -> {
            int seconds = WardEditorGui.parseIntOrDefault(value, profile.prefilterSeconds());
            WardProfile updated = store.effective(profileId);
            updated.prefilterSeconds(seconds);
            store.save(updated);
            player.sendMessage(i18n.tr("jwardchest.editor.prefilter_updated", seconds));
            store.flush();
          });
      case 16 -> editor.openPreview(player, profileId, block, click.shift());
      case 17 -> open(player, new WardDistributionPage(editor, store, profileId, block));
      default -> {
        return Verdict.IGNORED;
      }
    }
    return Verdict.HANDLED;
  }

  @Override
  public void onDetach(IPageContext ctx, DetachReasonEnum why) {
    super.onDetach(ctx, why);
    store.flush();
    if (why == DetachReasonEnum.CLOSED) {
      notifyLeft(ctx.player());
    }
  }

  /**
   * 进子界面
   * <p>
   * 用 {@link PagePriorityEnum#INSTANT}：主界面被<b>挂起</b>而不是终止，于是子界面按 ESC / E
   * 关闭时仲裁器会自动把主界面恢复回来——这就是「返回上一级」。
   * 反过来用 {@code REPLACE} 会把主界面终结掉，二级页面一关就什么都不剩了。
   */
  private static void open(Player player, WardSubPage page) {
    PageArbiter.get().open(player, page, PagePriorityEnum.INSTANT);
  }

  /** 发起一次文本输入：以 INSTANT 压在编辑界面之上，结束后自动回到这里 */
  private void openChat(IPageContext ctx, String field, ChatPrompt prompt,
      java.util.function.Consumer<String> accept) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    PageArbiter.get().open(player,
        new WardFieldChatPage(KEY + "." + field, profileId.toString(), prompt, accept),
        PagePriorityEnum.INSTANT);
  }

  /**
   * 真退出编辑时给一句反馈
   * <p>
   * 子界面用 {@code INSTANT} 进入（本页被挂起而不是终止），所以真正退干净的路径只有两种：
   * 玩家在本页按 ESC，或者子界面被 {@code REPLACE} 掉且没有可恢复的下层。
   * 两种都会让本页收到 {@code CLOSED}，此时延迟一刻确认队列里确实没有我们自己的页面。
   */
  private static void notifyLeft(@Nullable Player player) {
    if (player == null) {
      return;
    }
    Bukkit.getScheduler().runTask(Joyous.plugin, () -> {
      if (!player.isOnline()) {
        return;
      }
      if (PageArbiter.get().visible(player) != null) {
        return;
      }
      player.sendMessage(i18n.tr("jwardchest.editor.closed"));
    });
  }
}
