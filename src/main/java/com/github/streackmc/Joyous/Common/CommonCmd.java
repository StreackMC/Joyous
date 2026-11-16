package com.github.streackmc.Joyous.Common;

import com.github.streackmc.StreackLib.types.SConfig;

public class CommonCmd {
  CommonCmd() {
  }

  SConfig conf() {
    return CommonMain.conf;
  }

  String cmdPerm(String suffix) {
    return CommonMain.NAMES.CMD_PERMISSION_PREFIX(suffix);
  }

  final void register() {
  }
}
