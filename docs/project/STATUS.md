# 当前状态

- 日期：2026-10-11
- 阶段：本 fork 的 **2.x 版本线收尾**。`v2.2.20`（`versionCode` 10045）已出包，位于 `artifacts/DeepSeek-Harness-companion-v2.2.20.apk`，尚未打 tag / 发布 Release
- 版本线：v1.1.0 多地址连接 + 配置导入 → v1.2.0 插件清单页 → v1.4.0 会话文件与图片 → v1.9.0 长按选中/双击复制 + 文件按类型预览 → **v2.0.0 起会话详情页增加 WebView 实现（2.2.15 起把网页版呈现方式反向移植回原生，2.2.20 收尾）**
- 包名：`io.github.hakunm.deepseekharness`；Android 基线：min SDK 26、target/compile SDK 36、JDK 17
- 插件基线：`dsh-remote-bridge` **≥ 2.0.4**（旧名 `dsh-workspace`；Web 挂载前缀仍是历史名 `/dsh-workspace-api`，未随改名变动）
- 最近验证：**18 个测试类 / 199 用例 / 0 失败**（口径：`app/src/test/` 下 17 个测试文件、18 个 class、199 个 `@Test`；数字会随代码变化，跑 `scripts/build.sh :app:testDebugUnitTest` 取最新）。本次文档刷新任务**没有复跑**这套测试
- 当前产物：`artifacts/` 只留两个包 + `SHA256SUMS.txt`：
  - `DeepSeek-Harness-companion-v2.2.20.apk`，2,792,754 字节，SHA-256 `a139f025be72f23fdd07bcb1d1dec97985c3f8a6df135d312fa06f2b44ea8fd4`（当前版本）
  - `DeepSeek-Harness-companion-v1.9.0.apk`，2,714,772 字节，SHA-256 `03007f1cbffb037a51e5e9b87ea0dce76c47dd8a700434fa1abb311544ec3942`（旧签名，留作对照）
  - 上游基线 `DeepSeek-Harness-v1.0.0.apk` 与 `DeepSeek-Harness-compat02-release.apk` **已从 artifacts 删除**（git 中为删除状态）；需要时从对应 Release 重新下载
- 签名：**当前用 Android 调试密钥**（`apksigner verify --print-certs` 实测 v2.2.20：`CN=DSH Debug`，证书 SHA-256 `f0e6c7011a78eda9b83e0a67fe896b6efecbd376116b7a16d5ad42e49df0c34a`）。**项目发布证书已丢失** —— v1.9.0 及更早是 `CN=DSH Pocket Client`（`4e7fa1db395c4a2436e7a96fcdf6c606e20a14bad55dfcd60c80892f4f92deb3`），两者不同，**跨签名升级必须先卸载**（会清除已保存的连接）。与上游 `CN=Hakunm` 同样不同。当前包只能自用/内部分发
- 仓库：`https://github.com/westanke/dsh-companion`、`https://gitee.com/westanke/dsh-companion`；上游 `Hakunm/dsh-android-app` 只作参考，**不推送**
- 已打 tag：`v1.1.0` / `v1.2.0` / `v1.4.0` / `v1.9.0`（`git tag` 实测）。**`v2.0.0`–`v2.2.20` 还没有 tag**
- 未做真机 UI 验收：长按选中/双击复制的手势边界、`dsh-ui` 卡片实际观感、HTML 结构预览排版、输入区 `⋯` 菜单在窄屏与各输入法下的可达性，都没有逐项过真机。2.0 之后的多轮修复是**真机反馈驱动**的（见 `app/build.gradle.kts` 版本注释），但那不等于系统性验收
- 已知缺口：单测夹具 `ConnectionShareInteropTest.kt` 仍内嵌一枚**已吊销**的真实设备令牌；该文件在 `app/src` 内，是否改写夹具待定（文档侧已全部脱敏）
- 其他文档：`docs/project/CHANGELOG-DEV.md`（2.x 逐版开发日志）、`docs/project/PARITY.md`（对等矩阵）、`docs/project/webview-report.md`（WebView 线复盘，2.0.0 → 2.2.20；原名 webview-2.2.9-report.md，名字只反映起点不反映覆盖面）、`docs/project/webview-parity-audit.md`（原生 vs WebView 能力边界）、`docs/project/webview-port-todo.md`（移植清单）、`README.en.md`（英文 README，已同步 2.2.20）
- 阻塞项：`PARITY.md` 中仍有后续 DSH WebUI 能力；`IME-001` 继续扩大不同厂商键盘实机覆盖
- 下一步：v2.2.20 打 tag 并发 Release；真机验收长按/双击、卡片渲染、会话交付物清单与输入区 `⋯` 菜单；按需补齐 DSH WebUI 对等能力
