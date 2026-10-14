package com.github.streackmc.Joyous.JwardChest;

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

  /** /jc new —— 准星方块 */
  private int cmdNew(CommandContext<CommandSourceStack> ctx) {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    return bindAndEdit(player, player.getTargetBlockExact(JwardChestMain.CONF.reach()));
  }

  /** /jc new <坐标> */
  private int cmdNewAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    Player player = playerOf(ctx);
    if (player == null) {
      return 0;
    }
    Block block = blockFromArg(ctx, "pos");
    if (block == null) {
      player.sendMessage(i18n.tr("jwardchest.command.need_world"));
      return 0;
    }
    return bindAndEdit(player, block);
  }

  /** 绑定（已绑定则复用）并打开编辑界面 */
  private int bindAndEdit(Player player, @Nullable Block block) {
    if (block == null || block.getType().isAir()) {
      player.sendMessage(i18n.tr("jwardchest.command.new.no_block"));
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

    player.sendMessage(i18n.tr(
        fresh ? "jwardchest.command.new.bound" : "jwardchest.command.new.exists",
        block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    editor.openEditor(player, id, block.getLocation());
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
