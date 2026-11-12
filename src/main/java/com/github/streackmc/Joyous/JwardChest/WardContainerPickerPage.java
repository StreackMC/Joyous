package com.github.streackmc.Joyous.JwardChest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;

import net.kyori.adventure.text.Component;

/**
 * 容器类型选择
 * <p>
 * 一屏列出所有可用外形；选中「行数可变」的那种（箱子 / 木桶等）会顺势跳到行数选择，
 * 其余的选完即回主界面。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardContainerPickerPage extends WardSubPage {

  static final String KEY = "ward.picker.container";

  WardContainerPickerPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
      @Nullable Location block) {
    super(editor, store, profileId, block);
  }

  @Override
  public String key() {
    return KEY;
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
    return Canvas.title(viewer, i18n.tr("jwardchest.picker.title"));
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    WardProfile profile = profile();
    canvas.clear();
    int slot = 0;
    for (WardContainerType type : WardContainerType.values()) {
      if (slot >= canvas.size()) {
        break;
      }
      boolean current = type == profile.container();
      canvas.set(slot++, Canvas.button(type.icon(), type.displayName(),
          List.of(
              i18n.tr("jwardchest.picker.size", type.slots(profile.rows())),
              current ? i18n.tr("jwardchest.picker.current") : i18n.tr("jwardchest.editor.click")),
          current));
    }
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    WardContainerType[] types = WardContainerType.values();
    int slot = click.slot();
    if (slot < 0 || slot >= types.length) {
      return Verdict.HANDLED;
    }
    Player player = click.player();
    WardContainerType picked = types[slot];
    WardProfile profile = profile();
    profile.container(picked);
    save(profile);

    if (picked.variableRows()) {
      // 行数只对「行数可变」的外形有意义，顺势问一下
      open(player, new WardRowsPage(editor, store, profileId, block));
      return Verdict.HANDLED;
    }
    tell(player, "jwardchest.picker.updated", picked.displayName());
    backToEditor(ctx);
    return Verdict.HANDLED;
  }
}
