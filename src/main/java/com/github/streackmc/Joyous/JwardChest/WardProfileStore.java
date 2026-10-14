package com.github.streackmc.Joyous.JwardChest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.github.streackmc.Joyous.jlogger;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * 容器设置持久化（SConfig + YAML）
 * <p>
 * 落盘位置：{@code models/JwardChest/profiles.yml}。文件里有两个并列的顶层段：
 * <ul>
 *   <li>{@code profiles.<容器UUID>} —— 容器自身的设置（含它的 {@code preset-code}）</li>
 *   <li>{@code groups.<编码后的代号>} —— 预设组的设置</li>
 * </ul>
 * 二者是<b>覆盖关系</b>：{@code preset-code} 不为空时，生效设置取预设组；
 * 编辑也写回预设组，因此「修改设置会让同预设组的全部设置一并被修改」。
 * 解绑预设代号后容器回落到它自己那份设置。
 * <p>
 * 预设代号允许任意可见字符（含 {@code .}），而 SConfig 的路径以 {@code .} 分隔，
 * 故组的 YAML 键使用 URL 安全的 Base64 编码，真实代号写在段内的 {@code preset-code} 里。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class WardProfileStore {

  private static final String FILE_NAME = "profiles.yml";

  private final File file;
  private final SConfig conf;

  /** 容器自身设置：UUID → 设置 */
  private final Map<UUID, WardProfile> ownSettings = new HashMap<>();
  /** 预设组设置：代号 → 设置 */
  private final Map<String, WardProfile> presetGroups = new HashMap<>();
  /** 内存里有未落盘的改动 */
  private volatile boolean dirty;

  public WardProfileStore(File dataDir) {
    this.file = new File(new File(dataDir, "models/JwardChest"), FILE_NAME);
    File parent = this.file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    this.conf = new SConfig(this.file, SConfig.TYPES.YAML);
    // 批量写入后手动 save()，避免每次 put 都落盘
    this.conf.setWriteMode(SConfig.WRITE_MODE.INERTIA);
    reload();
    if (!this.file.exists()) {
      // 首次运行写出一个空模板，便于管理员直接手改
      writeNow();
    }
  }

  /** 从磁盘重新读取全部设置 */
  public synchronized void reload() {
    ownSettings.clear();
    presetGroups.clear();
    try {
      conf.reload();
    } catch (Exception e) {
      jlogger.warn("JwardChest 无法读取设置文件 %s：%s", file.getPath(), e.getLocalizedMessage());
    }

    Map<String, Object> groups = conf.getSection("groups", null);
    if (groups != null) {
      for (String key : groups.keySet()) {
        WardProfile p = WardProfile.readFrom(conf, "groups." + key, UUID.randomUUID());
        if (p.inPreset()) {
          presetGroups.put(p.presetCode(), p);
        }
      }
    }

    Map<String, Object> profiles = conf.getSection("profiles", null);
    if (profiles != null) {
      for (String key : profiles.keySet()) {
        UUID id = parseUuid(key);
        if (id == null) {
          jlogger.debug("JwardChest 忽略无法识别的容器键：%s", key);
          continue;
        }
        ownSettings.put(id, WardProfile.readFrom(conf, "profiles." + key, id));
      }
    }
    jlogger.debug("JwardChest 已载入 %s 个容器设置、%s 个预设组",
        ownSettings.size(), presetGroups.size());
    dirty = false;
  }

  // ──────────────────────────────────────────────
  // 读取
  // ──────────────────────────────────────────────

  /** 取容器自身那份设置（不存在则新建一份全默认的） */
  public synchronized WardProfile own(UUID id) {
    WardProfile p = ownSettings.get(id);
    if (p == null) {
      p = new WardProfile(id);
      ownSettings.put(id, p);
    }
    return p;
  }

  /**
   * 取生效设置
   * <p>
   * 若容器加入了预设组且该组存在，则返回「预设组设置 + 本容器 id / 代号」的副本；
   * 否则返回容器自身设置。
   *
   * @param id 容器 UUID
   * @return 生效设置（副本，可直接交给编辑器修改后再 {@link #save(WardProfile)}）
   */
  public synchronized WardProfile effective(UUID id) {
    WardProfile self = own(id);
    if (self.inPreset()) {
      WardProfile group = presetGroups.get(self.presetCode());
      if (group != null) {
        WardProfile merged = new WardProfile(id);
        merged.copySettingsFrom(group);
        merged.presetCode(self.presetCode());
        return merged;
      }
    }
    return copyOf(self);
  }

  /** 预设组设置（不存在返回 null） */
  @Nullable
  public synchronized WardProfile group(String code) {
    if (code == null || code.isBlank())
      return null;
    WardProfile p = presetGroups.get(code);
    return p == null ? null : copyOf(p);
  }

  /** 全部已存在的预设代号 */
  public synchronized List<String> groupCodes() {
    return new ArrayList<>(presetGroups.keySet());
  }

  /** 全部容器自身设置 */
  public synchronized Collection<WardProfile> profiles() {
    return new ArrayList<>(ownSettings.values());
  }

  /** 属于指定预设组的容器 UUID 列表 */
  public synchronized List<UUID> profilesInGroup(String code) {
    List<UUID> result = new ArrayList<>();
    if (code == null || code.isBlank())
      return result;
    for (WardProfile p : ownSettings.values()) {
      if (code.equals(p.presetCode())) {
        result.add(p.id());
      }
    }
    return result;
  }

  // ──────────────────────────────────────────────
  // 写入
  // ──────────────────────────────────────────────

  /**
   * 保存设置
   * <p>
   * 属于预设组时写回预设组（联动同组全部容器），并同步容器自身的 {@code preset-code}；
   * 否则写容器自身段。
   */
  /**
   * 保存设置（只改内存，不落盘）
   * <p>
   * 属于预设组时写回预设组（联动同组全部容器），并同步容器自身的 {@code preset-code}；
   * 否则写容器自身段。<b>落盘由 {@link #flush()} 负责</b>——编辑界面在关闭或点「保存」时统一调用，
   * 避免「点一次按钮写一次文件」。
   */
  public synchronized void save(WardProfile profile) {
    WardProfile self = own(profile.id());
    self.presetCode(profile.presetCode());

    if (profile.inPreset()) {
      WardProfile group = new WardProfile(profile.id());
      group.copySettingsFrom(profile);
      group.presetCode(profile.presetCode());
      presetGroups.put(profile.presetCode(), group);
      group.writeTo(conf, "groups." + encodeKey(profile.presetCode()));
    } else {
      self.copySettingsFrom(profile);
      self.presetCode(null);
    }
    self.writeTo(conf, "profiles." + profile.id());
    dirty = true;
  }

  /**
   * 设置预设代号（②预设代号）
   * <p>
   * 加入一个已存在的组时采用该组的设置；加入新组时以容器当前设置作为该组的初始模板；
   * 解除时把当前生效设置快照回容器自身。
   *
   * @param id   容器 UUID
   * @param code 新代号，null / 空 / {@link WardProfile#UNSET_CODE} 表示解除
   */
  public synchronized void setPresetCode(UUID id, @Nullable String code) {
    WardProfile self = own(id);
    String oldCode = self.presetCode();
    String target = (code == null || code.isBlank() || WardProfile.UNSET_CODE.equalsIgnoreCase(code.trim()))
        ? null
        : code.trim();

    if (java.util.Objects.equals(oldCode, target)) {
      return;
    }

    if (target == null) {
      // 解除：把当前生效设置快照进自身，再清空代号
      WardProfile snapshot = new WardProfile(id);
      snapshot.copySettingsFrom(effective(id));
      self.copySettingsFrom(snapshot);
      self.presetCode(null);
      self.writeTo(conf, "profiles." + id);
    } else if (presetGroups.containsKey(target)) {
      // 加入已有组：直接采用组设置，自身设置保留为解绑后的回退
      self.presetCode(target);
      self.writeTo(conf, "profiles." + id);
    } else {
      // 新建组：以自身当前设置为模板
      WardProfile group = new WardProfile(id);
      group.copySettingsFrom(self);
      group.presetCode(target);
      presetGroups.put(target, group);
      group.writeTo(conf, "groups." + encodeKey(target));
      self.presetCode(target);
      self.writeTo(conf, "profiles." + id);
    }
    dirty = true;
  }

  /** 是否有未落盘的改动 */
  public boolean isDirty() {
    return dirty;
  }

  /**
   * 落盘
   * <p>
   * 编辑界面在<b>关闭</b>（或点「保存」）时统一调用，所以一次编辑只写一次文件。
   * 没有改动时直接返回。
   */
  public synchronized void flush() {
    if (dirty) {
      writeNow();
    }
  }

  /** 无条件写盘（首次生成模板、以及 flush 内部使用） */
  private void writeNow() {
    try {
      conf.save();
      dirty = false;
    } catch (Exception e) {
      jlogger.warn("JwardChest 无法保存设置文件 %s：%s", file.getPath(), e.getLocalizedMessage());
    }
  }

  /** 设置文件路径 */
  public File file() {
    return file;
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  private static WardProfile copyOf(WardProfile src) {
    WardProfile copy = new WardProfile(src.id());
    copy.copySettingsFrom(src);
    return copy;
  }

  /** 预设代号 → 可用于 SConfig 路径的安全键（URL 安全 Base64，无补位） */
  private static String encodeKey(String code) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(code.getBytes(StandardCharsets.UTF_8));
  }

  @Nullable
  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (Exception e) {
      return null;
    }
  }
}
