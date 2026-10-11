package com.github.streackmc.Joyous.Neonaic.chat;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Neonaic.NeonaicConfig;
import com.github.streackmc.Joyous.Neonaic.NeonaicText;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeException;
import com.github.streackmc.Joyous.Neonaic.bridge.BridgeProtocol;
import com.github.streackmc.Joyous.Neonaic.bridge.NeonaicBridge;
import com.github.streackmc.Joyous.jlogger;

/**
 * 身份绑定：**绑定代码流程**（自助绑定）与解绑。
 * <p>
 * 流程：
 * <ol>
 *   <li>玩家在游戏内执行 {@code /neonaicbindidentity}（名/别名见配置）→ 本服务生成一次性代码，
 *       有效期缺省 5 分钟，并只保留该玩家最新的一个代码；</li>
 *   <li>玩家在聊天软件中执行 {@code /joyous:bind <code>} → Neonaic 以 {@code identity.claim}
 *       请求本端换取 MC 身份（UUID/名称），成功后 Neonaic 侧保存
 *       {@code 用户标识(qUSR#…) ↔ MC UUID} 的关联；代码随即作废；</li>
 *   <li>玩家执行 {@code /neonaicunbindidentity}（或 Neonaic 侧 {@code /joyous:bind clear}）→
 *       解绑：前者由本端发起 {@code identity.unbind}，后者由 Neonaic 侧自行移除。</li>
 * </ol>
 * 关联的**权威持有方是 Neonaic**：本端只持有「临时代码 → UUID」的映射（一次性、短时效）。
 *
 * @since 0.3.0
 */
public class IdentityBindingService {

  /** 一个待使用的绑定代码 */
  public record PendingCode(String code, UUID uuid, String name, long expiresAt) {
    /** 剩余有效秒数（向上取整，最小 0） */
    public long remainingSeconds() {
      long ms = expiresAt - System.currentTimeMillis();
      return ms <= 0 ? 0 : (ms + 999) / 1000;
    }
  }

  /** 代码字母表：去掉易混字符 0/O/1/I/L */
  private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

  private final NeonaicConfig config;
  private final NeonaicBridge bridge;
  private final SecureRandom random = new SecureRandom();

  /** 代码 → 待认领信息 */
  private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
  /** 玩家 → 上次请求代码的时间戳 */
  private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();

  public IdentityBindingService(NeonaicConfig config, NeonaicBridge bridge) {
    this.config = config;
    this.bridge = bridge;
  }

  // ---- 生成代码 ----

  /**
   * 为玩家生成一个新的绑定代码（覆盖该玩家之前的代码）。
   *
   * @param uuid 玩家 UUID
   * @param name 玩家名（仅用于回显与日志）
   * @throws BridgeException {@code rate_limited} 冷却未过
   */
  public PendingCode requestCode(UUID uuid, String name) {
    sweepExpired();
    long now = System.currentTimeMillis();

    int cooldown = config.bindRequestCooldownSeconds();
    if (cooldown > 0) {
      Long last = lastRequest.get(uuid);
      if (last != null && now - last < cooldown * 1000L) {
        long remain = (cooldown * 1000L - (now - last) + 999) / 1000;
        throw new BridgeException(BridgeProtocol.Errors.RATE_LIMITED,
            "请求过于频繁，请 " + remain + " 秒后再试");
      }
      lastRequest.put(uuid, now);
    }

    // 同一玩家只保留最新代码
    codes.values().removeIf(p -> p.uuid().equals(uuid));

    long expiresAt = now + config.bindCodeTtlSeconds() * 1000L;
    PendingCode pending = new PendingCode(newCode(), uuid, name, expiresAt);
    codes.put(pending.code(), pending);
    jlogger.debug("[Neonaic] 已为 %s 生成绑定代码（%d 秒有效）", name, config.bindCodeTtlSeconds());
    return pending;
  }

  // ---- 认领（identity.claim 方法处理器，Neonaic → 本端） ----

  /**
   * 用代码换取 MC 身份。成功即作废该代码并通知在线玩家。
   *
   * @param payload {@code { code, chatId, user?, profile? }}
   * @return {@code { bound:true, mc:{name,uuid}, profile?, chatId? }}
   * @throws BridgeException {@code invalid_argument} / {@code not_bound}
   */
  public Map<String, Object> claim(Map<String, Object> payload) {
    sweepExpired();

    String code = normalize(str(payload.get("code")));
    if (code.isEmpty()) {
      throw new BridgeException(BridgeProtocol.Errors.INVALID_ARGUMENT, "identity.claim 缺少 code");
    }

    PendingCode pending = codes.get(code);
    if (pending == null) {
      throw new BridgeException(BridgeProtocol.Errors.NOT_BOUND, "绑定代码无效或已被使用");
    }
    if (System.currentTimeMillis() > pending.expiresAt()) {
      codes.remove(code);
      throw new BridgeException(BridgeProtocol.Errors.NOT_BOUND, "绑定代码已过期");
    }
    codes.remove(code); // 一次性：成功认领即作废

    // 通知在线的本人（失败不影响绑定结果）
    notifyOwner(pending);

    jlogger.info("[Neonaic] 绑定代码已认领：%s(%s) ← chatId=%s",
        pending.name(), pending.uuid(), str(payload.get("chatId")));

    Map<String, Object> mc = new LinkedHashMap<>();
    mc.put("name", pending.name());
    mc.put("uuid", pending.uuid().toString());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("bound", true);
    result.put("mc", mc);
    Object chatId = payload.get("chatId");
    if (chatId != null) result.put("chatId", chatId);
    Object profile = payload.get("profile");
    if (profile != null) result.put("profile", profile);
    return result;
  }

  /** 当前是否有该玩家的待认领代码（供命令反馈/诊断） */
  public boolean hasPendingCode(UUID uuid) {
    sweepExpired();
    return codes.values().stream().anyMatch(p -> p.uuid().equals(uuid));
  }

  // ---- 解绑 ----

  /** 请求 Neonaic 解除该 MC 身份的全部绑定 */
  public CompletableFuture<Map<String, Object>> unbind(Player player) {
    Map<String, Object> mc = new LinkedHashMap<>();
    mc.put("name", player.getName());
    mc.put("uuid", player.getUniqueId().toString());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mc", mc);
    return bridge.identityUnbind(payload);
  }

  // ---- 内部 ----

  /** 通知在线的玩家「绑定成功」；任何失败（如无 Bukkit 环境/i18n 未就绪）都不影响绑定结果 */
  private void notifyOwner(PendingCode pending) {
    try {
      Player owner = Bukkit.getPlayer(pending.uuid());
      if (owner != null) {
        NeonaicText.send(owner, NeonaicText.tr("neonaic.bind.claimed"));
      }
    } catch (Exception e) {
      jlogger.debug("[Neonaic] 绑定成功通知发送失败（非致命）：%s", e.getLocalizedMessage());
    }
  }

  private void sweepExpired() {
    long now = System.currentTimeMillis();
    codes.values().removeIf(p -> now > p.expiresAt());
  }

  private String newCode() {
    int len = config.bindCodeLength();
    for (int attempt = 0; attempt < 8; attempt++) {
      StringBuilder sb = new StringBuilder(len);
      for (int i = 0; i < len; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
      String code = sb.toString();
      if (!codes.containsKey(code)) return code;
    }
    // 极小概率：加长一位再试一次
    StringBuilder sb = new StringBuilder(len + 2);
    for (int i = 0; i < len + 2; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    return sb.toString();
  }

  private static String normalize(String code) {
    return code == null ? "" : code.trim().toUpperCase().replace("-", "").replace(" ", "");
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }
}
