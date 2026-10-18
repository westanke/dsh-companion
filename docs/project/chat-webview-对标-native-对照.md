# chat.html 对标原生界面 · 本轮对齐清单

- 日期：2026-10-18（续 2.2.44 之后）
- 动因：用户「继续对 chat.html 完善，对标原生界面」。原生是主战场，chat.html 是 WebView 兜底路径。
- 依据：`design-system/deepseek-harness-android/MASTER.md` + `ui/theme/Theme.kt` + 原生 `ChatHeader/ChatComposer/ChatMessageItems`。

## 一、已对齐项

| # | 对标维度 | 原生现状 | chat.html 改动前 | chat.html 改动后 |
|---|---|---|---|---|
| 1 | **配色底色** | 奶白 Paper `#F9F9F7` | 纯白 `#fff`（iOS 冷风） | 奶白 `#F9F9F7` |
| 2 | **强调色** | teal `#006A60` | 蓝 `#2f6fed` | teal `#006A60` |
| 3 | **警示色** | 暖橙 Clay `#C6613F` | 橙 `#c2740a` | 暖橙 `#C6613F` |
| 4 | **用户气泡** | primary=Ink 黑底 `#131313` | 蓝底 `#2f6fed` | Ink 黑底 `#131313` |
| 5 | **圆角** | Material `medium` 8dp | 14dp | 8dp（右下 3dp 小角） |
| 6 | **暗色系** | `#181816` / `#83D5C8` | `#151517` / `#7aa2f7` | 全面对齐 Theme.kt 暗色 |
| 7 | **顶栏按钮** | 文件 + 刷新 + 插话 + 停止 + ⋯ | 仅 插话/停止/⋯ | 加 文件▤ + 刷新⟳ |
| 8 | **AI 标识条** | `ai_generated_notice` 常驻（ChatComposer） | 无 | `#aigen` 常驻「内容由 AI 生成，请甄别后使用」 |
| 9 | **连接状态** | StatusLabel「实时事件已连接/已断开」 | 仅一个点 | 点变色 + 短暂浮现中文状态 |
| 10 | **屏幕标题字体** | 平台衬线体（display/headline） | 无衬线 | Georgia/Noto Serif CJK 衬线 |
| 11 | **空闲占位符** | `message_hint`「描述你想要构建的内容」 | 「输入消息…」 | 「描述你想要构建的内容」 |

## 二、有意不做（能力边界，与 PARITY 审计一致）

- **文件面板**：原生顶栏 FolderOpen 打开的是原生 Sheet（`ChatSessionFiles`），WebView 模式没有「打开原生 Sheet」的桥方法（`DshBridge` 仅 11 个接口），越界造半成品面板无意义。文件按钮点按提示「请在本机原生端查看」。
- **权限档位 Pill**：会话级能力，WebView 够不着，不在此轮范围。
- **会话重命名/归档**：列表页功能，不塞进聊天页。

## 三、验证

- 6 个 JS 文件 `node --check` 全过。
- 配色 token 与原生 `Theme.kt` 一一对应（亮/暗两套）。
- 真机渲染由用户执行（按 AGENTS.md 纪律：涉及 WebView 视觉的改动需真机截图确认）。

## 四、未涉及

- `app/build/` 下 merge 产物属构建生成，下次 `scripts/build.sh` 重新合并，不手改。
- 版本号 / README / 文档同步：**本轮仅改 chat.html 与配套 JS，未动版本号**（按 AGENTS 纪律先改码、未到发布阶段）。
