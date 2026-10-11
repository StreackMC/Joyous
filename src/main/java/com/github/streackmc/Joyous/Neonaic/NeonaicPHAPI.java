package com.github.streackmc.Joyous.Neonaic;

import java.util.function.Supplier;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous._Model.JoyousPHAPIhandler;

/**
 * Neonaic 模块的占位符处理器。
 * <p>
 * 提供：{@code %joyous_neonaic_name%}（Bot 名）、{@code %joyous_neonaic_subname%}（Bot 副名）。
 * 为兼容配置文档中带点的写法，参数中的 {@code .} 会被归一化为 {@code _}。
 *
 * @since 0.3.0
 */
public class NeonaicPHAPI implements JoyousPHAPIhandler {

  private final Supplier<String> name;
  private final Supplier<String> subname;

  public NeonaicPHAPI(Supplier<String> name, Supplier<String> subname) {
    this.name = name;
    this.subname = subname;
  }

  @Override
  public String onPlaceholderRequest(Player player, String params) {
    if (params == null) return null;
    // 归一化：容忍文档里的 %joyous.neonaic.name% 写法
    String key = params.toLowerCase().replace('.', '_');
    switch (key) {
      case "neonaic_name":
        return name.get();
      case "neonaic_subname":
        return subname.get();
      case "neonaic":
        return name.get();
      default:
        return null;
    }
  }
}
