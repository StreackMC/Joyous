package com.github.streackmc.Joyous.JwardChest;

import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.i18n;
import com.github.streackmc.Joyous.utils.interact.IPageContext;
import com.github.streackmc.Joyous.utils.interact.chestui.Canvas;
import com.github.streackmc.Joyous.utils.interact.chestui.Click;
import com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum;
import com.github.streackmc.Joyous.utils.interact.input.ChatPrompt;

import net.kyori.adventure.text.Component;

/**
 * 各珍惜度的抽取数量上/下限
 * <p>
 * 两排：上排「最小」（地毯）= 保底，下排「最大」（羊毛）= 上限。行数随珍惜度档数自动长，
 * 所以容量必须按 {@link WardEditorGui#maxDrawRows()} 现算，不能沿用类加载时算死的常量
 * （{@code /jc reload} 改了档数之后，写死的容量会与槽位算法对不上）。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardMaxDrawPage extends WardSubPage {

  static final String KEY = "ward.maxdraw";

  WardMaxDrawPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
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
    // 上排 + 下排，各占 maxDrawRows() 行
    return WardEditorGui.maxDrawRows() * 2;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, i18n.tr("jwardchest.maxdraw.title"));
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    WardProfile profile = profile();
    canvas.clear();
    for (WardRarity rarity : WardRarity.list()) {
      canvas.set(WardEditorGui.maxDrawSlot(rarity.index(), false),
          WardEditorGui.drawButton(profile, rarity, true));
      canvas.set(WardEditorGui.maxDrawSlot(rarity.index(), true),
          WardEditorGui.drawButton(profile, rarity, false));
    }
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    int slot = click.slot();
    int blockSize = WardEditorGui.maxDrawRows() * 9;
    boolean floorRow;
    int index;
    if (slot >= 0 && slot < blockSize) {
      floorRow = true;
      index = slot;
    } else if (slot >= blockSize && slot < blockSize * 2) {
      floorRow = false;
      index = slot - blockSize;
    } else {
      return Verdict.HANDLED;
    }

    WardRarity rarity = WardRarity.byIndex(index);
    if (rarity == null) {
      return Verdict.HANDLED;
    }

    Player player = click.player();
    WardProfile profile = profile();
    int current = floorRow ? profile.minDraw(rarity) : profile.maxDraw(rarity);

    if (click.shift()) {
      openChat(ctx, "draw." + rarity.id() + (floorRow ? ".floor" : ".ceiling"),
          ChatPrompt.of(
              i18n.tr(floorRow
                  ? "jwardchest.input.field.min_draw"
                  : "jwardchest.input.field.max_draw",
                  rarity.displayName()),
              String.valueOf(current)),
          value -> {
            WardProfile updated = profile();
            int fallback = floorRow ? updated.minDraw(rarity) : updated.maxDraw(rarity);
            WardEditorGui.applyDraw(updated, rarity, floorRow, intOrDefault(value, fallback));
            save(updated);
          });
      return Verdict.HANDLED;
    }

    int delta = click.right() ? -1 : 1;
    WardEditorGui.applyDraw(profile, rarity, floorRow, current + delta);
    save(profile);
    repaint(ctx);
    return Verdict.HANDLED;
  }
}
