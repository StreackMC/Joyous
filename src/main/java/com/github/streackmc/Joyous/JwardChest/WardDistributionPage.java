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
 * 数量分布
 * <p>
 * 左端两格管「结果组总条数」，右端两格管「单条结果的物品数量」；
 * 每对分别是「分布」与「曲线指数」。左键前进、右键后退，Shift+点击则直接输入。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardDistributionPage extends WardSubPage {

  static final String KEY = "ward.dist";

  WardDistributionPage(WardEditorGui editor, WardProfileStore store, UUID profileId,
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
    return Canvas.title(viewer, i18n.tr("jwardchest.dist.title"));
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    WardProfile profile = profile();
    canvas.clear();
    canvas.set(0, WardEditorGui.distributionButton(profile, true));
    canvas.set(1, WardEditorGui.curveButton(profile, true));
    canvas.set(7, WardEditorGui.distributionButton(profile, false));
    canvas.set(8, WardEditorGui.curveButton(profile, false));
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;
    }
    boolean forCount;
    boolean isCurve;
    switch (click.slot()) {
      case 0 -> {
        forCount = true;
        isCurve = false;
      }
      case 1 -> {
        forCount = true;
        isCurve = true;
      }
      case 7 -> {
        forCount = false;
        isCurve = false;
      }
      case 8 -> {
        forCount = false;
        isCurve = true;
      }
      default -> {
        return Verdict.HANDLED;
      }
    }

    Player player = click.player();
    WardProfile profile = profile();
    String name = i18n.tr(forCount ? "jwardchest.dist.count_name" : "jwardchest.dist.amount_name");

    if (click.shift()) {
      if (isCurve) {
        double current = forCount ? profile.countCurve() : profile.amountCurve();
        openChat(ctx, "curve." + (forCount ? "count" : "amount"),
            ChatPrompt.of(i18n.tr("jwardchest.input.field.curve"), formatCurve(current)),
            value -> {
              WardProfile updated = profile();
              double fallback = forCount ? updated.countCurve() : updated.amountCurve();
              double parsed = parseDouble(value, fallback);
              double clamped = WardProfile.clampCurve(parsed);
              if (Math.abs(parsed - clamped) > 1e-9) {
                tell(player, "jwardchest.dist.curve_range",
                    formatCurve(WardProfile.MIN_CURVE), formatCurve(WardProfile.MAX_CURVE));
              }
              WardEditorGui.applyCurve(updated, forCount, clamped);
              save(updated);
              tell(player, "jwardchest.dist.curve_updated", name, formatCurve(clamped));
            });
      } else {
        WardDistribution current =
            forCount ? profile.countDistribution() : profile.amountDistribution();
        openChat(ctx, "dist." + (forCount ? "count" : "amount"),
            ChatPrompt.of(i18n.tr("jwardchest.input.field.dist"), current.id()),
            value -> {
              WardDistribution parsed = WardDistribution.parse(value);
              if (parsed == null) {
                tell(player, "jwardchest.dist.invalid", value, WardDistribution.ids());
                return;
              }
              WardProfile updated = profile();
              if (forCount) {
                updated.countDistribution(parsed);
              } else {
                updated.amountDistribution(parsed);
              }
              save(updated);
              tell(player, "jwardchest.dist.updated", name, parsed.id());
            });
      }
      return Verdict.HANDLED;
    }

    if (isCurve) {
      double step = click.right() ? -0.5 : 0.5;
      double current = forCount ? profile.countCurve() : profile.amountCurve();
      WardEditorGui.applyCurve(profile, forCount, current + step);
    } else {
      WardDistribution current =
          forCount ? profile.countDistribution() : profile.amountDistribution();
      WardDistribution next = click.right() ? current.previous() : current.next();
      if (forCount) {
        profile.countDistribution(next);
      } else {
        profile.amountDistribution(next);
      }
    }
    save(profile);
    repaint(ctx);
    return Verdict.HANDLED;
  }

  private static String formatCurve(double curve) {
    return WardEditorGui.formatCurve(curve);
  }

  private static double parseDouble(String value, double fallback) {
    try {
      return Double.parseDouble(value.trim());
    } catch (Exception e) {
      return fallback;
    }
  }
}
