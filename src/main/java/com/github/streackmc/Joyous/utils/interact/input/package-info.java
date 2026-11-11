/**
 * 文本输入类页面：聊天栏（已实现）与铁砧（待实现）。
 *
 * <h2>包里有什么</h2>
 * <ul>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.input.ITextInteract}——「给个初值 → 收到文本 / 被取消」的公共契约；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.input.IChatInteract}——聊天栏版本，多一条「给玩家看的提示」；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.input.ChatPrompt}——提示内容（字段名 / 旧值 / 额外提示行）；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.input.AbstractChatPage}——发提示、超时自动收掉；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.input.ChatInput}——截获聊天事件、判 {@code cancel}、回主线程。</li>
 * </ul>
 *
 * <h2>与容器界面的配合</h2>
 * 输入页用 {@code INSTANT} 压在编辑界面之上：编辑界面被挂起而不是关掉，输入结束（提交 / 取消 / 超时）
 * 后仲裁器自动把它恢复回来。**不需要再写「把界面开回来」的回调**——旧实现里的 {@code onFinish}
 * 就是这个能力的临时替代品。
 *
 * <h2>待实现：铁砧</h2>
 * 铁砧输入必须用真实 {@code AnvilMenu}
 * （{@code MenuType.ANVIL.builder().location(..).checkReachable(false).build(player)}），
 * 文本用 {@code AnvilView#getRenameText()} 读；{@code createInventory(holder, ANVIL)} 的假铁砧
 * 收不到打字（{@code handleRenameItem} 要求 {@code containerMenu instanceof AnvilMenu}），
 * 且 {@code openAnvil} 自 1.21.4 起已弃用。
 *
 * @author Neonai
 * @since 0.7.0
 */
package com.github.streackmc.Joyous.utils.interact.input;
