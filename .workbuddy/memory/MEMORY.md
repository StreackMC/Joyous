# Joyous 项目长期记忆

## 概述
- 「栈流Streack」MC 玩法插件；`com.github.streackmc:Joyous`，pom `0.4.1`（最新 tag `0.3.0`）
- Paper 1.21.8 API（线上 1.21.11）+ Java 21，GPL-3.0；依赖全 provided：StreackLib 0.6.2 / PAPI 2.11.6 / Geyser+Floodgate
- 测试服 `mcserver/` 非生产环境，世界/存档/配置随便造随便删

## 架构约定
- 模块继承 `JoyousModel`，`entry.Models` 静态块注册；`onEnable` 配置→依赖→遍历启用；各模块自行 `registerEvents`（无统一入口）
- 核心层 `Joyous`（上下文/权限/命令）、`PHAPI`（PAPI 桥接，竞争解析）、`jlogger`、`i18n`
- 配置 `Joyous.conf`(SConfig 自动重载) + `confDefault` + `confBuild`(plugin.yml)
- 玩家文本一律 `Joyous.i18n.tr(...)` + legacy→Component，不用 `sendMessage(String)`
- **jlogger**：`warn/info/error(Object...)` 首参 String 是模板，末尾 Throwable 不占 `%s` → 实参数=`%s` 数+2；`%s` 多于实参会抛 `MissingFormatArgumentException`；`error/severe` 带堆栈，`warn/info/debug` 把堆栈拼进文本
- PHAPI：实现 `JoyousPHAPIhandler` + `PlaceholderService.registerParser`，返回 null 表示不处理
- 命令 Paper Brigadier；`.requires(...)` 参数是 CommandSourceStack；玩家数据用 PDC
- 权限：命令 `joyous.commands.<模型>.<命令>[.<子类型>]`，其它 `joyous.<模型>.<子动作>`；`Joyous.addPermissions(PermDef.all/op/none(...))`，all=TRUE 默认放行/none=FALSE；「显式缺失」判定=`isPermissionSet && !hasPermission`

## ⚠️ SConfig（动手前先核 pom 版本，当前 0.6.2）
- `SConfig(String,String)`=(路径,类型)，按内容解析用三参 `SConfig(content,type,name)`；`putString(key,null)` NPE；writeMode 默认 autosave
- 嵌套：0.6.1 及更早只坏写/删（putNested 把首点当末点，`a.b.c`→`{a={b={"b.c"=v}}}`，remove 无效），读正常；**0.6.2 已修**（splitWithNormalDot）。中段是标量时 putNested 静默退化成顶层字面键→读不回，写前 `isReachable(key)` 预判
- 每次 putXxx 整文件重写；批量先 `setWriteMode(INERTIA)` 再 `save()`；新建 `new SConfig(file,YAMLc)`→INERTIA→reload→putList(无点键)→save
- ⚠️ 0.6.1 遗留 `language.yml` 有 233 个带点叶子键，0.6.2 不自愈、永远 miss→每次 tr() 触发 autosave；清理=删文件由 jar 重放

## 数据库（Sdb）
- `act(String)` 走 Statement；`act(SdbActionContext)` 走参数化；⚠️ ActionContext 无 INSERT，写行只能原始 SQL→值必须白名单断言（UUID/Base64/数字），自由文本塞 Base64 payload，越界抛 UnsafeSqlValueException
- ⚠️ 不能混用 `table()`（自动加前缀）与原始 SQL（不加）→写 A 读 B；`db.act(sql)` 每次自动提交，多语句 `try(SdbAction a=db.act()){…;a.commit();}`

## Git / 构建
- 身份 `Neonai <neonai+coding@kdxiaoyi.top>` 仅命令行 `-c` 携带；只 add 本次产出；不做远程同步；记忆文件属产出
- `@since` 只填已存在 tag 或当前 pom 版本，禁预写下一个版本
- 构建一律 `mvn -o clean package`（语言服务器会把 ECJ 错误 class 写进 target/classes）

## 模块
1. JMenu 双端菜单（箱子+Floodgate 表单）.jmenu JSON+TTL；v2 display.model（legacy_model_data≤1.21.3/item_model≥1.21.4）、else 回退；打开与点击必须共用 effectiveFor
2. Entroprix 抽卡（米池/权重/大小保底）PDC
3. JStatusAPI 声明式 HTTP，子端点 Ph/Ban/Status；缓存键按请求区分，TTL<=0 禁用
4. PlayerTitle 称号（占位符+权限）；5. Mails 关服邮件（SMail）
6. EnvExport 环境变量 KV；setEnv 直接 putString(key)，键来自占位符→深度不受控
7. Restarter 计划重启/内存检测/假人恢复；⚠️ 2026-10-05 审计 4 个 P0 详见 2026-10-05.md
8. Neonaic 桥接 Neonai-Connector+群服互联；bridge/ JSON+LF 分帧/握手/心跳，UDS+HTTP 双通道，插件是监听端不主动重连；绑定码 MC 取→聊天侧 /joyous:bind 认领，关联权威在 Neonaic；docs/neonaic/ 在 .gitignore
9. JwardChest 战利品箱 /jwardchest(/jc)，绑定写区块 PDC，设置落 profiles.yml
   - 珍稀度声明式（rarities 一档一条，声明序即强度；WardRarity equals 按 id/compareTo 按 index，改 id=换档）；预设代号=预设组名；组键 URL 安全 Base64
   - WardProfile 只走两段路径+扁平 map（profiles.<uuid>+putSection），保持不动
   - 共享箱 contents[] 权威+revealed{玩家→槽}+viewers；三态=有东西且我揭开过→真物品/没揭开→占位/没东西→空；揭开与领取分离（倒计时只 markSearched，点有东西格子才领取）；搜索序=每玩家环形游标
   - transact()=加锁→校验改→解锁→落库→派发，落库失败必须回滚内存；刷新只由 /jc reset* 或会话过期；关服先 setEvictionListener(null) 再 clear
   - 品质跟物品走 PDC joyous:jc_rarity，读不到用 fallback-rarity；item_model+custom_model_data.strings=[品质,状态]，item-model 默认留空
   - ⚠️ WardDistribution.pick() 必须 floor(shaped*(span+1))，round 使两端概率减半；改完待选物品 module.invalidate(id)
10. JSell 见专节

## JSell 要点
- 命令 /jsell(gui|hand|inv|all)、/jsellworth、/jsellset；权限 joyous.commands.jsell*、joyous.jsell.admin、joyous.jsell.group.<组>.{sellable,viewable}（默认 true，动态注册，兼定价门槛）
- 存档一组一文件 models/JSell/groups/<组名>.yml，format:2+entries:[]；组名=文件名 [a-z0-9_-]≤32；只重写 dirtyGroups；无条目且无分组设置才删文件；改分组重写两组靠 persistedGroups 快照；载入先 groups/*.yml 再并入老存档（同 id 以分组文件为准）按 createdAt 排序；只兼容已发布 0.3.0 items.yml（归 default），迁移后改名 items.legacy.bak，有解析失败不改名
- 匹配 SellMatch.difference（0=相同）取代价最小、同价取先登记；去重 findSameTemplate(isSimilar)
- 耐久 hasDurability()=模板带 MAX_DAMAGE；effectiveUnit()=单价×(MAX_DAMAGE−DAMAGE)/MAX_DAMAGE（按实例，缺 DAMAGE 视为满）；逐条用 sale.value()
- 托管界面物品权威，每次变动写 jsell_escrow；关界面/断线取消（原样退还+删行），崩服下次上线交付
- 结算 jsell_tx PREPARED→COMMITTED→REFUNDED→PAID→删；PREPARED/COMMITTED 可整单退回，REFUNDED/PAID 只能按已售；交付先删库再给物品
- 限购 jsell_usage+SUM(amount)，limitCount<=0 不限、limitSeconds<=0 终身，查询失败保守返 0；流水 SellLog+AsyncDailyLog→logs/JSell/yyyy-MM/dd.txt，唯一写入点 SellService.commit()
- 分组设置 SellGroupSettings 写组文件顶层 display/unit.name/economy.commands/economy.decimals，全可空 null=继承；default 组 canOverride()==false；读用 getSection 再取子键，写前 remove 整段重刷；只有设置没条目的组也出现在 groups()；展示名 store.label(group) 唯一出口
- 付款按生效配置聚合 Payout(commands,unit,decimals) 当 key，无覆写时所有组共享→只发一次钱；单位 CONF.unitName/decimals/economyCommands(group)，跨组用全局，界面逐组列小计
- GUI 6×9；管理列表 SLOT_GROUP=52 循环、Shift+左键→分组列表；分组列表左键筛选、右键管理员→设置页；详情 3=分组/5=折损；取消事件改光标要 runTask 延后；改列表后 store.flush()

## 共享交互层 utils/interact
- 不放 StreackLib（核心零 org.bukkit）；包 interact/.chestui/.input；命名 接口 I/抽象 Abstract/枚举 Enum
- 内核 PagePriorityEnum{INSTANT,REPLACE,NORMAL,INSERT}+IJoyousPage.keepable()+每玩家队列；视图关闭≠会话终止→onSuspend/onResume vs onDetach；当前页立即关新页延后1tick、同帧按(submittedAt,seq)、(key,purpose)去重、队列上限8/PENDING过期10分钟/单轮最多32转移；PageArbiter.get().install(plugin)
- chestui：IGuiInteract/AbstractGuiPage/Canvas/GuiRouter/Click+ClickPolicy/InteractHolder；input：ITextInteract/IChatInteract/ChatPrompt/AbstractChatPage/ChatInput，提示唯一出口 ChatInput，文案键 ui.input.*
- ⚠️ 关闭事件 state()==VISIBLE 才算主动退出；repaint 写完必须 player.updateInventory()；返回上级用 ctx.close() 不开新实例；Click.slot 是相对容器槽位非 rawSlot；铁砧只能用真实 AnvilMenu（MenuType.ANVIL.builder()...checkReachable(false).build(player)）；跨类增删/签名变更无法热重载必须停服重启；文档 docs/interact-api/README.md
