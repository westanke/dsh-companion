package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.debug.DiagLog

/*
 * ============================================================================
 * 诊断日志开关：HarnessViewModel 的两个扩展函数
 * ============================================================================
 *
 * ## 为什么拆出来
 *
 * `HarnessViewModel.kt` 已经贴着 800 行预算（`ArchitectureGuardTest` 第 1 条会红），
 * 而这两个函数的**注释比代码长** —— 它们要讲清楚「为什么要同步两次」「关的时候
 * 为什么要清环形缓冲」，这些正是将来最容易改错的地方。
 *
 * 按 `docs/project/TECH-DEBT.md` 记过的老路数：ViewModel 的成员函数搬到
 * `Harness*ViewModel.kt` 里写成扩展函数，**调用点写法完全不变**
 * （`viewModel.setDiagLog(true)` 照样成立），所以 `ui/` 下所有界面的行为一行都没改。
 *
 * ## 与 MainActivity 的关系
 *
 * `MainActivity.onCreate` 里也有 `DiagLog.syncFromPreference(...)` + `DiagLog.bind(...)`
 * 两行，和这里不是重复，是两个**不同的时机**：
 *
 * | 时机 | 负责什么 | 为什么不能只留一个 |
 * |---|---|---|
 * | `MainActivity.onCreate` | 进程启动时同步 + 绑定文件路径 | 这里是 `filesDir` 唯一拿得到 Context 的地方 |
 * | `HarnessViewModel.init` | ViewModel 每次被创建时同步 | 配置旋转会重建 ViewModel，此时偏好可能已被用户改过 |
 *
 * 顺序上有个坑：`DiagLog.bind()` 里有 `if (!enabled) return`，所以**必须先同步
 * enabled 再 bind**。反过来的话，偏好里是「开」而 enabled 还是初值 false，
 * bind 会直接跳过，之后 enabled 被同步成真也再没人调 bind —— 一条日志都不写，
 * 而设置页明明显示开着。这是那种「看起来配好了其实整个通道没建起来」的静默故障。
 */

/**
 * 用偏好里的开关值同步一次 `DiagLog.enabled`。
 *
 * 由 `HarnessViewModel` 的 init 调用 —— 那时才刚读过 SharedPreferences，
 * 且 Context 一定已经就绪（ViewModel 的构造参数就是 Application）。
 */
internal fun HarnessViewModel.syncDiagLogFromPreferences() {
    DiagLog.syncFromPreference(preferences.diagLog)
}

/**
 * 开关诊断日志。立刻生效，不需要重启。
 *
 * 拨到开的那一刻起日志开始写、屏上浮层开始显示；拨到关则两个都停。
 *
 * 关的时候顺手清掉环形缓冲：留着上一轮的旧行，下次开的时候浮层会先闪一段
 * 与本次无关的日志，容易让人看错时机。
 */
internal fun HarnessViewModel.setDiagLogImpl(enabled: Boolean) {
    preferences.diagLog = enabled
    DiagLog.enabled = enabled
    if (!enabled) DiagLog.clearRing()
    update { copy(diagLog = enabled) }
}
