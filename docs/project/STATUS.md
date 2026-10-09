# 当前状态

- 日期：2026-10-09
- 阶段：本 fork `v1.9.0` 已发布（origin + Gitee 双推、双 tag、双 Release）
- 当前任务：`DOC-003`（过期资料清理）、`PARITY-001`、`IME-001`
- 插件基线：`dsh-remote-bridge` **≥ 2.0.4**（旧名 `dsh-workspace`；Web 挂载前缀仍是历史名 `/dsh-workspace-api`，未随改名变动）
- 包名：`io.github.hakunm.deepseekharness`
- Android 基线：min SDK 26、target/compile SDK 36、JDK 17
- 本 fork 版本线：v1.1.0 多地址连接层 + 配置导入；v1.2.0 插件清单页；v1.4.0 会话文件与图片、链接分流、发图片/文件；v1.9.0 一并带上 v1.5.0–v1.8.0（dsh-ui 卡片原生渲染、卡片诊断开关、发送失败不吞字、取图失败显示真实原因）与 v1.9.0 自身的「长按选中片段 + 双击复制整条、会话文件按类型预览」
- 最近验证：`docsCheck` + `:app:testDebugUnitTest` 通过，**169 用例 / 0 失败**；8 份文档与 35 个任务一致；中英字符串键 291/291 对齐
- 正式产物：`artifacts/DeepSeek-Harness-companion-v1.9.0.apk`，2,714,772 字节，SHA-256 `03007f1cbffb037a51e5e9b87ea0dce76c47dd8a700434fa1abb311544ec3942`（`artifacts/SHA256SUMS.txt` 同步）。`artifacts/` 只保留当前版本、上游基线 `DeepSeek-Harness-v1.0.0.apk` 与 `DeepSeek-Harness-compat02-release.apk`；其余历史 APK 已清理，需要时从对应 Release 重新下载
- 签名：APK Signature Scheme v2，唯一签名者为本 fork 独立证书 `CN=DSH Pocket Client`（RSA 2048），证书 SHA-256 `4e7fa1db395c4a2436e7a96fcdf6c606e20a14bad55dfcd60c80892f4f92deb3`。**与上游 `CN=Hakunm`（RSA 4096）不同**，从上游版本切换过来必须先卸载（卸载会清除已保存的连接）
- 仓库：`https://github.com/westanke/dsh-companion`、`https://gitee.com/westanke/dsh-companion`；上游 `Hakunm/dsh-android-app` 只作参考，**不推送**
- Release：`https://github.com/westanke/dsh-companion/releases/tag/v1.9.0`（Gitee 同名 tag 亦有 Release，资产同名）
- 未做真机 UI 验证：本环境无可用 Android 设备。长按选中/双击复制的手势边界、dsh-ui 卡片实际观感、会话文件预览分流都只过了逻辑测试，需真机确认
- 已知缺口：单测夹具 `ConnectionShareInteropTest.kt` 仍内嵌一枚**已吊销**的真实设备令牌；该文件在 `app/src` 内，是否改写夹具待定（文档侧已全部脱敏）
- 阻塞项：`PARITY.md` 中仍有后续 DSH WebUI 能力；`IME-001` 继续扩大不同厂商键盘实机覆盖
- 下一步：真机验收长按/双击、卡片渲染与预览分流；按需补齐 DSH WebUI 对等能力
