package io.github.westanke.dshcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
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
import io.github.westanke.dshcompanion.debug.DiagLog
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
                Intent.ACTION_SCREEN_OFF -> {
                    DiagLog.i(DiagLog.Cat.SCREEN, "ACTION_SCREEN_OFF → pauseEvents()")
                    viewModel.pauseEvents()
                }
                Intent.ACTION_SCREEN_ON -> {
                    DiagLog.i(DiagLog.Cat.SCREEN, "ACTION_SCREEN_ON → resumeEvents()")
                    viewModel.resumeEvents()
                }
            }
        }
    }

    /** 上一次记录到的窗口尺寸（dp）：用来判断「窗口大小真的变了」而不是每次重建都报。 */
    private var lastW = -1
    private var lastH = -1

    /** 一行把「此刻的窗口/屏幕状态」写成可读文本，亮灭屏与旋转都复用它。 */
    private fun windowState(tag: String): String {
        val cfg: Configuration = resources.configuration
        val dm: DisplayMetrics = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val rot = when (cfg.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "横屏"
            Configuration.ORIENTATION_PORTRAIT -> "竖屏"
            else -> "未定"
        }
        // dp 取 Configuration 的 screenWidthDp/screenHeightDp，不用 DisplayMetrics.widthDp ——
        // 后者是 @UnsupportedAppUsage 的隐藏字段，SDK 桩里解析不到，编译直接失败。
        // 抽屉「手机 vs 平板」的 840.dp 分界值比的也是 Configuration 这套 dp。
        return "$tag px=${w}x${h} dp=${cfg.screenWidthDp}x${cfg.screenHeightDp} " +
            "密度=${dm.densityDpi} 方向=$rot"
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
        // 诊断日志三通道的启动顺序：**先同步偏好，再绑定文件**。
        //
        // 反序会踩一个很安静的坑：`DiagLog.bind()` 里有 `if (!enabled) return`，
        // 而 enabled 的初值只取了 BuildConfig（object 懒初始化时还没 Context 读偏好）。
        // 若先 bind，偏好里明明是「开」也会被那个 return 跳过，之后 enabled 被同步成真
        // 也再没人调 bind —— 一条日志都不写，而设置页显示开着。
        //
        // release 包里 BuildConfig.DIAG_LOG 为 false，这两行都是空操作。
        DiagLog.syncFromPreference(viewModel.preferences.diagLog)
        DiagLog.bind(applicationContext)
        DiagLog.i(DiagLog.Cat.LIFE, "onCreate " + windowState("启动时"))
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
        // 窗口尺寸变化（分屏 / 折叠屏展开 / 旋转）**不走** onConfigurationChanged
        // （未在清单里声明 configChanges 时系统会重建 Activity），所以要靠
        // onStart/onResume 里比对尺寸来发现。抽屉「手机 vs 平板」的分界 840.dp
        // 正是由 dm.widthDp 决定的 —— 尺寸一变，整页布局就换一套。
        lastW = resources.displayMetrics.widthPixels
        lastH = resources.displayMetrics.heightPixels
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
                // 配色主题（PAPER / WARM / MIST / INK）。它与明暗风格正交：
                // 前者定色相基调，后者定深浅，组合出 8 份色板。
                // 默认 PAPER = 加这套机制之前的配色，不切换就一个像素都不变。
                appTheme = state.appTheme,
            ) {
                DeepSeekHarnessApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagLog.d(DiagLog.Cat.LIFE, "onStart")
    }

    override fun onResume() {
        super.onResume()
        DiagLog.d(DiagLog.Cat.LIFE, "onResume " + windowState("恢复时"))
        // 回前台时复核「这条连接此刻还通不通」。
        //
        // 为什么要挂在这里而不是连接页内部：连接页在 Compose 组合里，屏幕旋转、
        // 主题切换、切页都会重建它 —— 拿「组合一次」当「回一次前台」会多探好几遍。
        // Activity 的 onResume 才是「用户真的回到这个 App 了」的唯一时机。
        //
        // 这一步也顺带保住那句「连接页是第一屏」的承诺：复核失败会把 connected
        // 翻成 false，而 `App.kt` 的门闩判据（`!connectGatePassed || !state.connected`）
        // 因此成立 —— 界面自然会停/回到连接页，不会把用户送进一个已经断了的会话页。
        viewModel.recheckConnectionIfOnConnectScreen()
    }

    override fun onPause() {
        DiagLog.d(DiagLog.Cat.LIFE, "onPause " + windowState("暂停时"))
        super.onPause()
    }

    override fun onStop() {
        DiagLog.d(DiagLog.Cat.LIFE, "onStop")
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DiagLog.i(
            DiagLog.Cat.SCREEN,
            "onConfigurationChanged " + windowState("配置变化"),
        )
    }

    /** 窗口尺寸真的变了（分屏拖动、折叠屏、旋转）时留一条证据。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w != lastW || h != lastH) {
            val cfg = resources.configuration
            DiagLog.i(
                DiagLog.Cat.SCREEN,
                "窗口尺寸变化 ${lastW}x${lastH} → ${w}x${h}" +
                    "（dp=${cfg.screenWidthDp}x${cfg.screenHeightDp}，抽屉分界 840dp）",
            )
            lastW = w
            lastH = h
        }
    }

    override fun onDestroy() {
        DiagLog.i(DiagLog.Cat.LIFE, "onDestroy（累计写入 ${DiagLog.fileLines()} 行日志）")
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
