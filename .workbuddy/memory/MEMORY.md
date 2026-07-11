# Joyous 项目长期记忆

## 项目概述
- **Joyous** 是「栈流Streack」Minecraft 服务器的玩法功能插件
- groupId: `com.github.streackmc`, artifactId: `Joyous`, version: `0.2.5`
- 基于 Paper 1.21.8 API + Java 21
- 许可证: GPL-3.0
- 仓库: https://github.com/StreackMC/Joyous

## 架构
- 模块化设计: 所有功能模块继承 `JoyousModel` 抽象类，在 `entry.Models` 中统一注册
- 核心层: `Joyous`(全局上下文) / `PHAPI`(PlaceholderAPI桥接) / `logger`(多后端日志) / `i18n`(多语言)
- 入口: `entry extends JavaPlugin` — onEnable 中初始化配置→检查依赖→遍历注册模块

## 模块清单 (6个)
1. **SMenu** — 双端通用菜单(Java箱子GUI + 基岩版Floodgate表单), JSON格式菜单文件(.jmenu), 带缓存TTL
2. **Entroprix** — 熵流抽卡系统(米池规则), 权重制概率/大小保底/概率提升, PDC持久化保底状态
3. **APIHolders** — HTTP API服务端(基于StreackLib的HTTPServer), 提供Placeholder查询和服务器状态查询
4. **PlayerTitle** — 轻量玩家称号系统(Placeholder+权限控制)
5. **Mails** — 关服邮件通知(基于StreackLib的SMail)
6. **EnvExport** — 环境变量KV存储(可通过占位符和命令读写)
7. **Restarter** — 服务器重启器(计划重启/关闭、自动重启时间条件+内存检测、假人恢复、preventInterrupt)
   - 内存检测: Old Gen占用率(MemoryPoolMXBean)、连续采样、泄漏检测(Full GC回收率)、堆转储、重启间隔保护
   - 持久化: dat.json (SConfig JSON)，存储 fakePlayers + lastMemoryRestart
   - 语义化执行: performRestart()/performShutdown() 封装最终逻辑

## 外部依赖 (全部 provided scope)
- StreackLib 0.5.2 (核心库,硬依赖)
- PlaceholderAPI 2.11.6 (软依赖)
- Geyser/Floodgate API (软依赖,基岩版支持)
- Paper API 1.21.8 / Spigot API 1.21.5

## 关键设计模式
- PHAPI竞争解析: 多个模块注册 JoyousPHAPIhandler, 按注册顺序竞争解析占位符
- 配置热重载: SConfig.setAutoReload(true), 配置变更自动触发事件
- PDC持久化: 玩家数据使用 Bukkit PersistentDataContainer 存储(保底状态/称号选择)
- 命令注册: Paper Brigadier API (Commands.literal/argument)

## Git 约定
- 提交身份: username="Neonai", email="neonai+coding@streack.top"
- 仅通过命令行 `-c user.name= -c user.email=` 携带，不写入git配置
