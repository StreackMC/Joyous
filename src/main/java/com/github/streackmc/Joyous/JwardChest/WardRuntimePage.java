package com.github.streackmc.Joyous.JwardChest;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import com.github.streackmc.Joyous.utils.interact.Canvas;
import com.github.streackmc.Joyous.utils.interact.Click;
import com.github.streackmc.Joyous.utils.interact.ClickPolicy;
import com.github.streackmc.Joyous.utils.interact.DetachReason;
import com.github.streackmc.Joyous.utils.interact.InteractContainer;
import com.github.streackmc.Joyous.utils.interact.InteractHolder;
import com.github.streackmc.Joyous.utils.interact.PageContext;
import com.github.streackmc.Joyous.utils.interact.SimpleGuiPage;

import net.kyori.adventure.text.Component;

/**
 * 战利品箱的运行时页面
 * <p>
 * 一个 {@link WardSession} 可以同时被多人打开，每个人手里的界面互不相同（各自的搜索进度），
 * 但内容共享。所以：
 * <ul>
 *   <li>{@link #render} 必须<b>每次从会话现算</b>，不能缓存 ItemStack；</li>
 *   <li>别人领取后刷新所有观看者，仍由 {@code WardSearchController.broadcast} 直接写进各自的界面，
 *       不经过这里——那是「同一份内容多处显示」，重画整页反而是浪费。</li>
 * </ul>
 * 可保留性：真容器可以（内容与进度都在会话里），预览不行（它本来就是一次性的演示）。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class WardRuntimePage extends SimpleGuiPage {

  static final String KEY = "ward.runtime";

  private final WardProfile profile;
  private final WardSession session;
  private final WardSearchController search;
  private final WardSessionManager sessions;
  private final boolean animate;

  WardRuntimePage(WardProfile profile, WardSession session, WardSearchController search,
      WardSessionManager sessions, boolean animate) {
    this.profile = profile;
    this.session = session;
    this.search = search;
    this.sessions = sessions;
    this.animate = animate;
  }

  /** 从界面反查会话；不是运行时页面时返回 null（搜索任务用它确认「玩家还在看这个容器」） */
  @Nullable
  static WardSession sessionOf(@Nullable Inventory inventory) {
    InteractHolder holder = InteractHolder.of(inventory);
    if (holder != null && holder.page() instanceof WardRuntimePage page) {
      return page.session;
    }
    return null;
  }

  WardSession session() {
    return session;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String purpose() {
    return session.key();
  }

  @Override
  public boolean keepable() {
    return !session.preview();
  }

  @Override
  public InteractContainer container() {
    return InteractContainer.byId(profile.container().id());
  }

  @Override
  public int rows() {
    return profile.rows();
  }

  @Override
  public Component title(Player viewer) {
    return WardGui.title(viewer, profile.title());
  }

  @Override
  public ClickPolicy policy() {
    // 上半区独占；背包只拦「可能把物品搬进上半区」的操作，玩家整理背包放行
    return ClickPolicy.TRANSFER_ONLY;
  }

  @Override
  public void render(PageContext ctx, Canvas canvas) {
    Player viewer = ctx.player();
    if (viewer == null) {
      return;
    }
    // 不走动画的场合（编辑界面的预览）把内容全部摆出来，否则按各自进度画三态
    search.renderInto(session, viewer, canvas, !animate);
  }

  @Override
  public Verdict onClick(PageContext ctx, Click click) {
    Player player = click.player();
    if (!click.top()) {
      // 背包侧：Shift+左键 = 把这一格登记成「放入」
      if (click.shift()) {
        search.placeFromInventory(player, session, click.slot());
      }
      return Verdict.HANDLED;
    }
    if (session.hasItem(click.slot())) {
      // 点击即领取（预览会话会被 claim 自己拒掉）
      search.claim(player, session, click.slot());
      return Verdict.HANDLED;
    }
    if (click.holding()) {
      int amount = click.right() ? 1 : click.cursor().getAmount();
      search.placeFromCursor(player, session, click.slot(), amount);
    }
    return Verdict.HANDLED;
  }

  // ──────────────────────────────────────────────
  // 生命周期：登记观看者、起停搜索任务
  // ──────────────────────────────────────────────

  @Override
  public void onAttach(PageContext ctx) {
    super.onAttach(ctx);
    bind(ctx);
  }

  @Override
  public void onResume(PageContext ctx) {
    super.onResume(ctx);
    bind(ctx);
  }

  @Override
  public void onSuspend(PageContext ctx) {
    unbind(ctx);
    super.onSuspend(ctx);
  }

  @Override
  public void onDetach(PageContext ctx, DetachReason why) {
    unbind(ctx);
    super.onDetach(ctx, why);
    if (session.preview()) {
      // 预览是临时演示：关闭即整个丢弃，重开时重新抽取
      sessions.remove(session.key());
    }
  }

  /** 界面已开好：登记为观看者，并按需启动搜索动画 */
  private void bind(PageContext ctx) {
    Player player = ctx.player();
    Inventory view = inventory();
    if (player == null || view == null) {
      return;
    }
    search.stop(player); // 清理可能残留的旧任务
    session.bindViewer(player.getUniqueId(), view);
    session.touch();
    if (animate && !session.searchedAll(player.getUniqueId())) {
      search.startSearch(player, profile, session);
    }
  }

  /** 界面要收起来了：停任务、摘掉观看者（进度与内容留在会话里） */
  private void unbind(PageContext ctx) {
    Player player = ctx.player();
    if (player == null) {
      return;
    }
    search.stop(player);
    session.unbindViewer(player.getUniqueId());
    session.touch();
  }
}
