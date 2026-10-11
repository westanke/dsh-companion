package io.github.hakunm.deepseekharness

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.hakunm.deepseekharness.ui.DeepSeekHarnessApp
import io.github.hakunm.deepseekharness.web.ActiveChatBridge
import io.github.hakunm.deepseekharness.web.ChatWebBridge
import io.github.hakunm.deepseekharness.ui.theme.DeepSeekHarnessTheme

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<HarnessViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeepSeekHarnessTheme {
                DeepSeekHarnessApp(viewModel)
            }
        }
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
