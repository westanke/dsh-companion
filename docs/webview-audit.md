# WebView 版聊天页静态审计（2026-10-10）—— 已收口为指针

**这份 203 行的静态审计已不再单独维护。** 2026-10-11 文档收口（task-10）时的处理：

- 它的四条结论 —— 附件选择器**永远打不开** / 连点附件互抢 uri / 发送成功后清理方向写反 /
  靠 `indexOf` 判 JSON 字段 —— 已并入
  [`project/webview-report.md`](project/webview-report.md) 的「2.0.2 附件链四个缺陷」，
  含根因与修法，本文不再重复一遍。
- 「原生 vs WebView 页」的功能对照 →
  [`project/webview-parity-audit.md`](project/webview-parity-audit.md)；
  「移植了什么 / 还差什么」→ [`project/webview-port-todo.md`](project/webview-port-todo.md)。
- 它当时**声明没做**的事：只做静态核对，**未编译、未上真机**；另有 5 条「只有真机才能确认」
  （选择器能否弹出、`onActivityResult` 回调、服务端是否接受上传、`sessionId` 时序、卡片观感）。
  这些的后续落地情况见报告文档的「发布状态」一节。

> 为什么保留指针而不是 `rm`：这四个根因只在本文被单独记录过（报告里是压缩版），
> 而这批 WebView 文档当时**都还没进版本库**，`rm` 就是永久丢失。
> 收口要的是「不重复」，不是「删证据」。要彻底删除，需在提交这批文档之后再单独确认。
