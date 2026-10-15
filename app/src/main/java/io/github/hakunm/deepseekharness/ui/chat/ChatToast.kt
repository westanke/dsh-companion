package io.github.hakunm.deepseekharness.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import io.github.hakunm.deepseekharness.R

/**
 * 一次性反馈与剪贴板助手。
 *
 * 刻意用系统 Toast 而不是 Snackbar：ChatDetail 没有 Scaffold/SnackbarHost，
 * 为了一句「已复制」去引入整套宿主结构，改动面远大于收益；而这些反馈都是
 * 「动作已完成」的通知，不需要用户在其中做选择（审批面板那种才需要留在界面上）。
 *
 * 从 `ui/ChatScreen.kt` 拆出时提取为共享文件：复制整条消息、复制工具输出、
 * 复制诊断详情，以及链接打不开时的提示，多处共用同一套写法。
 */
internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 把一段文本写进剪贴板并给一次「已复制」；[label] 只用于剪贴板条目的来源标注。 */
internal fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    toast(context, context.getString(R.string.copied))
}
