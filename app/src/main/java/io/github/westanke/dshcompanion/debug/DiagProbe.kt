package io.github.westanke.dshcompanion.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * ============================================================================
 * 屏上探针：不依赖 adb、不依赖 logcat 的第三条取证通道
 * ============================================================================
 *
 * ## 为什么需要它
 *
 * 前两条通道（logcat / 文件）都要**人在电脑前**。而这个项目的核心问题
 * ——「自动滚动 effect 到底跑没跑」「原生页还是 WebView 页在渲染」——
 * 恰恰需要在**真机上用手指操作**时同步看到数字。文件通道事后 `adb pull`
 * 也能读，但操作当下没法立刻对照。
 *
 * 屏上探针把最近 N 条日志直接画在屏幕上，手指点会话、滑列表、熄亮屏的同时
 * 就能看到 `LIFE` / `ROUTE` / `SCROLL` 分类在刷。上一轮就是这么定位出
 * 「collect 正常发射但被误判成用户滑过」的（探针数字 `first=30/54`）。
 *
 * ## 铁律
 *
 * - **只在 debug 包出现**：`DiagLog.enabled` 为 false 时整个 composable 直接 return，
 *   release 包里连这个函数体都不会进。
 * - **不挡操作**：探针是一小块半透明浮层，`clickable {}` 吞掉点击但不向下传，
 *   所以点它不会误触下面的会话列表；要收起就点它自己。
 */

/**
 * 探针浮层：右上角一小块，显示 [title] 与最近日志。收起后只剩一个小条。
 *
 * @param visible 由调用方传 Compose 状态（`state.diagLog`）进来，
 *   **不能**在函数体里读 `DiagLog.enabled` —— 那个是进程级 `@Volatile`，
 *   不是 Compose 状态，拨开关时不会触发重组，浮层就不会跟着出现/消失。
 *   这正是 `PITFALLS.md` §Compose 状态与 SharedPreferences 那个坑：
 *   「点了开关不动，得再点别的才跳」。buildType 那道闸仍然由
 *   `DiagLog.enabled` 在写日志那一侧把关（release 包里一条都不写），
 *   所以这里只需要管「该不该显示」。
 */
@Composable
fun DiagProbe(title: String, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return

    // **默认收起**。真机实测（2026-10-18）：默认展开时它盖住整个会话页面，
    // 连正文和输入框都看不见 —— 一个诊断工具把被测对象挡住了就没意义了。
    // 收起状态只剩标题一行，要点开是用户主动的动作。
    var expanded by remember { mutableStateOf(false) }
    // 一个计时器：日志是后台追加的，靠它推动重组，否则界面不会自己刷新。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }

    // tick 被读一次，避免「未使用」警告；实际作用是让下面每次重读 recentLines。
    val lines = remember(tick) { DiagLog.recentLines(24) }

    Box(
        modifier
            // **必须避让状态栏**。真机实测：探针挂在 `align(TopStart)` 时从 y=0 起画，
            // 标题行落在状态栏（0~84px）底下 —— 而状态栏归 SystemUI，点击被系统截走，
            // 于是「点一下收起」这个唯一的交互**根本点不动**，探针只能一直挡着。
            .statusBarsPadding()
            // **还必须让开顶栏**（第二次真机踩）。避开状态栏后探针落在 y≈135，
            // 而 App 的汉堡按钮正好也在 `TopStart`（y≈135）—— 探针把导航键盖住，
            // 点汉堡全被探针吃掉，表现为「抽屉打不开」。下移一个 TopAppBar 的高度
            // （56dp）即可让开；代价是展开时会压住列表第一条，这对诊断工具可以接受。
            .padding(top = 56.dp)
            .clickable { expanded = !expanded }
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Color(0xFF7CFC9A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  ×${DiagLog.fileLines()}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Text(
                    if (expanded) " ▾" else " ▸",
                    color = Color(0xFF7CFC9A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (expanded && lines.isNotEmpty()) {
                // 最新在底部、自动跟随：观察「此刻正在发生什么」时最新的才是有意义的。
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    listState.scrollToItem(lines.lastIndex.coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .heightIn(max = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            color = colorFor(line),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 按分类着色：一眼看出这条是生命周期、路由还是滚动。 */
private fun colorFor(line: String): Color = when {
    line.contains(" LIFE ") -> Color(0xFFFFD54F)
    line.contains(" ROUTE ") -> Color(0xFFFF8A80)
    line.contains(" SCROLL ") -> Color(0xFF82B1FF)
    line.contains(" COMPOSE ") -> Color(0xFFB388FF)
    line.contains(" WEB ") -> Color(0xFF80D8FF)
    line.contains(" SCREEN ") -> Color(0xFFFFAB91)
    line.contains(" CLICK ") -> Color(0xFFCCFF90)
    else -> Color(0xFFDDDDDD)
}
