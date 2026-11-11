/**
 * 文本输入类页面：聊天栏与铁砧。
 * <p>
 * 这一层还没实现，先把包留出来。计划中的内容：
 * <ul>
 *   <li>{@code ITextInteract}——「取初值 → 收到文本 → 取消」的公共契约，
 *       {@code ChatInteract} 与 {@code AnvilInteract} 只是两种 transport；</li>
 *   <li>{@code ChatInput}——聊天栏输入：pending 表、TTL、回主线程、
 *       「先登记再关界面」的顺序；</li>
 *   <li>{@code AnvilInput}——铁砧输入：必须用真实 {@code AnvilMenu}
 *       （{@code MenuType.ANVIL.builder().checkReachable(false)}），
 *       文本用 {@code AnvilView#getRenameText()} 读；假铁砧收不到打字。</li>
 * </ul>
 *
 * @author Neonai
 * @since 0.7.0
 */
package com.github.streackmc.Joyous.utils.interact.input;
