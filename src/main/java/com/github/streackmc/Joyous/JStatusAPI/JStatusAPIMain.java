package com.github.streackmc.Joyous.JStatusAPI;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.json.simple.JSONObject;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.response.Response;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.Joyous._Model.JoyousModel;
import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.types.HTTPServer;

/**
 * JStatusAPI 主类
 * 继承自 StreackMC/JStatusAPI 这一独立项目的代码
 * 
 * @author KimiAI 编写
 * @author kdxiaoyi 审计
 * @since 0.0.1
 */
public class JStatusAPIMain extends JoyousModel {
  public String MODEL_NAME() {
    return "JStatusAPI";
  }

  public JStatusAPIMain() {
  };

  public HTTPServer httpServer;

  public static final class CONF {// 动态获取以支持热重载
    public static final boolean whiteMode() {
      return Joyous.conf.getBoolean("JStatusAPI.white-mode", true);
    }

    public static final String corsHeader() {
      return Joyous.conf.getString("JStatusAPI.cors-allowed", "*");
    }

    public static final List<String> rawList() {
      return Joyous.conf.getListOfString("JStatusAPI.ph_list");
    }

    public static final String path(String submodelname) {
      String base = Joyous.conf.getString("JStatusAPI.baseurl", "/japi/");
      if (base.endsWith("/")) {
        return base + submodelname;
      } else {
        return base + "/" + submodelname;
      }
    }

    public static final long cache() {
      return Joyous.conf.getLong("JStatusAPI.cache", 60L)/* 配置文件为秒，自动转为毫秒 */ * 1000;
    }
  }

  JStatusApiSubhandler[] subhandlers = {
      new WebPhAPI(),
      new WebBanAPI(),
      new WebStatusAPI()
  };

  /**
   * 
   * @throws Exception
   * @since 0.0.1
   */
  @Override
  public void onEnable() throws Exception {
    this.httpServer = StreackLib.getHttpServer();
    if (this.httpServer == null) {
      jlogger.warn("StreackLib 中的 httpServer 服务器已被禁用，无法继续启用 JStatusAPI");
      return;
    }
    for (int i = 0; i < this.subhandlers.length; i++) {
      JStatusApiSubhandler h = this.subhandlers[i];
      try {
        jlogger.info("JStatusAPI 正在启用 %s 的处理模块", h.getSubhandlerName());
        this.httpServer.registerHandler(CONF.path(h.getPath()), h::handleRequest);
      } catch (Exception e) {
        jlogger.error("JStatusAPI 无法启用 %s 的处理模块：" + e.getLocalizedMessage(), h.getSubhandlerName(), e);
      }
    }
  }

  /**
   * 
   * @throws Exception
   * @since 0.0.1
   */
  @Override
  public void onDisable() throws Exception {
    if (this.httpServer == null) {
      return;
    }
    for (int i = 0; i < this.subhandlers.length; i++) {
      JStatusApiSubhandler h = this.subhandlers[i];
      try {
        jlogger.info("JStatusAPI 正在移除 %s 的处理模块", h.getSubhandlerName());
        this.httpServer.removeHandler(CONF.path(h.getPath()));
      } catch (Exception e) {
        jlogger.error("JStatusAPI 无法移除 %s 的处理模块：" + e.getLocalizedMessage(), h.getSubhandlerName(), e);
      }
    }
  }
}

/**
 * JStatusApiSubhandler
 * 
 * 用于处理协议下各子端点的消息，解耦合这块
 * 
 * <p>
 * 缓存在此统一声明：子端点只需提供「缓存键 + 数据构建函数」，
 * 由基类负责命中判断、有效期维护以及 retrieved_at / expires_at 字段的填写。
 */
abstract class JStatusApiSubhandler {
  abstract String getSubhandlerName();

  abstract String getPath();

  abstract Response handleRequest(IHTTPSession session) throws Exception;

  final static String CORS_HEADER = "Access-Control-Allow-Origin";

  /** 缓存槽：缓存键 → 已构建的数据体 */
  private final Map<String, JSONObject> cacheData = new ConcurrentHashMap<>();
  /** 缓存槽：缓存键 → 构建时间戳 */
  private final Map<String, Long> cacheTime = new ConcurrentHashMap<>();

  /** 无需操作 */
  JStatusApiSubhandler() {
  };

  /**
   * 声明式缓存：以 cacheKey 为粒度复用未过期的数据体。
   * <p>
   * 命中时直接复用既有数据体，仅刷新 retrieved_at；未命中（或已过期）时调用
   * builder 重新构建，并写入 retrieved_at / expires_at。有效期取自
   * {@link JStatusAPIMain.CONF#cache()}，当其不大于 0 时不缓存。
   *
   * @param cacheKey 缓存键，需能区分内容不同的请求（例如 target、payload）
   * @param builder  数据体构建函数，不应自行写入 retrieved_at / expires_at
   * @return 可直接序列化返回的数据体
   */
  protected final JSONObject cached(String cacheKey, Supplier<JSONObject> builder) {
    long now = System.currentTimeMillis();
    long ttl = JStatusAPIMain.CONF.cache();

    JSONObject hit = this.cacheData.get(cacheKey);
    Long builtAt = this.cacheTime.get(cacheKey);
    if (hit != null && builtAt != null && ttl > 0L && builtAt + ttl >= now) {
      hit.put("retrieved_at", now);
      jlogger.debug("%s 命中缓存（键=%s）：当前 %s 距上次构建 %s 不足 %s", this.getSubhandlerName(), cacheKey, now, builtAt, ttl);
      return hit;
    }

    jlogger.debug("%s 未命中缓存（键=%s）：当前 %s 距上次构建 %s 已超过 %s，重建数据", this.getSubhandlerName(), cacheKey, now, builtAt, ttl);
    JSONObject built = builder.get();
    long stamp = System.currentTimeMillis();
    built.put("retrieved_at", stamp);
    built.put("expires_at", ttl > 0L ? stamp + ttl : stamp);
    this.cacheData.put(cacheKey, built);
    this.cacheTime.put(cacheKey, stamp);
    return built;
  }

  /**
   * 查询某缓存键上次构建的时间戳。
   *
   * @param cacheKey 缓存键
   * @return 构建时间戳；从未构建过时返回 -1
   */
  protected final long cachedAt(String cacheKey) {
    Long at = this.cacheTime.get(cacheKey);
    return at == null ? -1L : at;
  }
}