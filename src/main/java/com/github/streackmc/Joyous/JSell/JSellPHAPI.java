package com.github.streackmc.Joyous.JSell;

import java.util.Locale;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

/**
 * JSell 的占位符
 * <pre>
 *   %joyous_jsell_hand%          手持物品的<b>单个</b>价值
 *   %joyous_jsell_hand_total%    手持物品的<b>总</b>价值（单价 × 数量）
 *   %joyous_jsell_hand_amount%   手持物品的数量
 *   %joyous_jsell_hand_display%  单个价值的展示格式（带千分位）
 *   %joyous_jsell_hand_total_display%  总价值的展示格式（带千分位）
 *   %joyous_jsell_unit%          货币单位名（配置项）
 *   %joyous_jsell_entries%       可售物品条目数
 * </pre>
 * 不可售（或空手）一律返回 {@code 0}，这样计分板/悬浮字里不会出现空洞。
 *
 * @author kdxiaoyi
 * @since 0.8.0
 */
public final class JSellPHAPI implements JoyousPHAPIhandler {

  private final SellEntryStore entries;

  public JSellPHAPI(SellEntryStore entries) {
    this.entries = entries;
  }

  @Override
  @Nullable
  public String onPlaceholderRequest(@Nullable Player player, @NotNull String params) {
    String key = params == null ? "" : params.toLowerCase(Locale.ROOT);
    if (!key.startsWith("jsell_")) {
      return null;
    }
    key = key.substring("jsell_".length());

    switch (key) {
      case "unit":
        return JSellMain.CONF.unitName();
      case "entries":
        return String.valueOf(entries.size());
      default:
        break;
    }

    if (!key.startsWith("hand")) {
      return null;
    }
    if (player == null) {
      return "0";
    }
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand == null || hand.getType().isAir()) {
      return "0";
    }
    SellEntry entry = entries.find(hand);
    if (entry == null) {
      return "0";
    }
    String rest = key.substring("hand".length());
    double unit = entry.unitValue();
    double total = SellService.round(unit * hand.getAmount());

    return switch (rest) {
      case "" -> SellService.amountText(unit);
      case "_total" -> SellService.amountText(total);
      case "_amount" -> String.valueOf(hand.getAmount());
      case "_display" -> SellService.display(unit);
      case "_total_display" -> SellService.display(total);
      case "_unit" -> JSellMain.CONF.unitName();
      case "_name" -> JSellGui.prettyName(hand);
      default -> null;
    };
  }
}
