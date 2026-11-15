# Joyous 项目长期记忆

## 项目概述
- 「栈流Streack」Minecraft 服务器的玩法功能插件；`com.github.streackmc:Joyous`，pom 版本 `0.4.0`（最新 tag `0.3.0`）
- Paper 1.21.8 API（线上跑 1.21.11）+ Java 21，GPL-3.0，仓库 https://github.com/StreackMC/Joyous
- 外部依赖全部 provided：StreackLib 0.6.2 / PlaceholderAPI 2.11.6 / Geyser+Floodgate / Spigot 1.21.5

## 架构
- 模块继承 `JoyousModel`，在 `entry.Models` 静态块注册；`onEnable` 里配置→依赖→遍历启用
- 核心层：`Joyous`（全局上下文/权限/命令注册）、`PHAPI`（PAPI 桥接，竞争解析）、`jlogger`、`i18n`
- 配置：`Joyous.conf`（SConfig，自动重载）+ `confDefault`（内置镜像）+ `confBuild`（plugin.yml）
- 玩家文本一律走 `Joyous.i18n.tr(...)`(language.yml) + legacy→Component，不用 `sendMessage(String)`
- ⚠️ `SConfig(String,String)` 是 **(路径, 类型)**；按字符串内容解析必须用三参 `SConfig(content, type, name)`
- ⚠️ `SConfig.putString(key, null)` 会 NPE
- ⚠️ **SConfig 嵌套写入**：0.6.1 与 m2 的 `master-SNAPSHOT` 的 `putNested` 拿「第一个点」当「最后一个点」，
  ≥3 层写入会把前缀重复进叶子键（`a.b.c` → `{a={b={"b.c"=v}}}`，读不回；`remove` 静默无效）。
  `splitWithNormalDot` 修复版在 master@9dac922，**无发布版含它** → 写配置要么 ≤2 层，要么 `putSection(path, Map)`
- ⚠️ 连带：`i18n.tr` 的差量回写就是 3 层键，在 0.6.1 上写出 `jsell: { editor.title: … }` 残缺键（线上已 231 个）
- 已实测：`new SConfig(新文件, YAMLc)` → `setWriteMode(INERTIA)` → `reload()`（不抛异常）→ `putList(无点键, List<Map>)` → `save()`
  能正常新建文件、覆盖写、保留注释；空列表写成 `entries: [\n  ]`

## 模块（10 个）
1. **JMenu** 双端菜单（Java 箱子 + Floodgate 表单），`.jmenu` JSON，TTL 缓存
   - 描述文件 v2：`display.model`（`legacy_model_data` ≤1.21.3 / `item_model` ≥1.21.4）、`else` 回退按钮
   - 打开与点击必须共用 `effectiveFor(player)`，否则「看到回退按钮却执行主按钮动作」
2. **Entroprix** 熵流抽卡（米池、权重、大小保底），PDC 持久化；有私有 `AsyncLogger`（可与 `utils/AsyncDailyLog` 合并）
3. **JStatusAPI** 声明式 HTTP 查询（StreackLib HTTPServer）；子端点 Ph/Ban/Status
   - 缓存键必须按请求区分，TTL<=0 禁用；`PHAPI.commonPHAPI` 定义了但从未 registerParser
4. **PlayerTitle** 称号（占位符 + 权限）
5. **Mails** 关服邮件（StreackLib SMail）
6. **EnvExport** 环境变量 KV
7. **Restarter** 计划重启 / 内存检测（Old Gen+泄漏+堆转储）/ 假人恢复
   - ⚠️ 2026-10-05 审计出 4 个 P0（onDisable 调度任务必被取消致 preventInterrupt 失效、计划重启被误判异常关闭、
     假人名单踢人后才采集、时间条件 List 强转 CCE），详见 `2026-10-05.md`
8. **Neonaic** 与 Neonai-Connector 的桥接 + 群服互联
   - `bridge/`：JSON+LF 分帧 / 握手 / 心跳；UDS 与 HTTP 双通道；插件是**监听端不主动重连**
   - 身份绑定走一次性绑定码：MC 取码 → 聊天侧 `/joyous:bind <code>` 认领；关联权威在 Neonaic
   - `docs/neonaic/` 在 .gitignore（本地文档）
9. **JwardChest** 战利品箱（方块绑定 → 带搜索动画的自定义容器），命令 `/jwardchest`(`/jc`)
   - 绑定写**区块 PDC**，设置落 `models/JwardChest/profiles.yml`；珍稀度**声明式**（`config.yml` 的
     `JwardChest.rarities` 一档一条，声明顺序即强度；`WardRarity` equals 按 id、compareTo 按 index，改 id = 换档）
   - 「预设代号」= 预设组名（非空则整组联动）；组键用 URL 安全 Base64（避开 `.` 与 SConfig 路径冲突）
   - 共享箱语义：`contents[]`(权威) + `revealed{玩家→槽}` + `viewers`
     · 三态唯一判据：有东西且我揭开过=真物品；没揭开=占位；没东西=空
     · 揭开与领取分离：倒计时只 markSearched，**点击有东西的格子才领取**
     · 搜索顺序 = **每玩家环形游标**（`Progress.cursor`）；别改回「最小未揭开槽位」（别人往靠前空槽放东西会把人拽回队首）
     · `transact()` = 加锁→校验+修改→解锁→落库→派发；**落库失败必须回滚内存**（否则复制）
     · 刷新只由 `/jc reset*` 或会话过期触发；关服先 `setEvictionListener(null)` 再 clear（否则重启=补货）
     · 品质跟着物品走：`WardTag` 写 PDC `joyous:jc_rarity`，读不到用 `fallback-rarity`；`allow-foreign-place` 控制外来物品
     · 物品模型：`item_model` + `custom_model_data.strings=[品质,状态]`；`item-model` 默认留空（没资源包写了会显示紫黑格）
     · 数量分布走 `WardDistribution`；⚠️ `pick()` 必须 `floor(shaped*(span+1))`，round 会让两端概率减半
   - ⚠️ 改完待选物品要 `module.invalidate(id)`，否则表还是旧结果
10. **JSell** 出售系统：玩家 `/jsell`(`/sell`) + 总览 `/jsellworth`(`/jworth`,`/worth`) + 管理 `/jsellset`(`/sellset`)
   - 细节见下方「JSell 要点」

## 数据库（StreackLib Sdb）
- `SdbDatabase.act(String)` 走 `Statement`（无参数位）；`act(SdbActionContext)` 走参数化
- ⚠️ **ActionContext 没有 INSERT**（只有 SELECT/UPDATE/MERGE/DELETE/CREATE/ALTER/DROP/TRUNCATE）；
  写行只能原始 SQL → **值必须白名单断言**（UUID/Base64/数字），自由文本塞进 Base64 payload；
  越界抛 `UnsafeSqlValueException` 并提示升级 StreackLib（UPDATE/MERGE 的参数化未实现）
- ⚠️ 不能混用 `SdbActionContext.table()`（自动加 `table_prefix`）与原始 SQL（不加）→ 会写 A 表读 B 表
- `db.act(sql)` 每次自动提交；多语句用 `try (SdbAction a = db.act()) { a.apply(...); a.commit(); }`

## 关键设计模式
- PHAPI 竞争解析：实现 `JoyousPHAPIhandler` 并 `Joyous.PlaceholderService.registerParser`，返回 null 表示不处理
  （`%joyous_xxx%` → params = `xxx`）
- PDC 持久化玩家数据；命令用 Paper Brigadier（`Commands.literal/argument` + `ArgumentTypes.*`）
- 权限用 `Joyous.addPermissions(PermDef.all/op/none(...))`；⚠️ `.requires(...)` 的参数是 **CommandSourceStack**
- **权限命名**：命令 `joyous.commands.<模型>.<命令>[.<子类型>]`，其它 `joyous.<模型>.<子动作>`
  （已统一 JSell/JwardChest/Entroprix/PlayerTitle；Entroprix 旧 `.get`/`.set`、`tires` 拼写、PlayerTitle `joyous.titles.*` 均改过）

## Git 约定
- 身份 `Neonai <neonai+coding@kdxiaoyi.top>`，仅命令行 `-c` 携带；只 add 本次产出；不做任何远程同步
- 记忆文件属任务产出，先写记忆再提交
- **`@since` 只填「已存在的 tag」或「当前 pom 版本」**，禁止预写下一个版本号；pom 的 `<version>` 是唯一版本源
  （`@since 0.6.0/0.7.0/0.8.0/0.9.0` 曾整片虚标，已按创建时的 pom 版本纠正：0.6.x→0.2.0，其余→0.3.0/0.4.0）
- ⚠️ 构建：VS Code 语言服务器会把 ECJ 的错误 class 写进 `target/classes` → **一律 `mvn -o clean package`**

## JSell 要点
- 命令：`/jsell`(`/sell`) `gui|hand|inv|all`；`/jsellworth`(`/jworth`,`/worth`) 只读价目表；
  `/jsellset`(`/sellset`) `[组名]` 开列表 / `<单价> [物品]` 设价 / `reload` / `pending` / `restore <tx>`
- 权限：命令 `joyous.commands.jsell.{gui,hand,inv,all}`、`joyous.commands.jsellworth`、
  `joyous.commands.jsellset[.reload|pending|restore|value|group]`；非命令 `joyous.jsell.admin`、
  `joyous.jsell.group.<组>.{sellable,viewable}`（默认 true，随组动态注册）
- **存档（一组一文件，0.4.0 起）**：`models/JSell/groups/<组名>.yml`，`format: 2` + `entries: [ … ]`
  · **组名 = 文件名，行内不再写 `group`**（两处说法会冲突）；只有 `item` 是 Base64（NBT 只能这么存）
  · 组名规范化后只含 `[a-z0-9_-]`≤32，跨平台可用；Windows 保留设备名加 `_` 前缀（`con` → `_con.yml`）
  · 判定「文件名是否规范」用 `fileOf(group).getName().equals(file.getName())`，不要拿字符串比组名
    （`con.yml` 与 `_con.yml` 都能读成组 `con`，少判一步就会同时留两个文件 → 重载复活已删条目）
  · 写入只重写 `dirtyGroups` 里的文件；某组没条目就**删文件**（组的边界就是文件）
  · **改分组要重写两个组**：旧组文件要让它消失。⚠️ 条目是可变对象，详情页改分组时 `save()` 拿到的
    就是表里那一份，`group` 已被覆盖 → 必须靠 `persistedGroups`（id→已落盘组名）快照判断「搬家」
  · 载入顺序：先读 `groups/*.yml`，再把老存档并进来（**同 id 以分组文件为准**），最后按 `createdAt` 排序入表
  · 只兼容**已发布**的 0.3.0 存档 `models/JSell/items.yml`（`items` 列表，一行一条整条 Base64 → 全归
    `default`），迁移后改名 `items.legacy.bak`；**有解析失败记录时不改名**（否则数据被藏起来）
  · 0.4.0 开发期的「`entries` 带 `group`」中间格式**不兼容**（从没发版，不留没人敢删的分支）：
    读到只报一次警告、文件原样留着，由管理员自己转成 Base64 列表或删掉
  · 写入用 `putInt("format")` + `putList("entries", List<Map>)`（两个路径都没有点，绕开 `putNested` 缺陷），
    `TYPES.YAMLc` + `putComment` 才有注释
- 匹配选优：`SellMatch.difference(item, template)` 取代「先 STRICT 后 CONTAINS」（0=完全相同；多出的数据组件/
  Lore 行/附魔等级/PDC 键各计 1），取代价最小、同价取先登记的 `createdAt`
- 去重：`findSameTemplate`（`isSimilar`）命中就打开已有条目，不再新增
- 耐久折损：`SellEntry.hasDurability()` = 模板带 `MAX_DAMAGE`；`SellService.effectiveUnit()` =
  单价 × (MAX_DAMAGE−DAMAGE)/MAX_DAMAGE（按**实例**取，缺 DAMAGE 视为满耐久）。所有取价入口都走它；
  `Sale` 自带 `value`，逐条命令用 `sale.value()` 而非「单价×件数」
- 分组权限同时是定价门槛：`entries.find(player,item)` 拿不到 `sellable` 就当不可售（计价/结算/占位符一致）
- 托管模型（抗灾核心）：界面里的物品 = 权威内容，每次变动同步写 `jsell_escrow`；
  关界面/断线 = 取消（原样退还 + 删行），崩服 = 下次上线交付
- 结算协议（`jsell_tx` 多阶段）：PREPARED → COMMITTED → REFUNDED → PAID → 删除。
  **PREPARED/COMMITTED 可整单退回；REFUNDED/PAID 只能按已售处理**（重复执行=刷钱）
- 交付顺序一律「先删库、再给物品」：宁可少给，绝不复制
- 限购：`jsell_usage` 流水 + `SUM(amount)`；`limitCount<=0` 不限、`limitSeconds<=0` 终身累计；
  查询失败保守返回 0；重置时刻取 MAX(sold_at)+窗口；时间只到分钟
- 出售流水（文本账，独立于数据库）：`SellLog` + `utils/AsyncDailyLog` → `logs/JSell/yyyy-MM/dd.txt`，
  列序 `时间|动作|玩家|UUID|分组|内容|数量|金额|tx`，动作 `SOLD/REFUND/FAILED`；
  唯一写入点 `SellService.commit()`（PAID 之后）；配置 `JSell.log.enabled` / `log.dir`
- GUI：6×9；管理员列表 `SLOT_GROUP=52` 循环分组（左键下一个/右键上一个），详情 `3`=分组、`5`=折损开关
- ⚠️ 取消事件里改光标必须 `runTask` 延后 `setItemOnCursor`；改列表后要 `store.flush()`

## 共享交互层（utils/interact）
- 触发：三个模块各自重复实现「容器 UI + 聊天输入」。**不放 StreackLib**——它刻意零平台引用
  （核心不许出现 `org.bukkit`），UI 是 Paper 专属；且库按月发版（0.5.2→0.6.2 跨 5 个月）不适合周迭代
- 包结构：`interact`（仲裁内核 + `Text`）/ `interact.chestui` / `interact.input`；
  命名：接口 `I` / 抽象类 `Abstract` / 枚举 `Enum` 后缀
- 内核：`PagePriorityEnum{INSTANT,REPLACE,NORMAL,INSERT}` + `IJoyousPage.keepable()` + 每玩家队列；
  **视图关闭 ≠ 会话终止** → `onSuspend/onResume` vs `onDetach`（副作用恰好一次）
- 规则：当前页立即关闭、新页延后 1 tick；同帧立即类按 (submittedAt,seq) 依次应用；同 (key,purpose) 去重；
  队列上限 8 / PENDING 过期 10 分钟 / 单轮最多 32 次转移；`PageArbiter.get().install(plugin)` 接入
- chestui：`IGuiInteract` + `AbstractGuiPage`（开界面/重绘/关闭）+ `Canvas` + `InteractHolder`
  + `InteractContainerEnum` + `Click`/`ClickPolicy` + `GuiRouter`；`IPageHandle.page()` 让路由不依赖 `PageEntry`
- input：`ITextInteract` / `IChatInteract` / `ChatPrompt`(label/oldValue/extraHints) / `AbstractChatPage` / `ChatInput`
  **提示的唯一出口是 `ChatInput`**（`onPageShown` 发提示+起超时，`onPageHidden` 撤计时器），消费端只给「问什么/旧值/提示」；
  文案键统一 `ui.input.*`；输入页用 INSTANT 压在挂起界面之上，结束自动恢复
- 已迁移：JMenu（`JMenuPage`，子菜单 REPLACE）、Ward 运行时（`WardRuntimePage`，keepable=非预览）与全部编辑页
  （`WardEditorPage` + 5 个 `WardSubPage` 派生 + `WardItemEditorPage`）、JSell 出售页（`JSellSellPage`，keepable=false）
  与管理员列表/详情（`JSellListPage`/`JSellDetailPage`，INSTANT）、两个 `*FieldChatPage`
- ⚠️ 关闭事件只有 `state()==VISIBLE` 才算玩家主动退出（切页/挂起/终止都会触发 close）
- ⚠️ **`repaint` 写完 Inventory 必须 `player.updateInventory()`**（在被自己取消的点击里改内容，客户端不刷新）；
  `AbstractGuiPage.repaint` 已内置，绕过它直接写 `inventory()` 的地方要自己补
- ⚠️ **返回上级用 `ctx.close()`，不要 open 新实例**（同 key+purpose 去重会命中挂起中的旧实例，界面停在子页）；
  上下级用 INSTANT，REPLACE 只用于「入口换入口」
- ⚠️ `Click.slot` 是**相对所点击容器**的槽位（`event.getSlot()`）；rawSlot 在背包侧带偏移会拿错物品
- ⚠️ 铁砧输入只能用真实 AnvilMenu：`MenuType.ANVIL.builder()...checkReachable(false).build(player)`；
  假 `createInventory` 收不到打字；`openAnvil` 1.21.4 起弃用；`AnvilView#getRenameText()` 读文本（transport 未实现）
- ⚠️ 1.21.8 的 `ClickType` **没有** `COLLECT_TO_CURSOR`（双手收集表现为 DOUBLE_CLICK）
- ⚠️ 跨类增删/签名变更**无法热重载**，必须完全停服重启（否则「旧界面+新监听器」混合体）
- 文档：`docs/interact-api/README.md`（最小示例、容器界面、聊天输入、仲裁器、踩坑清单）
