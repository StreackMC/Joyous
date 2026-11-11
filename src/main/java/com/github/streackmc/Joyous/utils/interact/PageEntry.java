package com.github.streackmc.Joyous.utils.interact;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.utils.jlogger;

/**
 * 一个页面在仲裁器里的登记项
 * <p>
 * 既是内部状态载体，也是交给页面实现的 {@link PageContext}。
 * 生命周期与队列位置全部由 {@link PlayerPages} 维护，这里只存数据。
 *
 * @author Neonai
 * @since 0.7.0
 */
final class PageEntry implements PageContext {

  private final PlayerPages owner;
  @Nullable
  private final Player player;
  private final JoyousPage page;
  /** 提交时刻，用于同帧多条请求的排序 */
  private final long submittedAt;
  /** 同一毫秒内提交时的次序，保证排序稳定 */
  private final long sequence;

  private PageState state = PageState.PENDING;

  PageEntry(PlayerPages owner, @Nullable Player player, JoyousPage page, long submittedAt, long sequence) {
    this.owner = owner;
    this.player = player;
    this.page = page;
    this.submittedAt = submittedAt;
    this.sequence = sequence;
  }

  JoyousPage page() {
    return page;
  }

  long submittedAt() {
    return submittedAt;
  }

  long sequence() {
    return sequence;
  }

  /** 状态由 {@link PlayerPages} 改写 */
  void state(PageState next) {
    this.state = next;
  }

  /**
   * 是否已排队过久
   * <p>
   * 只对 {@link PageState#PENDING} 生效：被挂起的页面是玩家「未完成的工作」，不该因为放久了就消失。
   */
  boolean expired() {
    return state == PageState.PENDING
        && System.currentTimeMillis() - submittedAt > PlayerPages.PENDING_TTL_MS;
  }

  /** 带异常兜底的 {@link JoyousPage#stillValid()}：页面自己炸了就当它失效 */
  boolean stillValid() {
    try {
      return page.stillValid();
    } catch (Exception e) {
      jlogger.warning("页面有效性检查出错，按失效处理：%s", describe(), e);
      return false;
    }
  }

  /** 排序键：先按提交时刻，再按同毫秒内的次序 */
  boolean submittedBefore(PageEntry other) {
    return submittedAt < other.submittedAt
        || (submittedAt == other.submittedAt && sequence < other.sequence);
  }

  /** 不带状态的短标签：key 或 key/purpose */
  String label() {
    String p = page.purpose();
    return page.key() + (p == null || p.isEmpty() ? "" : "/" + p);
  }

  /** 诊断用的描述：key/purpose(state) */
  String describe() {
    return label() + "(" + state + ")";
  }

  // ──────────────────────────────────────────────
  // PageContext / PageHandle
  // ──────────────────────────────────────────────

  @Override
  @Nullable
  public Player player() {
    return player;
  }

  @Override
  public String key() {
    return page.key();
  }

  @Override
  public String purpose() {
    String p = page.purpose();
    return p == null ? "" : p;
  }

  @Override
  public PageState state() {
    return state;
  }

  @Override
  public boolean close() {
    return owner.close(this);
  }

  @Override
  public void toFront() {
    owner.toFront(this);
  }
}
