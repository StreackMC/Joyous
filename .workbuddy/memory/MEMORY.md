# Joyous 项目长期记忆

## 项目概述
- **Joyous** 是「栈流Streack」Minecraft 服务器的玩法功能插件
- groupId: `com.github.streackmc`, artifactId: `Joyous`, version: `0.3.0`
- 基于 Paper 1.21.8 API + Java 21
- 许可证: GPL-3.0
- 仓库: https://github.com/StreackMC/Joyous

## 架构
- 模块化设计: 所有功能模块继承 `JoyousModel` 抽象类，在 `entry.Models` 中统一注册
- 核心层: `Joyous`(全局上下文) / `PHAPI`(PlaceholderAPI桥接) / `logger`(多后端日志) / `i18n`(多语言)
- 入口: `entry extends JavaPlugin` — onEnable 中初始化配置→检查依赖→遍历注册模块

## 模块清单 (9个)
1. **JMenu** — 双端通用菜单(Java箱子GUI + 基岩版Floodgate表单), JSON格式菜单文件(.jmenu), 带缓存TTL
2. **Entroprix** — 熵流抽卡系统(米池规则), 权重制概率/大小保底/概率提升, PDC持久化保底状态
3. **JStatusAPI** — 声明式 HTTP 查询API(基于StreackLib的HTTPServer, 由 APIHolders 重构而来)
   - 子端点: PhAPI(占位符, JSON5 payload) / BanAPI(封禁查询) / StatusAPI(服务器状态)
   - 基类 `JStatusApiSubhandler` 提供统一声明式缓存 `cached(cacheKey, builder)` / `cachedAt(key)`
   - 注册: `subhandlers[]` + `CONF.path(getPath())`，基路径 `JStatusAPI.baseurl`（默认 `/japi/`）
   - 配置键: `baseurl` / `cors-allowed` / `cache`(秒) / `white-mode` / `ph_list`（支持 `regex:` 前缀）
   - 缓存键必须按请求区分（Status 固定、Ban 按 target 集、Ph 按 payload），TTL<=0 即禁用缓存
   - ⚠️ 已知问题: BanApi 按玩家名查走 StreackLib `checkBan`(旧 NAME 列表)看不到 PROFILE 封禁；
     `PHAPI.commonPHAPI` 定义了但从未 registerParser；`SConfig.putString(key, null)` 会 NPE
4. **PlayerTitle** — 轻量玩家称号系统(Placeholder+权限控制)
5. **Mails** — 关服邮件通知(基于StreackLib的SMail)
6. **EnvExport** — 环境变量KV存储(可通过占位符和命令读写)
7. **Restarter** — 服务器重启器(计划重启/关闭、自动重启时间条件+内存检测、假人恢复、preventInterrupt)
   - 内存检测: Old Gen占用率(MemoryPoolMXBean)、连续采样、泄漏检测(Full GC回收率)、堆转储、重启间隔保护
   - 持久化: dat.json (SConfig JSON)，存储 fakePlayers + lastMemoryRestart
   - 语义化执行: performRestart()/performShutdown() 封装最终逻辑
   - ⚠️ 2026-10-05 审计出 4 个 P0（onDisable 调度任务必被取消致 preventInterrupt 失效、计划重启被误判为异常关闭、假人名单在踢人后才采集致丢失、时间条件 List 强转 CCE），详见 memory/2026-10-05.md
8. **Neonaic** — 与 Neonaic（Neonai-Connector, NodeJS AI/聊天机器人）的桥接 + 玩法特性
   - `bridge/` 传输层: JSON+LF 分帧 / 握手 / 请求响应 / 心跳；UDS(AF_UNIX 监听) 与 HTTP 双通道
   - 连接不由插件生命周期管理：公开 API（`NeonaicSubsystem.startConnection/stopConnection/restartConnection`）
     + 命令 `/neonaic bridge status|start|stop|restart`；`connect.auto-connect` 默认 false
   - **重连**：Joyous 是监听端、不主动重连；对端断开后 accept 循环继续，等 Neonaic 指数退避重拨
   - `chat/` 玩法: 聊天格式渲染、AI 随机/@/命令触发、群服互联(from-mc/to-mc)
     · to-mc 入站的**已绑定来源显示 MC 玩家名**(非 QQ 昵称)——`chat.broadcast` 的 `user` 带 `uuid`/`name`/`nickname`，
       绑定解析只能由 Neonaic 侧做(它持有关联)；新增 `[nickname]` token，`*-cover` 悬浮也走 token 替换
   - **身份绑定**（2026-10-11 重构）: 自助**绑定代码流程**——MC `/neonaicbindidentity` 取一次性代码(缺省5分钟)
     → 聊天侧 `/joyous:bind <code>` 经 `identity.claim` 认领 → MC `/neonaicunbindidentity` 经 `identity.unbind` 解绑；
     关联(qUSR#… ↔ UUID)权威持有方是 Neonaic；旧「直接设置」保留为管理员命令 `/neonaic bind ...`(默认 OP)
   - 占位符 `%joyous_neonaic_name%` / `%joyous_neonaic_subname%`；命令族 `/neonaic ...` + 配置化自助命令
   - 协议 V2 设计见 `docs/neonaic/PROTOCOL-V2.md`（⚠️ `docs/neonaic` 在 .gitignore，属本地文档）
9. **JwardChest** — 战利品箱(2026-10-14 新增): 绑定任意方块为带搜索动画的自定义容器
   - 命令 `/jwardchest`(别名 `/jc`): `new` / `unbind` / `reload` / `reset all|preset <代号>|radius <r>|box <坐标…>`
   - **绑定**写在**区块 PDC**(键含方块坐标，随区块存盘)；**设置**落 `models/JwardChest/profiles.yml`；
     **结果组与搜索进度**只在内存(带维度+坐标，空闲回收 + 总数上限)
   - 五档珍惜度(普通/稀有/典藏/珍贵/绝世)决定搜索时长与完成音效；权重为 0 的条目保留但不参与抽取
   - ②「预设代号」即**预设组名**：不为空 ⇒ 生效设置取组、编辑写回组(整组联动)；为空 ⇒ 用自身设置(覆盖关系)
   - 组在 YAML 里的键用 **URL 安全 Base64**(代号允许 `.`，而 SConfig 路径以 `.` 分隔)
   - GUI 用自定义 `InventoryHolder`(`WardGui.Holder`)区分各类界面；占位玻璃板带 PDC 标记 `joyous:jc_placeholder`
   - **数量分布**(2026-10-15 对齐)：凡「在下限~上限间取整数」都走 `WardDistribution`
     (uniform/low/high + curve)，共两处——结果组总条数(下限和~上限和)、单条结果的物品数量；默认 uniform；
     **总条数下限强制 ≥1** 以免抽出空箱。⚠️ `pick()` 必须用 `floor(shaped*(span+1))`，
     用 `round(shaped*span)` 会让两端概率减半
   - 每珍惜度有 `minDraw`(保底) 与 `maxDraw`(上限)：先发满保底，其余按权重分摊并受上限约束；
     上限 0 = 不限；总条数全 0 时按容器容量抽满
   - 编辑器放入物品 = 登记模板(**不消耗**物品、继承堆叠数量)，列表每次操作后紧凑排列(自动排序)
   - 物品文本一律过 `WardGui.legacy()`(内部 `ensureReset`)，否则原版把物品名渲染成斜体
   - **搜索中占位走「帧」API**(2026-10-15)：`WardGui.Frame(material, text)` 是「一次计算的全部产物」，
     `prefilterFrame()`(粗筛,带秒数) / `slotFrame()`(逐结果,不带秒数) 出帧、`searchingPane(frame)` 出物品；
     控制器 `push(..., Frame)` 用 `Frame[] frameCache` 去重(比较一帧 = 比较整格画出来的样子)。
     进入紧急窗口时**材质与文字一起变**，配色 普通白/稀有绿/典藏紫/珍贵金(ORANGE)/绝世红，
     窗口 = `clamp(时长 × urgency-ratio, urgency-min-seconds, urgency-seconds)`；
     ⚠️ 普通档紧急色即白色，与平时相同(配色必然结果)
   - ⚠️ 未验证部分：GUI 交互与搜索动画需要真人在线，headless 只验到「装配+命令+持久化」

## 外部依赖 (全部 provided scope)
- StreackLib 0.6.1 (核心库,硬依赖)
- PlaceholderAPI 2.11.6 (软依赖)
- Geyser/Floodgate API (软依赖,基岩版支持)
- Paper API 1.21.8 / Spigot API 1.21.5

## 关键设计模式
- PHAPI竞争解析: 多个模块注册 JoyousPHAPIhandler, 按注册顺序竞争解析占位符
- 配置热重载: SConfig.setAutoReload(true), 配置变更自动触发事件
- PDC持久化: 玩家数据使用 Bukkit PersistentDataContainer 存储(保底状态/称号选择)
- 命令注册: Paper Brigadier API (Commands.literal/argument)
- ⚠️ **SConfig 构造器**：`SConfig(String,String)` 是 **(路径, 类型)**；按**字符串内容**解析必须用
  **三参** `SConfig(content, type, name)`（name 可为 null）。用两参传内容会静默得到**空配置**
- 面向玩家的文本走 `Joyous.i18n.tr(...)`（language.yml）+ `NeonaicText`(legacy→Adventure Component)，
  避免已弃用的 `sendMessage(String)` 与硬编码

## Git 约定
- 提交身份: username="Neonai", email="neonai+coding@kdxiaoyi.top"
- 仅通过命令行 `-c user.name= -c user.email=` 携带，不写入git配置
- 记忆文件也要一起提交，所以需要在提交之前先写记忆