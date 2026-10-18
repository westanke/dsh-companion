package io.github.westanke.dshcompanion.data

import android.content.Context
import androidx.core.content.edit

/**
 * 轻量应用偏好。
 *
 * 与 [HostStore] 的分工：那里存的是**凭据与主机**（整体加密，损坏时容错），
 * 这里存的是**纯偏好**（明文即可，丢失只影响一次点击行为，不影响可用性），
 * 所以刻意不共用一套存储，避免把「读偏好失败」卷进凭据的容错路径。
 *
 * 读取一律带兜底：偏好损坏或键缺失时回落到默认值，绝不抛异常 ——
 * 一个设置项的取值问题不该让聊天界面起不来。
 */
/**
 * 界面明暗风格。**必须有「跟随系统」这一档**，不能只给浅/深两选一。
 *
 * 理由：此前界面完全由 `isSystemInDarkTheme()` 决定，用户想「白天浅色、晚上深色」
 * 只能去改系统设置；而直接给两选一又会把这类用户锁死在一种配色里。
 * 三档是移动端的通行做法（对比对象 Whaleness 的 `InterfaceStyle` 也是三档）。
 */
enum class InterfaceStyle {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

class AppPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * 界面明暗风格。默认 [InterfaceStyle.SYSTEM]（保持升级前后的既有观感不变）。
     *
     * 存 `name` 而不是 ordinal：枚举顺序将来若调整，ordinal 会把老用户的
     * 「深色」读成「浅色」——这类静默错位比崩溃更难查。
     */
    var interfaceStyle: InterfaceStyle
        get() = preferences.getString(KEY_INTERFACE_STYLE, null)
            ?.let { name -> InterfaceStyle.entries.firstOrNull { it.name == name } }
            ?: InterfaceStyle.SYSTEM
        set(value) = preferences.edit { putString(KEY_INTERFACE_STYLE, value.name) }

    /**
     * 应用内字号缩放倍数（1.0 = 系统字号原样）。
     *
     * 与系统「字体大小」是**相乘**关系：系统放大 1.2 倍、应用再放大 1.2 倍，
     * 最终 1.44 倍。用户两种需求都存在（「整个手机字都大」与「只有这个 App 字大」）。
     */
    var fontScale: Float
        get() = normalizeFontScale(preferences.getFloat(KEY_FONT_SCALE, 1f))
        set(value) = preferences.edit { putFloat(KEY_FONT_SCALE, normalizeFontScale(value)) }

    /**
     * 字号是否完全跟随系统（勾上时忽略 [fontScale]）。
     *
     * 默认 **false**：默认必须是「系统字号原样显示」，
     * 而不是「忽略用户以前调过的应用内字号」——后者会让升级后字突然变小。
     */
    var fontFollowsSystem: Boolean
        get() = preferences.getBoolean(KEY_FONT_FOLLOWS_SYSTEM, false)
        set(value) = preferences.edit { putBoolean(KEY_FONT_FOLLOWS_SYSTEM, value) }

    /**
     * 实际生效的字号倍数：勾了「跟随系统」就固定返回 1f（系统那份由 Density 自己乘，
     * 不该在这里再乘一次，否则会双重放大）。
     */
    fun effectiveFontScale(): Float = if (fontFollowsSystem) 1f else fontScale

    /**
     * 用户是否**主动选过**界面语言。
     *
     * 为什么需要这个标志：`AppCompatDelegate` 用「空 LocaleList」表示跟随系统，
     * 而首次安装时它同样是空 —— 两者从系统 API 上**长得一模一样**。
     * 没有这个标志，MainActivity 的「首次启动默认中文」就会把用户选的
     * 「跟随系统」每启动一次打回中文一次（用户会以为这个选项坏了）。
     */
    var languageChosen: Boolean
        get() = preferences.getBoolean(KEY_LANGUAGE_CHOSEN, false)
        set(value) = preferences.edit { putBoolean(KEY_LANGUAGE_CHOSEN, value) }

    /** 智能体运行时，Enter 与主发送按钮的默认行为。 */
    var busySendMode: BusySendMode
        get() = preferences.getString(KEY_BUSY_SEND_MODE, null)
            ?.let { name -> BusySendMode.entries.firstOrNull { it.name == name } }
            ?: BusySendMode.QUEUE
        set(value) = preferences.edit { putString(KEY_BUSY_SEND_MODE, value.name) }

    /**
     * dsh-ui 诊断开关：**默认关**。
     *
     * 打开后每条助手消息下方显示一行灰字，逐项报告围栏识别 / JSON 解析 / 节点分发的结果。
     * 存在的理由很直接：这条链路上没有实机可验证，而「没渲染」和「渲染了但空白」
     * 在屏幕上长得一模一样，靠猜改代码等于掷骰子。有了这行字，用户截图发回来就是完整现场。
     */
    var genUiDebug: Boolean
        get() = preferences.getBoolean(KEY_GEN_UI_DEBUG, false)
        set(value) = preferences.edit { putBoolean(KEY_GEN_UI_DEBUG, value) }

    /**
     * 会话详情页用 WebView 版（assets/chat.html）还是原生 Compose。
     *
     * 两条路并存，不是替换：原生版经过真机验证，WebView 版把 Markdown、
     * 代码块与卡片渲染交给浏览器。默认 false —— 未验证的实现不该抢走既有体验，
     * 要换在设置里显式打开，出问题随时能改回来。
     */
    var useWebChat: Boolean
        get() = preferences.getBoolean(KEY_USE_WEB_CHAT, false)
        set(value) = preferences.edit { putBoolean(KEY_USE_WEB_CHAT, value) }

    /**
     * 聊天页里是否显示「上下文注入」块（运行上下文 / 时间上下文 / 后台任务通知…）。
     *
     * 默认 **false**：那些是系统每轮塞进来的东西，手机端没人看，
     * 却会在每条消息之间插一排折叠块，把正文挤得七零八落。
     * 需要排查时再打开。
     */
    var showContext: Boolean
        get() = preferences.getBoolean(KEY_SHOW_CONTEXT, false)
        set(value) = preferences.edit { putBoolean(KEY_SHOW_CONTEXT, value) }

    /**
     * 诊断日志（窗口/会话操作/屏幕变化）总开关。
     *
     * 为什么是**运行时**开关而不只是 buildType：上一轮加在 `ChatDetail.kt` 的探针
     * 只能在 debug 包里活着，而 vivo 真机上 logcat 抓不到自有 App 的日志（见
     * `PITFALLS.md`），取证只能靠屏上探针 + `filesDir/diag.log`。把它提成设置项后，
     * 后期任何一次「会话页是不是原生页在渲染」「落底 effect 跑没跑」的排查
     * 都不用重新出包 —— 装上、开开关、复现、读日志，四步完事。
     *
     * 默认 **false**：诊断浮层是盖在界面上的噪音，正常使用不该看见它。
     *
     * 与 buildType 的关系是「相乘」而不是「替代」：`BuildConfig.DIAG_LOG` 决定
     * 这个代码路径**是否存在**（release 包里整个 DiagLog 静默、浮层不渲染），
     * 本项决定它**是否工作**。所以在 release 包里这个开关就算拨到开也没有任何效果，
     * 这是刻意的 —— 诊断能力不随发布包出厂。
     */
    var diagLog: Boolean
        get() = preferences.getBoolean(KEY_DIAG_LOG, false)
        set(value) = preferences.edit { putBoolean(KEY_DIAG_LOG, value) }

    private companion object {
        const val PREFERENCES_NAME = "dsh_settings"
        const val KEY_BUSY_SEND_MODE = "busy_send_mode"
        const val KEY_GEN_UI_DEBUG = "gen_ui_debug"
        const val KEY_USE_WEB_CHAT = "use_web_chat"
        const val KEY_SHOW_CONTEXT = "show_context"
        const val KEY_DIAG_LOG = "diag_log"
        const val KEY_INTERFACE_STYLE = "interface_style"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_FOLLOWS_SYSTEM = "font_follows_system"
        const val KEY_LANGUAGE_CHOSEN = "language_chosen"
    }
}

/**
 * 字号缩放的合法区间与兜底。
 *
 * 上界 1.5 / 下界 0.8 是与对比对象一致的取值：再大正文一屏放不下几行，
 * 再小中文就糊成一团。**非有限值（NaN/Inf）必须回落到 1f** ——
 * 一个坏偏好若原样塞进 Density，整个界面会以 NaN 尺寸测量，
 * 表现是「白屏且不崩」，比直接崩还难查。
 *
 * 抽成顶层纯函数是为了可单测：偏好本身依赖 Context，测不动，
 * 而这条边界规则是真正会出错的地方。
 */
internal fun normalizeFontScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.8f, 1.5f) else 1f
