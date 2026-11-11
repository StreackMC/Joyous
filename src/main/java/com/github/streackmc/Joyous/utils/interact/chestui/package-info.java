/**
 * 容器界面类页面。
 * <p>
 * 页面只回答四件事：外形、标题、每格画什么、某格点了算什么；排队与生命周期由
 * {@link com.github.streackmc.Joyous.utils.interact.PageArbiter} 决定。
 *
 * <h2>包里有什么</h2>
 * <ul>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.IGuiInteract}——页面的容器界面契约；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.AbstractGuiPage}——开界面/重绘/关闭的通用基类；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.Canvas}——渲染画布与文本/按钮构造；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.InteractHolder}——界面与页面的唯一绑定方式；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.InteractContainerEnum}——可用的容器外形；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.Click} 与
 *       {@link com.github.streackmc.Joyous.utils.interact.chestui.ClickPolicy}——归一化点击与取消策略；</li>
 *   <li>{@link com.github.streackmc.Joyous.utils.interact.chestui.GuiRouter}——点击/拖拽/关闭的事件路由。</li>
 * </ul>
 *
 * <h2>关键约定</h2>
 * <ul>
 *   <li>认界面只靠 {@code InteractHolder}，不用标题字符串、也不靠「当前可见页」猜测；</li>
 *   <li>{@code Click.slot} 是<b>相对所点击容器</b>的槽位，背包侧不是 rawSlot；</li>
 *   <li>关闭事件只有 {@code state() == VISIBLE} 时才算玩家主动退出。</li>
 * </ul>
 *
 * @author Neonai
 * @since 0.7.0
 */
package com.github.streackmc.Joyous.utils.interact.chestui;
