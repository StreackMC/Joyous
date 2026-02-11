package com.github.streackmc.Joyous;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus.Internal;

import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.SConfig;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class Joyous {

  // 配置对象
  /** 用户配置 */
  public static SConfig conf;
  /** 默认配置 */
  public static SConfig confDefault;
  /** 构建配置 plugin.yml */
  public static SConfig confBuild;
  /** 多语言支持 */
  public static i18n i18n;

  // 全局变量
  /** 插件对象 */
  public static JavaPlugin plugin;
  /** 生命周期管理器 */
  public static LifecycleEventManager<Plugin> lifeCycleManager;
  /** 数据文件夹目录 */
  public static File dataPath;

  /**
   * 是否启用调试模式
   * 
   * @return 启用状态
   * @since 0.0.1
   */
  public static boolean isDebugMode() {
    // 继承StreackLib的调试状态
    return StreackLib.conf.getBoolean("debug", false);
  }

  /**
   * 获取配置文件版本差异
   * 负数表示低于当前版本，正数表示高于当前版本，0表示相同版本
   * 
   * @return 差异版本数量
   * @throws NullPointerException 当 conf* 未被初始化时
   * @since 0.0.1
   */
  public static int getConfigVerisonDiff() {
    Long cfgVer = conf.getLong("config-version", 000000L);
    int diff = Long.compare(cfgVer, confDefault.getLong("config-version", 000000L));// TODO: bug,无法正常检测
    logger.debug(String.format("配置文件版本：%d，适配版本：%d，差值：%d", cfgVer, confDefault.getLong("config-version", 000000L), diff));
    return diff;
  }

  /**
   * 获取当前版本
   * 
   * @return
   * @throws NullPointerException 当 conf* 未被初始化时
   * @since 0.0.1
   */
  public static String getVersion() throws NullPointerException {
    return confBuild.getString("version");
  }

  /**
   * 提取JAR内部资源文件
   * 
   * @param name 要提取的资源文件
   * @return 资源文件对象
   * @throws FileNotFoundException 没有找到指定的资源文件
   * @throws IOException           无法创建指定的临时文件
   */
  @Internal
  public static File getResourceAsFile(String name) throws Exception {
    InputStream in = Joyous.class.getResourceAsStream(name);
    if (in == null) {
      throw new FileNotFoundException(String.format("没有找到 %s ，打包时是否包括了它？", name));
    }
    Path tmp = File.createTempFile("extract-", ".tmp").toPath();
    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    tmp.toFile().deleteOnExit();
    return tmp.toFile();
  }

  /**
   * 注册一个命令：
   * 
   * <pre>
   * regisiterCommand(Commands.literal("root")
   *   .then(
   *     Commands.literal("animal")
   *       .then(
   *         Commands.literal("cat")
   *       ).then(
   *         Commands.literal("dog")
   *       )
   *   ).then(
   *     Commands.literal("give")
   *       .then(
   *         Commands.argument("player", ArgumentTypes.player())
   *            .executes(context -> {
   *              Func1(context)
   *              return Command.SINGLE_SUCCESS;
   *            })
   *         )
   *       )
   *   )
   *   , "描述", List.of("alias"));
   * </pre>
   * 
   * 上例中可以构建命令：
   * /root
   *   animal
   *     cat
   *     dog
   *   give <Selector>
   * 
   * @param commandNode 命令树
   * @param description 命令描述，可为空
   * @param alias       命令别名，可为空
   */
  @Internal
  public static final void regisiterCommand(LiteralCommandNode<CommandSourceStack> commandNode, @Nullable String description, @Nullable List<String> alias) {
    List<String> aliasFiltered = Objects.requireNonNullElse(alias, List.of());
    String descriptionFiltered = Objects.requireNonNullElse(description, "Joyous Command");
    lifeCycleManager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      event.registrar().register(commandNode, descriptionFiltered, aliasFiltered);
    });
  }

  private Joyous() {
  }
}
