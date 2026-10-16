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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        // 首次启动默认中文；**用户选过语言之后绝不再插手**。
        //
        // 判据只能是「选过没有」，不能是 `getApplicationLocales().isEmpty` ——
        // 「跟随系统」在系统 API 里同样是空列表，用空列表当判据会把用户
        // 选的跟随系统每次启动打回中文（详见 AppPreferences.languageChosen 的注释）。
        if (!viewModel.preferences.languageChosen) {
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
            // 主题的两项外观偏好放在**最外层**读：
            // 它们要同时作用于连接页、会话页与设置页，任何一处漏包都会出现
            // 「设置页是深色、退到连接页变浅色」这种半截状态。
            //
            // 读 state（而不是 viewModel.preferences）是关键：偏好本身不是 Compose 状态，
            // 直接读它改了不会触发重组 —— 那正是「点了开关没反应」的经典坑
            // （见 AGENTS.md §4.1 与 PITFALLS.md §Compose 状态与 SharedPreferences）。
            val state by viewModel.state.collectAsState()
            DeepSeekHarnessTheme(
                darkTheme = state.interfaceStyle.isDark(isSystemInDarkTheme()),
                fontScale = state.effectiveFontScale,
            ) {
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
