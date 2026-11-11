# Joyous 项目长期记忆

## 项目概述
- 「栈流Streack」Minecraft 服务器的玩法功能插件；`com.github.streackmc:Joyous:0.3.0`
- Paper 1.21.8 API + Java 21，GPL-3.0，仓库 https://github.com/StreackMC/Joyous

## 架构
- 模块化：继承 `JoyousModel`，在 `entry.Models` 静态块注册；`onEnable` 里配置→依赖→遍历启用
- 核心层：`Joyous`(全局上下文/权限/命令注册) / `PHAPI`(PAPI 桥接，竞争解析) / `jlogger` / `i18n`
- 配置：`Joyous.conf`(SConfig, 自动重载) + `confDefault`(内置镜像) + `confBuild`(plugin.yml)
- ⚠️ **SConfig 构造器**：`SConfig(String,String)` 是 **(路径, 类型)**；按字符串内容解析必须用
  **三参** `SConfig(content, type, name)`。两参传内容会静默得到空配置
- ⚠️ `SConfig.putString(key, null)` 会 NPE
- ⚠️ **SConfig 嵌套写入（重要）**：0.6.1（线上跑的）与本地 m2 的 `master-SNAPSHOT`（2459 行）
  都有同一个缺陷 —— `putNested` 拿「第一个点」当「最后一个点」，**≥3 层写入**会把前缀重复进叶子键
  （`putString("a.b.c", v)` → `{a={b={"b.c"=v}}}`，读不回；`remove` 则静默无效）。
  `splitWithNormalDot` 已是 master@9dac922(2026-10-14) 的修复版，可实测通过；但**没有发布版含它**
  （最新 tag 仍是 0.6.1 / 2026-08-12）。→ 写配置要么 ≤2 层，要么 `putSection(path, Map)`（1 个点）
- ⚠️ 由此连带：`i18n.tr` 的差量回写写的就是 3 层键，在 0.6.1 上会把 language.yml 写成
  `jsell: { editor.title: … }` 这种残缺键（线上已积 231 个）→ 管理员改这些译文**不生效**（每次回落内置默认）
- 玩家文本走 `Joyous.i18n.tr(...)`(language.yml) + legacy→Component，避免 `sendMessage(String)`

## 模块（10 个）
1. **JMenu** — 双端菜单(Java 箱子 + Floodgate 表单)，`.jmenu` JSON，带 TTL 缓存
   - 描述文件 v2：`display.model`(`legacy_model_data` ≤1.21.3 / `item_model` ≥1.21.4)、`else` 回退按钮
   - 打开与点击共用 `effectiveFor(player)`，否则「看到回退按钮却执行主按钮动作」
2. **Entroprix** — 熵流抽卡(米池、权重、大小保底)，PDC 持久化
3. **JStatusAPI** — 声明式 HTTP 查询(StreackLib HTTPServer)；子端点 Ph/Ban/Status
   - 缓存键必须按请求区分，TTL<=0 禁用；`PHAPI.commonPHAPI` 定义了但从未 registerParser
4. **PlayerTitle** — 称号(占位符+权限)
5. **Mails** — 关服邮件(StreackLib SMail)
6. **EnvExport** — 环境变量 KV
7. **Restarter** — 计划重启/内存检测(Old Gen+泄漏+堆转储)/假人恢复
   - ⚠️ 2026-10-05 审计出 4 个 P0（onDisable 调度任务必被取消致 preventInterrupt 失效、
     计划重启被误判异常关闭、假人名单踢人后才采集、时间条件 List 强转 CCE）见 `2026-10-05.md`
8. **Neonaic** — 与 Neonai-Connector 的桥接 + 群服互联
   - `bridge/`：JSON+LF 分帧 / 握手 / 心跳；UDS 与 HTTP 双通道；插件是**监听端不主动重连**
   - 身份绑定走**一次性绑定码**：MC 取码 → 聊天侧 `/joyous:bind <code>` 认领；关联权威在 Neonaic
   - `docs/neonaic/` 在 .gitignore（本地文档）
9. **JwardChest** — 战利品箱（方块绑定 → 带搜索动画的自定义容器）
   - 命令 `/jwardchest`(`/jc`)；绑定写**区块 PDC**，设置落 `models/JwardChest/profiles.yml`
   - 珍惜度**声明式**：`config.yml` 的 `JwardChest.rarities` 一档一条，声明顺序即强度；
     `WardRarity` 是数据类+静态注册表（equals 按 id，compareTo 按 index），改 id = 换档
   - 「预设代号」= 预设组名（非空则整组联动）；组键用 URL 安全 Base64（避免 `.` 与 SConfig 路径冲突）
   - 容器语义（2026-11-07 共享箱改造）：`contents[]`(权威) + `revealed{玩家→槽}`(私有) + `viewers`
     · 三态唯一判据：有东西且我揭开过=真物品；没揭开=占位；没东西=空（已取走自然落到空）
     · 揭开与领取分离：倒计时只 markSearched，**点击有东西的格子才领取**
     · 搜索顺序 = **每玩家环形游标**（`Progress.cursor`）；⚠️ 别改回「最小未揭开槽位」
       （别人往靠前空槽放东西会把玩家拽回队首）
     · `transact()` = 加锁→校验+修改→解锁→落库→派发；**落库失败必须回滚内存**（否则复制）
     · 刷新只由 `/jc reset*` 或会话过期触发；关服先 `setEvictionListener(null)` 再 clear（否则重启=补货）
     · 品质跟着物品走：`WardTag` 写 PDC `joyous:jc_rarity`，读不到用 `fallback-rarity`
     · 物品模型：`item_model` + `custom_model_data.strings=[品质,状态]`；`item-model` 默认留空
       （没资源包却写键会显示「缺失模型」紫黑格）
     · 数量分布走 `WardDistribution`；⚠️ `pick()` 必须 `floor(shaped*(span+1))`，用 round 会让两端概率减半
   - 界面：自定义 `InventoryHolder`(`WardGui.Holder`)、文本一律过 `WardGui.legacy()`(防斜体)
   - ⚠️ 改完待选物品要 `module.invalidate(id)`，否则表还是旧结果
10. **JSell**（2026-11-09 新增）— 出售系统：玩家 GUI `/jsell`(`/sell`) + 管理 `/jsellset`(`/sellset`)
   - 托管/结算/限额/占位符细节见下方「JSell 要点」

## 外部依赖（全部 provided）
StreackLib 0.6.1 / PlaceholderAPI 2.11.6 / Geyser+Floodgate / Paper 1.21.8 / Spigot 1.21.5

## 数据库（StreackLib Sdb）
- `SdbDatabase.act(String)` 走 `Statement`（无参数位）；`act(SdbActionContext)` 走参数化
- ⚠️ **ActionContext 没有 INSERT**（只有 SELECT/UPDATE/MERGE/DELETE/CREATE/ALTER/DROP/TRUNCATE）；
  写行只能原始 SQL → **值必须白名单断言**（UUID/Base64/数字），自由文本一律塞进 Base64 payload
- ⚠️ 不能混用 `SdbActionContext.table()`（自动加 `table_prefix`）与原始 SQL（不加）→ 会写 A 表读 B 表
- `db.act(sql)` 每次自动提交；多语句用 `try (SdbAction a = db.act()) { a.apply(...); a.commit(); }`

## 关键设计模式
- PHAPI 竞争解析：模块实现 `JoyousPHAPIhandler` 并 `Joyous.PlaceholderService.registerParser`，
  返回 null 表示不处理（`%joyous_xxx%` → params = `xxx`）
- PDC 持久化玩家数据；命令用 Paper Brigadier（`Commands.literal/argument` + `ArgumentTypes.*`）
- 权限用 `Joyous.addPermissions(PermDef.all/op/none(...))`，命令节点用 `.requires(...)`

## Git 约定
- 身份 `Neonai <neonai+coding@kdxiaoyi.top>`，仅命令行 `-c` 携带；只 add 本次产出；不做任何远程同步
- 记忆文件属任务产出，先写记忆再提交

## JSell 要点
- 命令 `/jsell`(`/sell`)：`gui`/`hand`/`inv`/`all`；`/jsellset`(`/sellset`)：编辑界面/`reload`/`pending`/`restore <tx>`
- 权限 **`joyous.jsell.use`（默认 all，但必须有节点）**、`joyous.jsell.admin`
- **可售列表**落 `models/JSell/items.yml`，但**不是分层 YAML**：一行一条整条 Base64
  （`items: [ "<b64>" ]`）。⚠️ 原因：StreackLib 的 `putNested` 把**第一个点**当成「最后一个点」
  （`getIndexOfNormalDot` 命中即 `return i`），≥3 层的写入会把前缀重复进叶子键：
  `putString("items.<uuid>.item", v)` → `items: { <uuid>: { "<uuid>.item": v } }`，读回来找不到。
  **与 deprecated 的 `put()` 无关**（它是 `cache.put(key,value)`，连一层都不拆）。
  → 写配置的规则：路径最多一个点；更深层级用 `putSection(path, Map)` 一次写；**读不限深度**
- 匹配（`SellMatch`）：STRICT = `ItemStack#isSimilar`（忽略数量）；CONTAINS =
  ⚠️ **不能用 `ItemStack#serialize()`**（1.21.5+ 已从 `type/meta` 变成 `id/components`），
  改为「PDC 子集 + Lore 前缀 + 附魔等级 ≥ + 自定义名一致 + 其余**数据组件**逐项相等」；
  组件按 `type.getKey().getKey()` 判名（`CUSTOM_DATA` 等常量在 1.21.8 API 里根本没暴露）
- 托管模型（抗灾核心）：界面里的物品 = 权威内容，每次变动同步写 `jsell_escrow`；
  关界面/断线 = 取消（原样退还 + 删行 + `player.saveData()`），崩服 = 下次上线交付
- 结算协议（`jsell_tx` 多阶段）：PREPARED(只记日志) → COMMITTED(已接管物品) →
  REFUNDED(退款已交还) → PAID(命令可能已跑) → 删除。
  **PREPARED/COMMITTED 可整单退回；REFUNDED/PAID 只能按已售处理**（重复执行命令=刷钱）
- 交付顺序一律「**先删库、再给物品**」：同一微窗口里宁可少给（可人工补），绝不复制
- 「钱」这个外部副作用无法事务化：停在 REFUNDED/PAID 的单子留给 `/jsellset pending` 人工核查
- 限购：`jsell_usage` 流水 + `SUM(amount)`；`limitCount<=0` 不限、`limitSeconds<=0` 终身累计；
  查询失败时保守返回 0（宁可卖不掉，也不打穿限购）
- GUI：6×9，前 5 行放入区，45 关闭 / 49 结算预览 / 53 确认（**金额防抖刷新，`repaint` 只重画物品区**）
- 展示物品永远是 `clone()`（追加 lore / 改名都在副本上）→ 「展示与实物互不影响」的落点
- ⚠️ 取消事件里改光标必须 `runTask` 延后 `setItemOnCursor`，否则被服务端还原
- ⚠️ 改可售列表后要 `store.flush()`；`SellEntryStore` 有材质索引 `byMaterial`（save/delete 置脏）
- 已用「真机自检」验过（临时 selftest + SQLite）：匹配语义、条目/托管/事务存档往返（PDC 不丢）、
  限购额度、SQL 白名单；**未验证**：真人 GUI 交互与命令派发（需客户端）
- ⚠️ 构建注意：VS Code Java 语言服务器会把 ECJ 的「带错误的 class」写进 `target/classes`，
  随后 `mvn package` 增量编译会跳过它 → 出现「`Unresolved compilation problems`」的运行时错误。
  **改完代码一律 `mvn clean package`**
