package io.github.westanke.dshcompanion.web

import android.net.Uri

/**
 * 当前存活的 [ChatWebBridge]。
 *
 * 为什么需要它：文件选择器是 **Activity 发起的**，而回调 `onActivityResult` 落在
 * Activity 上，桥接却在 Composable 里创建。两者之间没有天然通路，所以用这个
 * 极小的持有者接一下 —— 比把桥接一路从 Activity 传到 Composable 干净。
 *
 * 只有一个 WebView 会话页，所以单个槽位够用；换会话时由 [ChatWebScreen] 重新登记。
 */
object ActiveChatBridge {
    @Volatile
    private var current: ChatWebBridge? = null

    fun attach(bridge: ChatWebBridge) {
        current = bridge
    }

    fun detach(bridge: ChatWebBridge) {
        if (current === bridge) current = null
    }

    /** 转发用户选中的文件；没有桥接时忽略（页面已经不在了）。 */
    fun onPicked(uri: Uri?) {
        current?.onPicked(uri)
    }
}
