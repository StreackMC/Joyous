package com.github.streackmc.Joyous.Mails;

import java.util.List;

import com.github.streackmc.Joyous.Joyous;
import com.github.streackmc.Joyous.Joyous.PermDef;
import com.github.streackmc.Joyous.logger;
import com.github.streackmc.StreackLib.utils.SMail;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public class MailsCommand {
  MailsCommand() {
  }

  final void register() {
    Joyous.addPermissions(
      PermDef.none("joyous.commands.mails.tempswitch", "临时开关关服邮件"),
      PermDef.op("joyous.commands.mails.test", "测试发送邮件")
    );
    Joyous.registerCommand(Commands.literal("jmail")
      .then(
        Commands.literal("tempswitch")
        .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.mails.tempswitch"))
        .then(
          Commands.literal("shutdown")
          .executes(this::tempswitch_shutdown)
        )
      )
      .then(
        Commands.literal("test")
        .requires(ctx -> ctx.getSender().hasPermission("joyous.commands.mails.test"))
        .then(
          Commands.argument("Profile", StringArgumentType.word())
          .then(
            Commands.argument("targetAddress", StringArgumentType.string())
            .then(
              Commands.argument("Subject", StringArgumentType.string())
              .then(
                Commands.argument("Content", StringArgumentType.greedyString())
                .executes(this::test_send)
              )
            )
          )
        )
      )
      .build(), "邮件管理", List.of("mail", "mails"));
  }

  /** /jmail tempswitch shutdown — 临时切换关服邮件 */
  private int tempswitch_shutdown(CommandContext<CommandSourceStack> ctx) {
    MailsMain.shutdownMailDisabled = !MailsMain.shutdownMailDisabled;
    if (MailsMain.shutdownMailDisabled) {
      ctx.getSource().getSender().sendMessage("§a关服邮件已 §c临时禁用 §a，本次服务器运行期间将不会发送关服邮件。");
    } else {
      ctx.getSource().getSender().sendMessage("§a关服邮件已 §b恢复 §a，本次服务器关闭时将正常发送关服邮件。");
    }
    return 1;
  }

  /** /jmail test <Profile> <targetAddress> <Subject> <Content> — 发送测试邮件 */
  private int test_send(CommandContext<CommandSourceStack> ctx) {
    String profile = StringArgumentType.getString(ctx, "Profile");
    String target = StringArgumentType.getString(ctx, "targetAddress");
    String subject = StringArgumentType.getString(ctx, "Subject");
    String content = StringArgumentType.getString(ctx, "Content");

    ctx.getSource().getSender().sendMessage("§7正在通过 Profile §f" + profile + " §7向 §f" + target + " §7发送测试邮件…");

    try {
      SMail mail = SMail.builder(profile)
          .to(target)
          .subject(subject)
          .body(content, false)
          .build();
      mail.send();
      ctx.getSource().getSender().sendMessage("§a测试邮件已成功发送至 §f" + target);
      logger.info("Mails | 测试邮件已通过 Profile[%s] 发送至 %s", profile, target);
      return 1;
    } catch (Exception e) {
      ctx.getSource().getSender().sendMessage("§c发送失败：" + e.getLocalizedMessage());
      logger.err("Mails | 测试邮件发送失败: %s", e.getLocalizedMessage(), e);
      return 0;
    }
  }

}
