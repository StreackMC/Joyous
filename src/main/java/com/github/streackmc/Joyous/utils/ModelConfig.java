package com.github.streackmc.Joyous.utils;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.StreackLib.types.SConfig;

/**
 * ModelConfig
 * @deprecated 未启用的轮子
 */
public class ModelConfig extends SConfig {
  /**
   * 新建一个模块细化配置文件，自动管理配置文件的写出与读取、版本控制、启动自动重载。
   * 
   * @param path            配置文件的路径，相对数据目录的 models 文件夹，无需以 / 开头
   * @param ResInternalPath 在 jar 内部 models 文件夹为基准的资源路径，无需以 / 或 . 开头
   * @param ctype           配置文件的类型，用于版本比较；传入 Null
   *                        将不比较版本。仅限{@link SConfig.TYPES}中定义的字符串，将作为配置文件格式传入。
   * @throws IOException                   无法创建或读取配置文件
   * @throws UnsupportedOperationException 不支持的格式
   * @throws NullPointerException          参数含有 Null
   */
  public ModelConfig(@Nonnull String path, @Nonnull String ResInternalPath, @Nullable String ctype) throws Exception {
    super(Joyous.dataPath.toPath().resolve("models").resolve(path), ctype);
    if (!Joyous.requireFileAndCheck("models/" + path, "/models/" + ResInternalPath, ctype)) {
      throw new IOException("无法创建配置文件 models/" + path + "，请检查插件数据目录权限");
    };

    // 写出配置文件后重载
    super.reload();
    super.setAutoReload(true);
  }
}
