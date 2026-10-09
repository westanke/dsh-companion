package io.github.hakunm.deepseekharness.data

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
class AppPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

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

    private companion object {
        const val PREFERENCES_NAME = "dsh_settings"
        const val KEY_BUSY_SEND_MODE = "busy_send_mode"
        const val KEY_GEN_UI_DEBUG = "gen_ui_debug"
    }
}
