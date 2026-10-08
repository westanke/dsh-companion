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

    private companion object {
        const val PREFERENCES_NAME = "dsh_settings"
        const val KEY_BUSY_SEND_MODE = "busy_send_mode"
    }
}
