package com.github.streackmc.Joyous.JwardChest;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;

import net.kyori.adventure.text.Component;

/**
 * 箱子行数选择
 * <p>
 * 只对「行数可变」的外形有意义（箱子 / 木桶等）。六格分别代表 1~6 行，
 * 物品数量就是行数，一眼就能看出容量。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardRowsPage extends WardSubPage {

  static final String KEY = "ward.picker.rows";

  WardRowsPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
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
    return 1;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jwardchest.rows.title"));
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    WardProfile profile = profile();
    canvas.clear();
    for (int rows = 1; rows <= 6; rows++) {
      boolean current = rows == profile.rows();
      var item = Canvas.button(Material.RED_STAINED_GLASS_PANE,
          i18n.tr("jwardchest.rows.entry", rows, rows * 9),
          List.of(current
              ? i18n.tr("jwardchest.picker.current")
              : i18n.tr("jwardchest.editor.click")),
          current);
      item.setAmount(rows);
      canvas.set(rows - 1, item);
    }
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    int slot = click.slot();
    if (slot < 0 || slot > 5) {
      return Verdict.HANDLED;
    }
    Player player = click.player();
    WardProfile profile = profile();
    profile.rows(slot + 1);
    save(profile);
    tell(player, "jwardchest.rows.updated", profile.rows());
    backToEditor(ctx);
    return Verdict.HANDLED;
  }
}
