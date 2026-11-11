package com.github.streackmc.Joyous.utils.interact.input;

import java.util.List;

import javax.annotation.Nullable;

/**
 * 聊天栏输入的一条提示
 *
 * @param label      字段名（已经本地化好的文本，例如「容器标题」）
 * @param oldValue   旧值，非空时会附一条「点击粘贴旧值」；可为 null
 * @param extraHints 额外提示行（已经本地化），例如「输入 unset 改回默认」
 * @author Neonai
 * @since 0.7.0
 */
public record ChatPrompt(String label, @Nullable String oldValue, List<String> extraHints) {

  public ChatPrompt {
    extraHints = extraHints == null ? List.of() : List.copyOf(extraHints);
  }

  /** 只带字段名 */
  public static ChatPrompt of(String label) {
    return new ChatPrompt(label, null, List.of());
  }

  /** 字段名 + 旧值 */
  public static ChatPrompt of(String label, @Nullable String oldValue) {
    return new ChatPrompt(label, oldValue, List.of());
  }

  /** 追加一条提示行 */
  public ChatPrompt hint(String line) {
    var merged = new java.util.ArrayList<>(extraHints);
    merged.add(line);
    return new ChatPrompt(label, oldValue, merged);
  }
}
