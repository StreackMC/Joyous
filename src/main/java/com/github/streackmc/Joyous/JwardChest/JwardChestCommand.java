package com.github.streackmc.Joyous.JwardChest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.i18n;
import com.github.streackmc.Joyous.jlogger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.math.BlockPosition;

/**
 * JwardChest 命令
 * <p>
 * 主命令 {@code /jwardchest}，别名 {@code /jc}。
 * <ul>
 *   <li>{@code /jc new [坐标]} —— 绑定准星方块（或指定坐标）并打开编辑界面</li>
 *   <li>{@code /jc unbind [坐标]} —— 解除绑定（带坐标时不需要玩家，可放进命令方块）</li>
 *   <li>{@code /jc reset all|preset <代号>|radius <半径>|box <坐标…>} —— 清除临时数据</li>
 *   <li>{@code /jc reload} —— 重新读取设置文件</li>
 * </ul>
 * 坐标参数用 {@link ArgumentTypes#blockPosition()}，因此支持 {@code ~ ~ ~} 相对坐标与
 * 原版坐标补全，配合 {@code /execute positioned …} 即可让命令方块也用上。
 * 维度取自命令源的位置（即 {@code /execute in} 指定的维度）；控制台没有位置，
 * 范围类重置会作用于全部维度。
 *
 * @author kdxiaoyi
 * @since 0.7.0
 */
public final class JwardChestCommand {

  private final JwardChestMain module;
  private final WardProfileStore store;
  private final WardSessionManager sessions;
  private final WardEditorGui editor;

  public JwardChestCommand(JwardChestMain module, WardProfileStore store, WardSessionManager sessions,
      WardEditorGui editor) {
    this.module = module;
    this.store = store;
    this.sessions = sessions;
    this.editor = editor;
  }

  /** 注册命令与权限 */
  public void register() {
    Joyous.addPermissions(
        PermDef.op(WardListener.Perm.EDIT, "绑定方块与编辑战利品箱"),
        PermDef.all(WardListener.Perm.USE, "使用战利品箱"),
        PermDef.op(WardListener.Perm.RESET, "清除战利品箱临时数据"));

    Joyous.registerCommand(
        Commands.literal(JwardChestMain.COMMAND)
            .then(Commands.literal("new")
                .requires(ctx -> ctx.getSender().hasPermission(WardListener.Perm.EDIT))
                .executes(this::cmdNew)
                .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                    .executes(this::cmdNewAt)))
            .then(Commands.literal("unbind")
                .requires(ctx -> ctx.getSender().hasPermission(WardListener.Perm.EDIT))
                .executes(this::cmdUnbind)
                .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                    .executes(this::cmdUnbindAt)))
            .then(Commands.literal("info")
                .executes(this::cmdInfo)
                .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                    .executes(this::cmdInfoAt)))
            .then(Commands.literal("break")
                .requires(ctx -> ctx.getSender().hasPermission(WardListener.Perm.EDIT))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(this::cmdBreak)))))
            .then(Commands.literal("reload")
                .requires(ctx -> ctx.getSender().hasPermission(WardListener.Perm.RESET))
                .executes(this::cmdReload))
            .then(Commands.literal("reset")
                .requires(ctx -> ctx.getSender().hasPermission(WardListener.Perm.RESET))
                .then(Commands.literal("all")
                    .executes(this::cmdResetAll))
                .then(Commands.literal("preset")
                    .then(Commands.argument("code", StringArgumentType.string())
                        .suggests(this::suggestGroupCodes)
                        .executes(this::cmdResetPreset)))
                .then(Commands.literal("radius")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(this::cmdResetRadius)))
                .then(Commands.literal("box")
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                            .then(Commands.argument("z1", IntegerArgumentType.integer())
                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                    .then(Commands.argument("y2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                            .executes(this::cmdResetBox)))))))))
            .build(),
        "JwardChest 战利品箱",
        JwardChestMain.ALIASES);
  }

  /** 预设代号补全 */
  private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestGroupCodes(
      CommandContext<CommandSourceStack> ctx,
      com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
    String prefix = builder.getRemainingLowerCase();
    for (String code : store.groupCodes()) {
      if (code.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        builder.suggest(code);
      }
    }
    return builder.buildFuture();
  }

  // ──────────────────────────────────────────────
  // new / unbind
  // ──────────────────────────────────────────────

  /** /jc new —— 准星方块（只有玩家能用准星） */
  private int cmdNew(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    return bind(ctx.getSource().getSender(),
        player.getTargetBlockExact(JwardChestMain.CONF.reach()), true);
  }

  /**
   * /jc new &lt;坐标&gt;
   * <p>
   * 玩家执行时绑定并打开编辑界面；控制台 / 命令方块执行时只做绑定与回执
   * （把 {@link Block} 放进命令方块后，再在游戏内对着同一方块用 {@code /jc new} 即可配置）。
   */
  private int cmdNewAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    CommandSender sender = ctx.getSource().getSender();
    Block block = blockFromArg(ctx, "pos");
    if (block == null) {
      reply(ctx, i18n.tr("jwardchest.command.need_world"));
      return 0;
    }
    return bind(sender, block, sender instanceof Player);
  }

  /**
   * 绑定（已绑定则复用），可选是否打开编辑界面
   *
   * @param openEditor 仅对玩家执行者有效
   */
  private int bind(CommandSender sender, @Nullable Block block, boolean openEditor) {
    if (block == null || block.getType().isAir()) {
      sender.sendMessage(i18n.tr("jwardchest.command.new.no_block"));
      return 0;
    }

    UUID id = WardBinding.read(block);
    boolean fresh = id == null;
    if (fresh) {
      id = UUID.randomUUID();
      WardBinding.write(block, id);
      store.save(new WardProfile(id));
      // 新绑定是一次性动作，立刻落盘（编辑界面的改动则等关闭时统一落盘）
      store.flush();
    }

    sender.sendMessage(i18n.tr(
        fresh ? "jwardchest.command.new.bound" : "jwardchest.command.new.exists",
        block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));

    if (openEditor && sender instanceof Player player) {
      editor.openEditor(player, id, block.getLocation());
    }
    return 1;
  }

  /** /jc unbind —— 准星方块 */
  private int cmdUnbind(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    return unbind(ctx.getSource().getSender(), player.getTargetBlockExact(JwardChestMain.CONF.reach()),
        true);
  }

  /** /jc unbind <坐标> —— 不需要玩家，可放进命令方块 */
  private int cmdUnbindAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    Block block = blockFromArg(ctx, "pos");
    if (block == null) {
      reply(ctx, i18n.tr("jwardchest.command.need_world"));
      return 0;
    }
    // 不为 air：允许清理「方块已被破坏」的残留绑定
    return unbind(ctx.getSource().getSender(), block, false);
  }

  private int unbind(CommandSender sender, @Nullable Block block, boolean requireBlock) {
    if (block == null || (requireBlock && block.getType().isAir())) {
      sender.sendMessage(i18n.tr("jwardchest.command.new.no_block"));
      return 0;
    }
    UUID id = WardBinding.read(block);
    if (id == null) {
      sender.sendMessage(i18n.tr("jwardchest.command.unbind.none",
          block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
      return 0;
    }
    int dropped = sessions.removeByProfile(id);
    WardBinding.remove(block);
    store.flush();
    sender.sendMessage(i18n.tr("jwardchest.command.unbind.done",
        block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), dropped));
    return 1;
  }

  // ──────────────────────────────────────────────
  // reset
  // ──────────────────────────────────────────────

  /** /jc reset all */
  private int cmdResetAll(CommandContext<CommandSourceStack> ctx) {
    int removed = sessions.size();
    sessions.clear();
    reply(ctx, i18n.tr("jwardchest.command.reset.all", removed));
    return 1;
  }

  /** /jc reset preset <code> */
  private int cmdResetPreset(CommandContext<CommandSourceStack> ctx) {
    String code = StringArgumentType.getString(ctx, "code");
    List<UUID> ids = store.profilesInGroup(code);
    int removed = 0;
    for (UUID id : ids) {
      removed += sessions.removeByProfile(id);
    }
    reply(ctx, i18n.tr("jwardchest.command.reset.preset", code, ids.size(), removed));
    return 1;
  }

  /** /jc reset radius <r> */
  private int cmdResetRadius(CommandContext<CommandSourceStack> ctx) {
    Location loc = ctx.getSource().getLocation();
    if (loc == null) {
      reply(ctx, i18n.tr("jwardchest.command.reset.need_position"));
      return 0;
    }
    // 控制台没有固有维度 → 全部维度（见 worldOf）
    World world = worldOf(ctx);
    int r = Math.min(IntegerArgumentType.getInteger(ctx, "radius"),
        JwardChestMain.CONF.resetRadiusLimit());
    int[] min = { loc.getBlockX() - r, loc.getBlockY() - r, loc.getBlockZ() - r };
    int[] max = { loc.getBlockX() + r, loc.getBlockY() + r, loc.getBlockZ() + r };
    int removed = sessions.removeInRange(world, min, max);
    reply(ctx, i18n.tr("jwardchest.command.reset.range",
        world == null ? "*" : world.getName(), r, removed));
    return 1;
  }

  /** /jc reset box <x1 y1 z1> <x2 y2 z2> */
  private int cmdResetBox(CommandContext<CommandSourceStack> ctx) {
    World world = worldOf(ctx);

    int[] min = {
        Math.min(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "x2")),
        Math.min(IntegerArgumentType.getInteger(ctx, "y1"), IntegerArgumentType.getInteger(ctx, "y2")),
        Math.min(IntegerArgumentType.getInteger(ctx, "z1"), IntegerArgumentType.getInteger(ctx, "z2"))
    };
    int[] max = {
        Math.max(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "x2")),
        Math.max(IntegerArgumentType.getInteger(ctx, "y1"), IntegerArgumentType.getInteger(ctx, "y2")),
        Math.max(IntegerArgumentType.getInteger(ctx, "z1"), IntegerArgumentType.getInteger(ctx, "z2"))
    };
    int removed = sessions.removeInRange(world, min, max);
    reply(ctx, i18n.tr("jwardchest.command.reset.box",
        world == null ? "*" : world.getName(), removed));
    return 1;
  }

  /** /jc info —— 准星方块 */
  private int cmdInfo(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    Block block = (sender instanceof Player player)
        ? player.getTargetBlockExact(JwardChestMain.CONF.reach())
        : null;
    return info(ctx, block);
  }

  /** /jc info &lt;坐标&gt; */
  private int cmdInfoAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    return info(ctx, blockFromArg(ctx, "pos"));
  }

  /**
   * 打印某个方块的战利品箱信息
   * <p>
   * 主要给管理员排查用：一眼看清绑定、生效设置（可能是预设组带来的）与运行时状态。
   */
  private int info(CommandContext<CommandSourceStack> ctx, @Nullable Block block) {
    if (block == null || block.getType().isAir()) {
      reply(ctx, i18n.tr("jwardchest.command.new.no_block"));
      return 0;
    }
    String world = block.getWorld().getName();
    UUID id = WardBinding.read(block);
    if (id == null) {
      reply(ctx, i18n.tr("jwardchest.command.info.none", world, block.getX(), block.getY(), block.getZ()));
      return 0;
    }

    WardProfile profile = store.effective(id);
    reply(ctx, i18n.tr("jwardchest.command.info.header",
        world + " (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")"));
    reply(ctx, i18n.tr("jwardchest.command.info.profile", id,
        profile.inPreset() ? profile.presetCode() : WardProfile.UNSET_CODE));
    reply(ctx, i18n.tr("jwardchest.command.info.layout",
        profile.container().displayName(), profile.rows(), profile.slots()));
    reply(ctx, i18n.tr("jwardchest.command.info.title", profile.title()));
    reply(ctx, i18n.tr("jwardchest.command.info.prefilter", profile.prefilterSeconds()));

    List<Object> caps = new ArrayList<>();
    for (WardRarity r : WardRarity.values()) {
      caps.add(profile.maxDraw(r));
    }
    caps.add(profile.totalDrawCount());
    reply(ctx, i18n.tr("jwardchest.command.info.maxdraw", caps.toArray()));

    long active = profile.entries().stream().filter(WardItemEntry::active).count();
    reply(ctx, i18n.tr("jwardchest.command.info.entries",
        profile.entryCount(), active, profile.totalWeight()));

    WardSession session = sessions.block(block.getWorld(), block.getX(), block.getY(), block.getZ());
    if (session == null) {
      reply(ctx, i18n.tr("jwardchest.command.info.session_none"));
    } else {
      reply(ctx, i18n.tr("jwardchest.command.info.session",
          session.size(),
          session.prefilted()
              ? i18n.tr("jwardchest.command.info.done")
              : i18n.tr("jwardchest.command.info.not_done"),
          session.trackedPlayers()));
    }
    return 1;
  }

  /**
   * /jc break &lt;x&gt; &lt;y&gt; &lt;z&gt;
   * <p>
   * 只由「破坏确认提示」里那条可点击消息调用：破坏方块并解除绑定。
   */
  private int cmdBreak(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    Location loc = ctx.getSource().getLocation();
    if (loc == null || loc.getWorld() == null) {
      reply(ctx, i18n.tr("jwardchest.command.need_world"));
      return 0;
    }
    int bx = IntegerArgumentType.getInteger(ctx, "x");
    int by = IntegerArgumentType.getInteger(ctx, "y");
    int bz = IntegerArgumentType.getInteger(ctx, "z");
    Block block = loc.getWorld().getBlockAt(bx, by, bz);

    UUID id = WardBinding.read(block);
    if (id == null) {
      reply(ctx, i18n.tr("jwardchest.command.break.none"));
      return 0;
    }

    // 玩家执行时要求就在旁边，避免拿点击提示远程拆别人的箱子
    if (sender instanceof Player player) {
      double limit = JwardChestMain.CONF.reach() + 3.0;
      if (!player.getWorld().equals(block.getWorld())
          || player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > limit * limit) {
        player.sendMessage(i18n.tr("jwardchest.command.break.too_far"));
        return 0;
      }
    }

    int dropped = sessions.removeByProfile(id);
    WardBinding.remove(block);
    store.flush();
    block.breakNaturally();
    jlogger.debug("JwardChest 方块 %s(%s, %s, %s) 已被破坏并解除绑定，清除 %s 个会话",
        block.getWorld().getName(), bx, by, bz, dropped);
    reply(ctx, i18n.tr("jwardchest.command.break.done", block.getWorld().getName(), bx, by, bz));
    return 1;
  }

  /** /jc reload */
  private int cmdReload(CommandContext<CommandSourceStack> ctx) {
    store.reload();
    reply(ctx, i18n.tr("jwardchest.command.reload.done", store.profiles().size(), store.groupCodes().size()));
    return 1;
  }

  // ──────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────

  /** 取玩家执行者；不是玩家则回一条提示并返回 null */
  @Nullable
  private static Player playerOf(CommandContext<CommandSourceStack> ctx) {
    CommandSender sender = ctx.getSource().getSender();
    if (sender instanceof Player player) {
      return player;
    }
    sender.sendMessage(i18n.tr("system.command.player_only"));
    return null;
  }

  /**
   * 解析 {@code blockPosition} 参数为方块
   *
   * @return 方块；命令源没有世界（如控制台）时返回 null
   */
  @Nullable
  private static Block blockFromArg(CommandContext<CommandSourceStack> ctx, String name)
      throws CommandSyntaxException {
    BlockPosition pos = ctx.getArgument(name, BlockPositionResolver.class).resolve(ctx.getSource());
    Location base = ctx.getSource().getLocation();
    World world = (base == null) ? null : base.getWorld();
    if (world == null) {
      return null;
    }
    return world.getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ());
  }

  /**
   * 命令源所在维度
   * <p>
   * 玩家与命令方块都有实际位置，因此 {@code /execute in <维度> positioned <坐标>} 会生效；
   * 控制台（{@link org.bukkit.command.ConsoleCommandSender}）没有固有维度，
   * 按规格作用于<b>全部维度</b>，此处返回 null。
   *
   * @return 维度；null 表示全部维度
   */
  @Nullable
  private static World worldOf(CommandContext<CommandSourceStack> ctx) {
    if (ctx.getSource().getSender() instanceof org.bukkit.command.ConsoleCommandSender) {
      return null;
    }
    Location loc = ctx.getSource().getLocation();
    return (loc == null) ? null : loc.getWorld();
  }

  private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
    ctx.getSource().getSender().sendMessage(message);
  }
}
