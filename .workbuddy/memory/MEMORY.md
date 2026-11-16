# Joyous 项目长期记忆

## 概述
- 「栈流Streack」MC 玩法插件；`com.github.streackmc:Joyous`，pom `0.4.1`（最新 tag `0.3.0`）
- Paper 1.21.8 API（线上 1.21.11）+ Java 21，GPL-3.0；依赖全 provided：StreackLib **0.6.2** / PAPI 2.11.6 / Geyser+Floodgate
- 测试服 `mcserver/`：**测试服务器目录、非生产环境，世界/存档/配置随便造随便删都没事**（plugins 内即 0.6.2）

## 架构与约定
- 模块继承 `JoyousModel`，`entry.Models` 静态块注册；`onEnable` 配置→依赖→遍历启用
- 核心层 `Joyous`（上下文/权限/命令）、`PHAPI`（PAPI 桥接，竞争解析）、`jlogger`、`i18n`
- 配置：`Joyous.conf`(SConfig 自动重载) + `confDefault`(内置镜像) + `confBuild`(plugin.yml)
- 玩家文本一律 `Joyous.i18n.tr(...)` + legacy→Component，不用 `sendMessage(String)`
- **jlogger 参数约定**：`jlogger.warn/info/error(Object...)` 的第一个 String 是模板，**末尾的 Throwable 不占 `%s`**
  （`extract()` 会把它摘出去单独处理）→ **参数总个数 = `%s` 个数 + 2**（模板本身 + 末尾 Throwable）。
  try/catch 里写 `jlogger.warn("%s：%s", ctx, e.getLocalizedMessage(), e)`；`severe/error/err` 走真 Throwable
  后端（带堆栈），`warn/info/debug` 只把堆栈拼进消息文本。⚠️ `%s` 比实参多会当场抛
  `MissingFormatArgumentException`（日志调用自己崩）
- PHAPI：实现 `JoyousPHAPIhandler` + `PlaceholderService.registerParser`，返回 null 表示不处理
- 命令用 Paper Brigadier；`.requires(...)` 参数是 **CommandSourceStack**；玩家数据用 PDC
- 权限：命令 `joyous.commands.<模型>.<命令>[.<子类型>]`，其它 `joyous.<模型>.<子动作>`；
  `Joyous.addPermissions(PermDef.all/op/none(...))`

## ⚠️ SConfig（结论带版本，动手前先核 pom 版本）
- `SConfig(String,String)` 是 **(路径, 类型)**；按内容解析必须三参 `SConfig(content, type, name)`
- `putString(key, null)` 会 NPE；`writeMode` 默认 `autosave`
- **嵌套对称性（2026-11-16 实测）**
  · **0.6.1 及更早：只坏「写/删」，「读」一直正常**。`putNested` 拿「第一个未转义点」当「最后一个点」取叶子名：
    `a.b.c`→`{a={b={"b.c"=v}}}`、`a.b.c.d`→`{a={b={c={"b.c.d"=v}}}}`，写得进读不回、`remove` 静默无效、
    `putSection("p.q.r")` 同病。2 段路径正常。
  · **0.6.2 已修**（`splitWithNormalDot` 为唯一分段权威，叶子取最后一段）。实测 2/3/4/5 段在
    写→读→reload→读 全一致，`remove`、`putSection` 正常。
  · 坏的是 `master-SNAPSHOT`：m2 里 11-11 那份构建仍含 `getIndexOfNormalDot`。**2026-11-16 已清空
    整个 StreackLib 缓存、重拉并核对字节码**（备份 `/tmp/streacklib-m2-backup-*.tar.gz`；部署的
    `mcserver/plugins/StreackLib-0.6.2.jar` 原本就是修好的）
- **仍存的坑（与深度无关）**：路径中段若是标量，`putNested` 静默退化成「整条路径当顶层字面键」→ 读不回；
  写前用 `isReachable(key)` 预判
- **每次 `putXxx` 都整文件重写**；批量写先 `setWriteMode(INERTIA)` 再 `save()`
- 新建文件：`new SConfig(file, YAMLc)` → `INERTIA` → `reload()` → `putList(无点键, List<Map>)` → `save()`
- ⚠️ **0.6.1 遗留损伤**：`mcserver/plugins/Joyous/language.yml` 有 233 个「带点的叶子键」
  （如 `jwardchest: command: reload: command.reload.done:`）。0.6.2 只管新写入正确、**旧坏键不会自愈**，
  且永远 miss → 每次 `tr()` 触发整文件 autosave。清理=删文件由 jar 重放，或手工折回层级

## 模块
1. **JMenu** 双端菜单（Java 箱子 + Floodgate 表单），`.jmenu` JSON + TTL 缓存
   - v2：`display.model`（`legacy_model_data` ≤1.21.3 / `item_model` ≥1.21.4）、`else` 回退按钮
   - 打开与点击必须共用 `effectiveFor(player)`，否则「看到回退按钮却执行主按钮动作」
2. **Entroprix** 抽卡（米池/权重/大小保底），PDC
3. **JStatusAPI** 声明式 HTTP（StreackLib HTTPServer），子端点 Ph/Ban/Status
   - 缓存键按请求区分，TTL<=0 禁用
4. **PlayerTitle** 称号（占位符 + 权限）　5. **Mails** 关服邮件（StreackLib SMail）
6. **EnvExport** 环境变量 KV；`setEnv` 直接 `putString(key)`，键来自占位符 → 深度不受控
7. **Restarter** 计划重启 / 内存检测 / 假人恢复
   - ⚠️ 2026-10-05 审计 4 个 P0（onDisable 调度任务必被取消致 preventInterrupt 失效、计划重启误判异常关闭、
     假人名单踢人后才采集、时间条件 List 强转 CCE），详见 `2026-10-05.md`
8. **Neonaic** 与 Neonai-Connector 桥接 + 群服互联
   - `bridge/`：JSON+LF 分帧 / 握手 / 心跳；UDS 与 HTTP 双通道；插件是**监听端，不主动重连**
   - 绑定码：MC 取码 → 聊天侧 `/joyous:bind <code>` 认领；关联权威在 Neonaic；`docs/neonaic/` 在 .gitignore
9. **JwardChest** 战利品箱，`/jwardchest`(`/jc`)；绑定写**区块 PDC**，设置落 `models/JwardChest/profiles.yml`
   - 珍稀度**声明式**（`JwardChest.rarities` 一档一条，声明序即强度；`WardRarity` equals 按 id、compareTo 按 index，
     改 id = 换档）；「预设代号」= 预设组名（非空整组联动）；组键用 URL 安全 Base64（避开 `.`）
   - `WardProfile` 只走 **两段路径 + 一张扁平 map**（`profiles.<uuid>` + `putSection`）：0.6.1 时代止损，
     0.6.2 起技术上可放开，但没有放开的理由，保持不动
   - 共享箱：`contents[]`(权威) + `revealed{玩家→槽}` + `viewers`；三态判据=有东西且我揭开过→真物品，
     没揭开→占位，没东西→空；揭开与领取分离（倒计时只 markSearched，**点击有东西的格子才领取**）
     · 搜索序=**每玩家环形游标**（`Progress.cursor`），别改回「最小未揭开槽位」
     · `transact()`=加锁→校验+改→解锁→落库→派发；**落库失败必须回滚内存**（否则复制）
     · 刷新只由 `/jc reset*` 或会话过期；关服先 `setEvictionListener(null)` 再 clear（否则重启=补货）
     · 品质跟物品走：PDC `joyous:jc_rarity`，读不到用 `fallback-rarity`；`allow-foreign-place` 控外来物品
     · `item_model` + `custom_model_data.strings=[品质,状态]`；`item-model` 默认留空（没资源包会紫黑格）
     · ⚠️ `WardDistribution.pick()` 必须 `floor(shaped*(span+1))`，round 会让两端概率减半
   - ⚠️ 改完待选物品要 `module.invalidate(id)`
10. **JSell** 见下节

## 数据库（Sdb）
- `act(String)` 走 `Statement`；`act(SdbActionContext)` 走参数化
- ⚠️ **ActionContext 没有 INSERT**（只有 SELECT/UPDATE/MERGE/DELETE/CREATE/ALTER/DROP/TRUNCATE）；
  写行只能原始 SQL → **值必须白名单断言**（UUID/Base64/数字），自由文本塞进 Base64 payload；
  越界抛 `UnsafeSqlValueException`
- ⚠️ 不能混用 `table()`（自动加 `table_prefix`）与原始 SQL（不加）→ 写 A 表读 B 表
- `db.act(sql)` 每次自动提交；多语句 `try (SdbAction a = db.act()) { …; a.commit(); }`

## Git 与构建
- 身份 `Neonai <neonai+coding@kdxiaoyi.top>`，仅命令行 `-c` 携带；只 add 本次产出；不做远程同步；记忆文件属产出
- **`@since` 只填「已存在的 tag」或「当前 pom 版本」**，禁止预写下一个版本号
- ⚠️ 构建一律 `mvn -o clean package`（语言服务器会把 ECJ 错误 class 写进 `target/classes`）

## JSell 要点
- 命令：`/jsell`(`gui|hand|inv|all`)、`/jsellworth`(只读价目表)、`/jsellset`(`<单价>[物品]|reload|pending|restore`)，
  别名见 plugin.yml
- 权限：`joyous.commands.jsell*`（含 jsellset 子命令）、`joyous.jsell.admin`、
  `joyous.jsell.group.<组>.{sellable,viewable}`（默认 true，随组动态注册；分组权限同时是定价门槛）
- **存档（一组一文件）**：`models/JSell/groups/<组名>.yml`，`format: 2` + `entries: [ … ]`
  · 组名=文件名，行内不写 `group`；只有 `item` 是 Base64；组名规范化后只含 `[a-z0-9_-]`≤32
    （Windows 保留设备名加 `_` 前缀 `con`→`_con.yml`）；判「文件名是否规范」用
    `fileOf(group).getName().equals(file.getName())`，别拿字符串比组名
  · 只重写 `dirtyGroups`；某组**既没条目也没分组设置**才删文件（组边界=文件）；**改分组要重写两个组**——
    条目是可变对象，靠 `persistedGroups`(id→已落盘组名) 快照判断「搬家」
  · 载入：先 `groups/*.yml`，再并入老存档（**同 id 以分组文件为准**），最后按 `createdAt` 排序
  · 只兼容**已发布**的 0.3.0 `items.yml`（整条 Base64 一行一条 → 归 `default`），迁移后改名
    `items.legacy.bak`；**有解析失败记录时不改名**；开发期「`entries` 带 `group`」不兼容
  · 写：`putInt("format")` + `putList("entries", List<Map>)`（两路径均无点）；`TYPES.YAMLc` + `putComment` 才有注释
  · 理由源自 0.6.1 缺陷，0.6.2 起已非必需
- 匹配：`SellMatch.difference(item, template)`（0=相同；多出的数据组件/Lore 行/附魔等级/PDC 键各计 1），
  取代价最小、同价取先登记的 `createdAt`；去重靠 `findSameTemplate`(`isSimilar`)
- 耐久：`hasDurability()` = 模板带 `MAX_DAMAGE`；`effectiveUnit()` = 单价 ×(MAX_DAMAGE−DAMAGE)/MAX_DAMAGE
  （按**实例**取，缺 DAMAGE 视为满）；取价全走它；逐条命令用 `sale.value()`
- 托管：界面物品=权威内容，每次变动同步写 `jsell_escrow`；关界面/断线=取消（原样退还+删行），崩服=下次上线交付
- 结算 `jsell_tx`：PREPARED→COMMITTED→REFUNDED→PAID→删除；**PREPARED/COMMITTED 可整单退回；
  REFUNDED/PAID 只能按已售处理**（重复执行=刷钱）
- 交付一律「先删库、再给物品」：宁可少给，绝不复制
- 限购：`jsell_usage`+`SUM(amount)`；`limitCount<=0` 不限、`limitSeconds<=0` 终身；查询失败保守返 0；
  重置取 MAX(sold_at)+窗口；时间只到分钟
- 流水：`SellLog`+`AsyncDailyLog`→`logs/JSell/yyyy-MM/dd.txt`（`SOLD/REFUND/FAILED`）；唯一写入点
  `SellService.commit()`；列序以 `SellLog` 为准
- **分组设置**（`SellGroupSettings`，就写在组文件顶层，删掉=继承）：
  `display`（展示名，只影响界面文字）/ `unit.name` / `economy.commands` / `economy.decimals`
  · 全是可空字段：`null` = 继承 `JSell.*`；界面写「继承全局（当前：金币）」，输入 `unset` 恢复
  · ⚠️ `default` 组 `canOverride()==false`：它的值**就是**全局值，界面不画 unit/economy（只留展示名）
  · 读用 `getSection("unit"|"economy")` 再取子键（`getString(key, null)` 分不清「没写」与「空」）；写前先
    `remove("display"/"unit"/"economy")` 整段重刷，否则「覆写→继承」会留幽灵字段
  · 只有设置没有条目的组**也出现在 `groups()`**（`SellGroups.groupsOf(entries, extra)`），否则改过名的组会消失
- 展示名：`store.label(group)` 是唯一出口（总览 Lore、筛选按钮、组列表页都用它）；权限节点/文件名/命令参数仍用组名
- **付款按「生效配置」聚合**：`SellService.runCommands` 用 `Payout(commands, unit, decimals)` 当 key 汇总金额，
  没覆写时所有组共享同一份配置 → 仍然**只发一次钱**（拆成多笔会放大任何按次数触发的副作用）
- 单位取用：`CONF.unitName(group)` / `decimals(group)` / `economyCommands(group)`；跨组用全局（把两种货币加总不成立），
  出售界面在跨组时逐组列出小计（`jsell.gui.info_group_line`）
- GUI 6×9；管理员列表 `SLOT_GROUP=52` 循环分组（左键下/右键上）、**Shift+左键 → 分组列表**；
  分组列表左键筛选、**右键（仅管理员）→ 分组设置页**；详情 `3`=分组、`5`=折损
- ⚠️ 取消事件里改光标必须 `runTask` 延后 `setItemOnCursor`；改列表后要 `store.flush()`

## 共享交互层（utils/interact）
- **不放 StreackLib**：它刻意零平台引用（核心不许出现 `org.bukkit`），UI 是 Paper 专属；库按月发版不适周迭代
- 包：`interact`（仲裁+`Text`）/`.chestui`/`.input`；命名 接口 `I`/抽象类 `Abstract`/枚举 `Enum`
- 内核：`PagePriorityEnum{INSTANT,REPLACE,NORMAL,INSERT}` + `IJoyousPage.keepable()` + 每玩家队列；
  **视图关闭 ≠ 会话终止** → `onSuspend/onResume` vs `onDetach`（副作用恰好一次）
- 规则：当前页立即关、新页延后 1 tick；同帧立即类按 (submittedAt,seq)；同 (key,purpose) 去重；
  队列上限 8 / PENDING 过期 10 分钟 / 单轮最多 32 次转移；`PageArbiter.get().install(plugin)` 接入
- chestui：`IGuiInteract`/`AbstractGuiPage`/`Canvas`/`GuiRouter`/`Click`+`ClickPolicy`/`InteractHolder`
- input：`ITextInteract`/`IChatInteract`/`ChatPrompt`/`AbstractChatPage`/`ChatInput`；**提示唯一出口是 `ChatInput`**
  （`onPageShown` 发提示+起超时，`onPageHidden` 撤计时器）；文案键 `ui.input.*`
- ⚠️ 关闭事件只有 `state()==VISIBLE` 才算玩家主动退出（切页/挂起/终止都会触发 close）
- ⚠️ `repaint` 写完 Inventory 必须 `player.updateInventory()`；`AbstractGuiPage.repaint` 已内置
- ⚠️ 返回上级用 `ctx.close()`，不要 open 新实例（去重会命中挂起中的旧实例）；上下级用 INSTANT
- ⚠️ `Click.slot` 是**相对所点击容器**的槽位（`event.getSlot()`），不是 rawSlot
- ⚠️ 铁砧输入只能用真实 AnvilMenu：`MenuType.ANVIL.builder()...checkReachable(false).build(player)`
- ⚠️ 跨类增删/签名变更**无法热重载**，必须停服重启；文档 `docs/interact-api/README.md`
