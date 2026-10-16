package io.github.westanke.dshcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import io.github.westanke.dshcompanion.ui.DeepSeekHarnessApp
import io.github.westanke.dshcompanion.web.ActiveChatBridge
import io.github.westanke.dshcompanion.web.ChatWebBridge
import io.github.westanke.dshcompanion.ui.theme.DeepSeekHarnessTheme

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<HarnessViewModel>()

    /**
     * 熄屏断流、亮屏接回（策略由用户定：**熄屏=断连，亮屏=实时**）。
     *
     * 为什么不放在 Activity 的 `onStop`：切到别的 App / 分屏时屏幕还亮着，
     * 那时断流等于白白牺牲实时性；只有**真的熄屏**（按电源键、合盖、超时黑屏）
     * 用户才看不到内容，保持 WebSocket 才是纯耗电。`ACTION_SCREEN_OFF` /
     * `ACTION_SCREEN_ON` 正好把这两种情形分开。
     *
     * 用 `ContextCompat.registerReceiver`（带 RECEIVER_NOT_EXPORTED）而不是
     * 直接 `registerReceiver`：Android 14（API 34）起对动态注册的接收器强制要求
     * 声明是否导出，不写就直接抛 SecurityException —— 而这两个系统广播
     * 只可能由系统本身发出，NOT_EXPORTED 是正确且最严的选法。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> viewModel.pauseEvents()
                Intent.ACTION_SCREEN_ON -> viewModel.resumeEvents()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enableEdgeToEdge()
        setContent {
            DeepSeekHarnessTheme {
                DeepSeekHarnessApp(viewModel)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    /**
     * WebView 版聊天页的图片 / 文件选择结果。
     *
     * 转发给 [ActiveChatBridge] 而不是直接操作某个 bridge 实例：桥接由 Composable 创建，
     * Activity 这边拿不到引用，也不该知道。页面不在时它是 null，这里自然变空操作。
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == ChatWebBridge.REQUEST_PICK || requestCode == ChatWebBridge.REQUEST_CAPTURE) {
            ActiveChatBridge.onPicked(uri)
        }
    }
}
