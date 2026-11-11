/**
 * 页面仲裁：排队、生命周期与优先度。
 * <p>
 * 这一层只管「什么时候轮到谁、被挤下去怎么办、什么时候算结束」，不涉及界面绘制、
 * 也不订阅任何 Bukkit 事件（事件路由在 {@code chestui} 包里）。
 *
 * <h2>分层</h2>
 * <ul>
 *   <li>{@code interact}（本包）——仲裁内核：{@link com.github.streackmc.Joyous.utils.interact.PageArbiter}
 *       与每玩家的队列状态机；</li>
 *   <li>{@code interact.chestui}——容器界面：外形、渲染、点击语义与事件路由；</li>
 *   <li>{@code interact.input}——文本输入：聊天栏与铁砧（待实现）。</li>
 * </ul>
 *
 * <h2>命名约定</h2>
 * 接口以 {@code I} 前缀、抽象类以 {@code Abstract} 前缀、枚举以 {@code Enum} 后缀区分，
 * 其余（record / 普通类）保持原名。
 *
 * @author Neonai
 * @since 0.7.0
 */
package com.github.streackmc.Joyous.utils.interact;
