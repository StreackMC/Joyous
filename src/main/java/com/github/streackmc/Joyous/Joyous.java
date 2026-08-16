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

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus.Internal;

import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.types.SConfig;
import com.github.streackmc.StreackLib.types.SDatabase.SdbDatabase;
import com.github.streackmc.StreackLib.utils.SFile;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class Joyous {

  // 配置对象
  /** 用户配置 */
  public volatile static SConfig conf;
  /** 默认配置 */
  public volatile static SConfig confDefault;
  /** 构建配置 plugin.yml */
  public volatile static SConfig confBuild;
  /** 多语言支持 */
  public volatile static i18n i18n;

  // 全局变量
  /** 插件对象 */
  public volatile static boolean PHAPI_available = false;
  /** 插件对象 */
  public volatile static JavaPlugin plugin;
  /** 生命周期管理器 */
  public volatile static LifecycleEventManager<Plugin> lifeCycleManager;
  /** 数据文件夹目录 */
  public volatile static File dataPath;
  /** 插件管理器 */
  public volatile static PluginManager pluginManager;
  /** PlaceholderAPI Service */
  public volatile static PHAPI PlaceholderService;
  /** 数据库对象 */
  public volatile static SdbDatabase database;

  /**
   * 是否启用调试模式
   * 
   * @return 启用状态
   * @since 0.0.1
   */
  public static boolean isDebugMode() {
    // 继承StreackLib的调试状态
    return StreackLib.isDebugMode();
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
    jlogger.debug(String.format("配置文件版本：%d，适配版本：%d，差值：%d", cfgVer, confDefault.getLong("config-version", 000000L), diff));
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
   * 智能从提取JAR内部资源文件，若已存在则不动作
   * 
   * @apiNote 应当使用硬编码参数，不得信任用户输入，以防绝对路径覆写意外文件
   * @param target           外部资源路径，相对插件数据目录
   * @param internalFileName 内部资源文件，自动识别并补全开头的 /
   * @param compareVersion   是否要比较版本，仅限{@link SConfig.TYPES}中定义的字符串，将作为配置文件格式传入。如果为
   *                         Null 将不比较。
   * @return 文件是否存在
   * @throws FileNotFoundException         没有找到指定的资源文件
   * @throws IOException                   无法创建指定的临时文件
   * @throws NullPointerException          某些参数是空的
   * @throws UnsupportedOperationException 不支持的格式
   */
  @Internal
  public static boolean requireFileAndCheck(String target, String internalFileName, @Nullable String compareVersion) throws Exception {
    Path file = dataPath.toPath().resolve(Objects.requireNonNull(target, "target 参数不能为 Null"));
    String internalFilePath = (Objects.requireNonNull(internalFileName, "internalFileName 参数不能为 Null").startsWith("/"))
        ? internalFileName
        : "/" + internalFileName;
    if (Files.notExists(file)) {
      // 文件不存在则写出
      try {
        jlogger.debug("检查到 %s 不存在，自动新建默认文件", file);
        SFile.mv(Joyous.getResourceAsFile(internalFilePath), file.toFile());
        return true;
      } catch (Exception e) {
        jlogger.err("警告：无法写入 %s ： %s", internalFilePath, e.getLocalizedMessage(), e);
        return false;
      }
    } else /* 文件存在 */ if (compareVersion != null) {
      // 非 null 则判断版本
      SConfig outerConf = new SConfig(file, compareVersion);
      SConfig innerConf = new SConfig(Joyous.getResourceAsFile(internalFilePath), compareVersion);
      if (outerConf.getLong("version", 0L) < innerConf.getLong("version", 0L)) {
        // 文件版本过低，写出 .new
        int lastIndexOfDot = target.lastIndexOf(".");
        if (lastIndexOfDot > target.lastIndexOf("/") && lastIndexOfDot > target.lastIndexOf("\\")) {
          // 这说明文件指定了拓展名
          String start = target.substring(0, lastIndexOfDot);
          String end = "";
          if (lastIndexOfDot < target.length()) {
            // 安全可切
            end = target.substring(lastIndexOfDot + 1);
          }
          String finalNewFileTarget = start + ".new." + end;
          Path finalNewFilePath = dataPath.toPath().resolve(finalNewFileTarget);
          try {
            jlogger.warn("检查到 %s 的版本过低，已将新版保存为 %s", file, finalNewFileTarget);
            SFile.mv(Joyous.getResourceAsFile(internalFilePath), finalNewFilePath.toFile());
            return true;
          } catch (Exception e) {
            jlogger.err("警告：无法写入 %s ： %s", internalFilePath, e.getLocalizedMessage(), e);
            return true;
          }
        } else {
          // 没有拓展名，直接 new
          String finalNewFileTarget = target + ".new";
          Path finalNewFilePath = dataPath.toPath().resolve(finalNewFileTarget);
          try {
            jlogger.warn("检查到 %s 的版本过低，已将新版保存为 %s", file, finalNewFileTarget);
            SFile.mv(Joyous.getResourceAsFile(internalFilePath), finalNewFilePath.toFile());
            return true;
          } catch (Exception e) {
            jlogger.err("警告：无法写入 %s ： %s", internalFilePath, e.getLocalizedMessage(), e);
            return true;
          }
        }
      }
    }
    return true;
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
   *   .build(), "描述", List.of("alias"));
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
  public static final void registerCommand(LiteralCommandNode<CommandSourceStack> commandNode, @Nullable String description, @Nullable List<String> alias) {
    List<String> aliasFiltered = Objects.requireNonNullElse(alias, List.of());
    String descriptionFiltered = Objects.requireNonNullElse(description, "Joyous Command");
    lifeCycleManager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      event.registrar().register(commandNode, descriptionFiltered, aliasFiltered);
    });
  }

  public record PermDef(String node, PermissionDefault def, String desc) {
    /** 对非op默认 */
    public static PermDef notOp(String node) {return new PermDef(node, PermissionDefault.NOT_OP, "");}
    /** 对所有人默认 */
    public static PermDef all(String node) {return new PermDef(node, PermissionDefault.TRUE, "");}
    /** 无人默认 */
    public static PermDef none(String node) {return new PermDef(node, PermissionDefault.FALSE, "");}
    /** 对OP默认 */
    public static PermDef op(String node) {return new PermDef(node, PermissionDefault.OP, "");}
    /** 对非op默认 */
    public static PermDef notOp(String node, String desc) {return new PermDef(node, PermissionDefault.NOT_OP, desc);}
    /** 对所有人默认 */
    public static PermDef all(String node, String desc) {return new PermDef(node, PermissionDefault.TRUE, desc);}
    /** 无人默认 */
    public static PermDef none(String node, String desc) {return new PermDef(node, PermissionDefault.FALSE, desc);}
    /** 对OP默认 */
    public static PermDef op(String node, String desc) {return new PermDef(node, PermissionDefault.OP, desc);}
  }

  /**
   * 添加权限
   * 
   * @param perms
   */
  public static void addPermissions(PermDef... perms) {
    for (PermDef p : perms) {
      if (pluginManager.getPermission(p.node()) != null)
        continue;
      pluginManager.addPermission(new Permission(p.node(), p.desc(), p.def()));
    }
  }

  private Joyous() {
  }
}
