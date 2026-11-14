# 公共交互层使用指南

> 本文档由 AI 生成，可能包含不准确的内容。

`com.github.streackmc.Joyous.utils.interact` 提供两件能力，三个模块（JMenu / JwardChest / JSell）已经全部迁到它上面：

| 想要的东西 | 用什么 | 一句话 |
|---|---|---|
| 一个自定义容器界面 | `interact.chestui` | 只要回答「长什么样、每格放什么、这一格点了算什么」 |
| 一段文字输入（聊天栏） | `interact.input` | 只要回答「问什么、旧值是什么、拿到文本干什么」 |
| 谁先显示 / 被挤下去怎么办 | `interact`（`PageArbiter`） | 由它排队，你不需要自己管生命周期 |

**不要自己写 `InventoryHolder`、不要自己订阅 `InventoryClickEvent` / `AsyncChatEvent`。**
这三个模块原先各写一套，行为已经各自漂移（取消策略不同、斜体修复三份、关闭退款时机不同），现在统一在这里。

---

## 一、最小可用例子

一个 3 行界面，点第一格弹一次聊天输入，把输入的数量写回模型。

### 1. 容器页面

```java
final class DemoPage extends AbstractGuiPage {

  private final DemoModel model;

  DemoPage(DemoModel model) {
    this.model = model;
  }

  @Override
  public String key() {
    return "demo.page";                 // 稳定、点分、全局唯一
  }

  @Override
  public String purpose() {
    return model.id();                  // 同一 key 的不同对象用 purpose 区分
  }

  @Override
  public boolean keepable() {
    return true;                        // 内容全部来自 model，随时能重画
  }

  @Override
  public InteractContainerEnum container() {
    return InteractContainerEnum.CHEST;
  }

  @Override
  public int rows() {
    return 3;
  }

  @Override
  public Component title(Player viewer) {
    return Canvas.title(viewer, "&8演示界面");   // 支持 & 颜色码与 PAPI
  }

  @Override
  public void render(IPageContext ctx, Canvas canvas) {
    canvas.clear();                     // 每次渲染都从模型现算，不要缓存上次画的东西
    canvas.set(11, Canvas.button(Material.PAPER, "&e设置数量",
        List.of("&7当前：&f" + model.amount(), "&7点击修改")));
  }

  @Override
  public Verdict onClick(IPageContext ctx, Click click) {
    if (!click.top()) {
      return Verdict.IGNORED;           // 背包侧的点击不关心
    }
    if (click.slot() == 11) {
      PageArbiter.get().open(click.player(),
          new DemoInputPage(model),
          PagePriorityEnum.INSTANT);    // 本页被挂起，输入结束后自动回来
      return Verdict.HANDLED;
    }
    return Verdict.HANDLED;
  }
}
```

### 2. 聊天输入页面

```java
final class DemoInputPage extends AbstractChatPage {

  private final DemoModel model;

  DemoInputPage(DemoModel model) {
    this.model = model;
  }

  @Override
  public String key() {
    return "demo.page.input";
  }

  @Override
  public ChatPrompt prompt(Player viewer) {
    // 只给「问什么 / 旧值 / 额外提示」，不要自己拼 Component、更不要 sendMessage
    return ChatPrompt.of("数量", String.valueOf(model.amount()))
        .hint("&7输入 0 表示不限");
  }

  @Override
  public void onInput(IPageContext ctx, String text) {
    try {
      model.amount(Integer.parseInt(text.trim()));
    } catch (NumberFormatException ignored) {
      // 解析失败就保持原值：不要关页面，让他在同一个输入里重来也行
    }
    ctx.close();                        // 关掉输入页 → 仲裁器恢复 DemoPage 并重画
  }
}
```

### 3. 打开它

```java
PageArbiter.get().open(player, new DemoPage(model), PagePriorityEnum.REPLACE);
```

`PageArbiter` 在 `entry.onEnable` 里已经 `install` 过，`GuiRouter` 与 `ChatInput` 都已注册，
**消费端不需要注册任何监听器**。

> 示例为简洁省略了 import；在 `onClick` 里直接 `open` 另一个页面是允许的——仲裁器会把「关旧页 / 开新页」
> 统一延后到下一 tick，不会与正在处理的点击事件抢光标。

---

## 二、容器界面

### 2.1 页面要回答的四个问题

`IGuiInteract` 只有四个实质方法：

| 方法 | 说明 |
|---|---|
| `container()` / `rows()` | 外形与行数，见 `InteractContainerEnum` |
| `title(Player)` | 用 `Canvas.title(player, raw)` 构造（自动 PAPI + 关闭斜体） |
| `render(ctx, canvas)` | 把当前状态画进界面。**允许被反复调用**，必须从模型现算 |
| `onClick(ctx, click)` | 这一格点了算什么；返回 `HANDLED` 或 `IGNORED` |

`AbstractGuiPage` 已经替你做了「开界面、重绘、关闭」三件事，继承它即可。
需要自己控制开界面时机的（例如铁砧那种挂不上 `InteractHolder` 的）才直接实现 `IGuiInteract`。

### 2.2 `render` 必须无副作用、可重入

`render` 会在这些时机被调用：首次显示、从挂起恢复、`repaint(ctx)`、翻页改值之后。
所以：

- ❌ 不要在 `render` 里写库、给物品、发消息
- ❌ 不要把 `ItemStack` 缓存到字段里再画（会看到上一轮的内容）
- ✅ 每次都 `canvas.clear()` 然后从模型现算

### 2.3 `Canvas` 常用方法

```java
canvas.size();                                  // 界面格数
canvas.clear();
canvas.set(slot, item);                         // 越界与 null 都静默忽略
canvas.fill(from, to, item);                    // 铺一段
Canvas.button(Material.PAPER, "&e名字", List.of("&7Lore"));
Canvas.button(material, name, lore, true);      // 带附魔光效（标记当前选中项）
Canvas.legacy("&7颜色码");                       // 文本 → Component（已关斜体）
Canvas.title(player, "&8标题");                  // 颜色码 + PAPI
```

> **为什么所有文本都要过 `Canvas.legacy` / `Canvas.title`**：物品名与 Lore 的原版默认样式是斜体，
> 而 Adventure 反序列化出来的装饰是 `NOT_SET`（继承 = 开启）。`legacy` 会把这些未设置的装饰显式写成
> `false`，否则每个按钮都会是斜体。

### 2.4 `Click` 里有什么

```java
click.player();     // 点击者
click.slot();       // 相对「所点击的那个容器」的槽位
click.top();        // 点的是不是我们的上半区
click.shift();
click.right();
click.holding();    // 光标上是否有东西
click.cursor();     // 光标物品（可能为空气）
click.event();      // 原始事件，需要 getCurrentItem() 之类再用
```

⚠️ **`click.slot()` 不是 `rawSlot`**。背包侧的 `rawSlot` 带着上半区的偏移（54 格容器里点背包第 0 格，
`rawSlot` 是 54），拿它去 `getInventory().getItem(slot)` 会取错人。`Click.slot()` 已经归一化成
「相对所点击容器」的下标，背包侧就是背包下标。

### 2.5 取消策略：`ClickPolicy`

「这一格点了算什么」和「原版搬运允许到什么程度」是两件事。后者由 `policy()` 声明：

| 策略 | 上半区 | 背包侧 | 拖拽 | 适用 |
|---|---|---|---|---|
| `STRICT` | 全取消 | 全取消 | 取消 | 纯按钮界面（详情页、菜单） |
| `TRANSFER_ONLY` | 全取消 | 只拦 Shift / 数字键 / 双击 | 取消 | 需要从背包拿东西放进来的界面（出售、待选物品、列表登记） |
| 自行构造 | 按需 | `Bottom.ALLOW` = 完全不拦 | 按需 | 一般不要用：背包与上半区的边界会变得很难解释 |

```java
// 如果确实需要「背包完全放行」：
new ClickPolicy(true, ClickPolicy.Bottom.ALLOW, true)
```

`TRANSFER_ONLY` 会**取消** Shift+左键，但仍然把这次点击转发给 `onClick`——所以「Shift+左键登记物品」
这种用法照常写：

```java
if (!click.top()) {
  if (click.click() == ClickType.SHIFT_LEFT) {
    addEntry(click.event().getCurrentItem());
  }
  return Verdict.HANDLED;
}
```

### 2.6 容器外形

```java
InteractContainerEnum.CHEST          // 支持 rows 1-6
InteractContainerEnum.DISPENSER      // 9
InteractContainerEnum.HOPPER         // 5
InteractContainerEnum.FURNACE        // 3（燃料/输入/输出，仅外形）
...
```

⚠️ 不要往这里加**带客户端子界面**的类型：铁砧（改名框）、织布机、锻造台、制图台、工作台。
它们的子界面或结果槽由客户端先算，自定义 `Inventory` 拿不到稳定结果。

### 2.7 重绘

```java
repaint(ctx);   // 只在「我还在显示」时重画，并自动 player.updateInventory()
```

⚠️ **改完内容一定要重绘，而不只是 `setItem`**。`repaint` 最后会 `updateInventory()`：
我们经常是**在被我方取消掉的点击里**改内容，客户端不会自行把那一格刷成空，
症状就是「物品取走了但格子里还显示着」。旧 `JSellGui` 有这一句，迁移时漏过一次。

---

## 三、聊天输入

### 3.1 页面要回答的三个问题

`ITextInteract`（父接口）：

```java
String initialValue(Player viewer);              // 初值（聊天栏只用来做「点击粘贴旧值」）
void onInput(IPageContext ctx, String text);     // 已 trim，一定在主线程
default void onCancel(IPageContext ctx) {}       // 取消 / 超时
```

`IChatInteract`（聊天栏版本）多两样：

```java
ChatPrompt prompt(Player viewer);   // 问什么、旧值是什么、额外提示
default int timeoutSeconds() { return 60; }
```

### 3.2 `ChatPrompt` 只描述语义

```java
ChatPrompt.of("单价");                                   // 只问
ChatPrompt.of("单价", "1.5");                            // 带旧值（会渲染成可点击的「粘贴旧值」）
ChatPrompt.of("数量", "3").hint("&7输入 0 表示不限");      // 追加一行提示
```

**所有面向玩家的文本由 `ChatInput` 统一发送**：开头那句、可点击的粘贴旧值、取消提示、超时提示。
消费端不要 `sendMessage`，否则措辞会各写一套（这正是迁移前的状态）。

### 3.3 用 `INSTANT` 打开

```java
PageArbiter.get().open(player,
    new MyInputPage(...),
    PagePriorityEnum.INSTANT);
```

输入页把当前界面**挂起**（不是关掉），提交 / 取消 / 超时后仲裁器自动把它恢复回来并重画。
因此**不需要任何「把界面开回来」的回调**——项目里旧的 `onFinish` / `reopenLater` 都是这个能力的临时替代品。

### 3.4 输入页必须不可保留

`AbstractChatPage` 已经替你声明 `keepable() = false`：打到一半被挤下去，玩家刚打的字就丢了，
与其恢复一个空输入框，不如结束掉让他重来。

### 3.5 超时

默认 60 秒。需要跟配置走就覆写：

```java
@Override
public int timeoutSeconds() {
  return MyMain.CONF.chatInputTimeoutSeconds();
}
```

超时按「取消」处理：发一句提示、调 `onCancel`、关掉页面（下层自动恢复）。

---

## 四、谁先显示：优先度与生命周期

### 4.1 四种优先度

| 优先度 | 当前页面 | 队列 | 用在哪 |
|---|---|---|---|
| `INSTANT` | 挂起（可保留时）/ 终止（不可保留时降级为 REPLACE） | 当前页插到队头 | **打开下级页面、输入页** |
| `REPLACE` | 终止 | 不变 | **入口换入口**：打开菜单、打开运行时容器 |
| `NORMAL` | 不动 | 追加队尾 | 排队等前一个结束 |
| `INSERT` | 不动 | 插到队头 | 下一件要做的事 |

### 4.2 三条最容易搞错的规则

**① 下级页面用 `INSTANT`，不要用 `REPLACE`。**
用 `REPLACE` 会把上级终结掉，下级按 ESC 时无处可回（本项目真踩过：二级页面关不掉回主界面）。

**② 返回上级用 `ctx.close()`，不要 `open` 一个新的上级。**
上级已经被挂起在队列里，再 `open` 一个同 `(key, purpose)` 的实例会命中**去重**、直接返回、
不产生任何转移——界面就停在子页面上不动了。

```java
// ❌ 界面卡在子页面
PageArbiter.get().open(player, new ParentPage(...), PagePriorityEnum.REPLACE);

// ✅ 关掉自己，仲裁器恢复被挂起的上级
ctx.close();
```

**③ `keepable` 只描述「被挤下去的那一页」。**
返回 `true` 前请确认四件事同时成立：内容可重建、目的可延续、时效性未绑定、无「关闭即结算」副作用。
任一不成立就返回 `false`，否则会出现「看起来恢复了、其实状态已经被结算过」。

### 4.3 生命周期钩子

```
PENDING ──出队──▶ VISIBLE ──INSTANT(可保留)──▶ SUSPENDED
   │                 │                            │
   │                 │REPLACE/关闭/退出            │轮到它
   ▼                 ▼                            ▼
DETACHED ◀───────────┴──────────────────── VISIBLE（经 onResume）
```

| 事件 | 钩子 |
|---|---|
| 首次显示 | `onAttach` |
| 被 `INSTANT` 挤下去 | `onSuspend`（停自己的定时任务，模型保留） |
| 从挂起恢复 | `onResume`（重绘、从模型续算，**不要**重跑初始化副作用） |
| 终止 | `onDetach(ctx, why)`（**唯一**允许做收款/退款/落库的地方） |

`onDetach` 的 `why` 有七种：`CLOSED`（玩家关）、`REPLACED`（被别的页面顶掉）、`DISCARDED`（队列满被丢）、
`INVALID`（`stillValid()` 为假）、`TIMEOUT`（排队过期）、`QUIT`、`SHUTDOWN`。

### 4.4 关闭事件不是「玩家退出」

切页、挂起、终止都会触发 `InventoryCloseEvent`。`GuiRouter` 的判定是
**只有 `state() == VISIBLE` 时的关闭才算玩家主动退出**。
自己实现 `IGuiInteract`（不走 `AbstractGuiPage`）时，这条过滤必须照抄。

---

## 五、常见错误清单

| 症状 | 原因 | 做法 |
|---|---|---|
| 物品取走了但格子里还显示着 | 只写了服务端 Inventory | 用 `repaint(ctx)`（内置 `updateInventory()`） |
| 二级页面 ESC 后停在原地 | 用 `REPLACE` 打开的下级 | 改 `INSTANT` |
| 返回上级后界面不回来 | `open` 了一个新上级，被去重拦住 | `ctx.close()` |
| 背包侧 Shift 取到错物品 | 用了 `rawSlot` | 用 `click.slot()` |
| 每个按钮都是斜体 | 直接 `LegacyComponentSerializer.deserialize` | 用 `Canvas.legacy` / `Canvas.title` |
| 聊天输入提示发两遍 | 页面自己 `sendMessage` + `ChatInput` 也发 | 页面只返回 `ChatPrompt` |
| 输入页被别的提示挤掉后恢复成空 | `keepable()` 返回了 `true` | 保持 `false`（`AbstractChatPage` 已默认） |
| 关服/断线时物品被吞或重复 | 在 `onDetach` 里无差别退款 | 只对 `CLOSED` / `REPLACED` 退款；`QUIT` / `SHUTDOWN` 交给持久化与上线交付 |
| 界面在构造时就打开了 | 在构造器里拿 `Player` 做副作用 | 构造无副作用，资源在 `onAttach` 里取 |
| 点击后立刻 `openInventory` 导致光标错乱 | 在事件处理里直接开界面 | 交给 `PageArbiter.open`，它统一延后 1 tick |

---

## 六、参考实现

迁移完成的三个模块可以直接对照：

| 场景 | 类 |
|---|---|
| 简单菜单（多级、子菜单保持 REPLACE） | `JMenu/JMenuPage` |
| 多人共看的运行时容器（广播、领取/放入事务） | `JwardChest/WardRuntimePage` |
| 主编辑界面 + 五个子页面 | `JwardChest/WardEditorPage`、`WardSubPage` 及其派生 |
| 带「保存/取消」的工作副本 | `JwardChest/WardItemEditorPage` |
| 关闭即退款（`keepable = false`） | `JSell/JSellSellPage` |
| 列表 ↔ 详情（INSTANT 进出） | `JSell/JSellListPage`、`JSellDetailPage` |
| 多字段聊天输入（提示差异用 `hint`） | `JSell/JSellFieldChatPage` |

---

## 七、调试与验证

- `/jsell`、`/jc` 这类入口第一次打开若抛 `IllegalStateException: PageArbiter 尚未安装`，
  说明 `entry.onEnable` 里漏了 `install`（正常应该在子模块之前调用）。
- **跨类结构改动（新增/删除类、改签名）不能用 JVM 热重载验证**：HotSwap 只能替换方法体，
  结果会是「旧界面 + 新监听器」的混合体（点击无人处理、关闭无人退款）。必须完全停服重启。
- 启动日志里 `页面仲裁器已启用` 应只出现一次；出现两次说明重复 `install`（旧 Listener 仍在 `HandlerList` 上）。
- 诊断某个玩家的页面栈：`PageArbiter.get().describe(player)`。
