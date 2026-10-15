# 平板（wide）界面审查与优化建议

> 日期：2026-10-15
> 范围：`wide` 分支（`maxWidth >= 840.dp`，`App.kt:130`）。**手机端（`wide=false`）一行不改**。
> 截图基准：W200DS 横屏，1920×1200。

## 0. 现状骨架（改之前先钉住事实）

| 位置 | 现状 | 手机/wide |
|---|---|---|
| `App.kt:130` | 唯一断点 `wide = maxWidth >= 840.dp` | 手机恒 false |
| `App.kt:207` | `NavigationPanel` 固定 `width(264.dp)` | wide 才显示 |
| `App.kt:251` | 手机抽屉 304.dp | 手机路径 |
| `ChatScreen.kt:238` | `SessionPane` 固定 `width(304.dp)` | wide 才显示 |
| `FilesScreen.kt:136` | `FileBrowser` 固定 `width(360.dp)` | wide 才显示 |
| `ChatScreen.kt:2489` | 消息气泡 `fillMaxWidth(0.78f).widthIn(max = 560.dp)` | 双端生效（已合理） |

即：**整个平板体验 = 三根写死的硬宽度 + 一个粗粒度断点**。
MASTER.md:65 早已要求 "Verify … a wide viewport with no clipped text or controls"，
而截图恰恰在 wide viewport 上出现多处 clipped text——规范自己就打了脸。

## 1. 截图发现的问题（按严重度）

### P0 · 会话列表标题大面积截断
`SessionRow` 标题 `maxLines=1 + Ellipsis`（ChatScreen.kt:353），在 304.dp 栏里
`/media/wangke/OFF…`、`阅读https://github.c…`、`DSH 的 dsh-workbud…` 全部腰斩。
平板横向空间充足，304.dp 只是沿用了手机抽屉宽度。
**这是截图里最扎眼的伤。**

### P0 · 未选会话时右侧 >50% 纯空白
第二张截图：wide 分支下 `selectedSessionId == null` 时，
右侧只有居中的一句"选择一个会话"。1610px 预览里 ~835px 是空的。
浪费的是一块本可以放概览/最近项/工作区信息的展示区。

### P1 · 聊天顶栏路径（cwd）单行腰斩
`ChatDetail` 顶栏标题 `session.title ?: workspaceTitle ?: cwd`（ChatScreen.kt:761）
单行 Ellipsis，长路径直接砍尾。平板宽度充裕，砍得没必要。

### P1 · 导航栏 264.dp 在平板上偏宽且空旷
5 个图标+文字项 + 底部连接状态卡片，`264.dp` 吃掉 20% 屏宽，
下方大面积留白。平板标准做法是 NavigationRail（72–80.dp）或收窄到 200.dp。

### P2 · 断点过于粗（只有 840.dp 一档）
800dp 自由窗口 ↔ 900dp 之间布局硬切换：三栏 → 抽屉，没有中间态。
平板分屏/自由窗口（Android 12L+ 很常见）体验会"跳变"。

### P2 · 会话列表信息密度是手机规格
每行只有 标题 + "空闲 workspace" 两行。平板上可以加"最后活跃时间/模型"
作为第三列（可选增强，不强制）。

### P3 · 未验收项
- 触控笔/鼠标 hover 态缺失（Material3 默认无 hover 反馈）
- 输入框键盘避让后消息列表底部留白
- 导航栏底部连接状态卡片（`http://192.168.1.126:3090/api/v1`）两行偏长

## 2. 优化建议（全部收敛在 `wide` 分支，手机零影响）

> 共同前提：**所有改动都写在 `if (wide)` 分支里**，或对 `wide` 参数敏感的
> 可选参数（默认值=手机现状）。手机上 `wide` 恒 false，编译期即可确认
> 手机路径无 diff。

### 方案 A · 会话列表宽度自适应（P0，改动最小、收益最大）
把 `ChatScreen.kt:238` 的硬 304.dp 改成随屏宽缩放、带上下限：

```kotlin
val pane = Modifier
    .width((maxWidth * 0.22f).coerceIn(280.dp, 400.dp))
    .fillMaxHeight()
```
需在 `ChatScreen` 外用 `BoxWithConstraints` 取 `maxWidth`，
或直接把 `maxWidth` 透传进来（`ChatScreen` 已收 `wide: Boolean`，
扩成 `wideWidth: Dp` 一行改动）。
收益：304→~360.dp（960dp 屏）标题截断率大降；手机 304.dp 不动。

### 方案 B · 未选会话时右侧放「概览」而非空（P0）
wide 且 `selectedSessionId == null` 时，`detail` 区渲染一个
`SessionOverviewPane`：
- 当前工作区/连接的主机（已有 `state.device`）
- 最近 5 个会话卡片（可点击直接选）
- 一键"新建会话"

替代当前居中空态。手机路径（`selected==null` 走 `SessionPane` 全屏）不动。

### 方案 C · 顶栏路径截断改为「中间省略」或换行（P1）
`ChatDetail` 顶栏 cwd 在 wide 时 `maxLines = 2`，
或保持单行但把省略号放中间（`…OFFICE/TRae_work/dsh-companion`）。
`session.title` 非空时仍单行（标题短不必换行）。

### 方案 D · 导航栏收窄（P1）
`App.kt:207` 的 264.dp 在 wide 且 `maxWidth >= 1200.dp` 时可用
NavigationRail（80.dp，纯图标+tooltip），
`840–1199.dp` 保持 264.dp 或收窄到 220.dp。
省下的宽度自动补给内容区。

### 方案 E · 断点细化（P2，可延后）
`840.dp` 单一断点 → 三档：
- `>= 1200.dp`：rail + 会话列表 + 内容（三栏）
- `840–1199.dp`：导航 + 会话列表 + 内容（三栏，现行）
- `600–839.dp`：导航 + 内容（两栏，会话列表收进内容页顶栏抽屉）
自由窗口缩到 839dp 时不再硬跳抽屉。

### 方案 F · 会话行加"最后活跃时间"（P2，可选）
`ChatSession` 若已有时间戳，wide 下 `SessionRow` 右列显示
`HH:mm`，小字 `onSurfaceVariant`。手机保持现状。

## 3. 优先级与工作量

| 方案 | 解决 | 改动面 | 风险 | 建议 |
|---|---|---|---|---|
| A | P0 截断 | 1 个 composable + 1 处宽度 | 极低 | **首选，先做** |
| B | P0 空白 | 新增 1 个 pane | 低 | 紧随 A |
| C | P1 顶栏 | 顶栏 Text 两行 | 极低 | 顺手 |
| D | P1 导航 | 新增 rail 分支 | 中 | 第二波 |
| E | P2 断点 | 断点逻辑重构 | 中 | 看排期 |
| F | P2 密度 | 会话行 | 低 | 可选 |

## 4. 验证口径（按 AGENTS.md 第 5 节）
- 每方案落地带回归用例（现有 `ui/` 下 5 个 `XxxTest.kt` 可参照）
- 手机 360dp/412dp 截图与 wide 1280dp 截图对账（MASTER.md:65）
- 走 `scripts/build.sh :app:testDebugUnitTest`，再数 XML 用例数
- 真机（平板）验收 P0/P1，P2 按排期

## 5. 一句话结论
**先做 A + B + C（三个 P0/P1，改动小、纯 wide 分支、手机零 diff），
D/E/F 进 TECH-DEBT 台账排期。** 这三件做完全屏观感立刻从
"手机上拉宽了的抽屉"变成"为平板设计的桌面端"。
