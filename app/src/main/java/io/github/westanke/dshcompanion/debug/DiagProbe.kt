package io.github.westanke.dshcompanion.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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
 * - **能拖开**：见下面 `diagProbeDrag` 的注释 —— 固定坐标试过三次都不够，
 *   第四次改成可拖动。**不要**再回去调死坐标。
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

    // ── 拖动（第四次真机踩之后的根治方案）──────────────────────────────
    //
    // 前三次都靠**调死坐标**让开：statusBarsPadding 避状态栏、再下移 56dp 避顶栏。
    // 2026-10-19 真机第四次复发：下移之后浮层落在 y≈300，而**会话详情页的返回箭头
    // 也在那一行**（顶栏下方是工作区条，返回箭头在其中）—— 点不到返回键，
    // 会话列表就回不去。
    //
    // 根因不是「坐标算错了」，而是**没有一个固定坐标能对所有页面都对**：
    // 主页顶栏在 y≈180、会话详情页那条在 y≈300、平板宽屏又是另一套布局。
    // 每加一个页面就多一种撞法，所以这次改成**可拖动**：让用户（和排查的人）
    // 把它拖到当时不挡路的地方。`PITFALLS.md` 那条本来就点了名
    // 「要么换锚点，要么做成可拖动」—— 前两次只做了前半句。
    //
    // 只允许往右/下拖（偏移量非负）：初始位置已经是「状态栏 + 一个顶栏高度」，
    // 往左上拖只会把它送进状态栏（点击会被 SystemUI 截走）或顶栏，收益为负。
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    var selfWidth by remember { mutableIntStateOf(0) }
    var selfHeight by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier) {
        // 边界用**父容器**的实测尺寸，不猜屏幕宽高：adapter 变化、分屏、平板都不同。
        val maxX = (constraints.maxWidth - selfWidth).coerceAtLeast(0).toFloat()
        val maxY = (constraints.maxHeight - selfHeight).coerceAtLeast(0).toFloat()

        Box(
            Modifier
                // **必须避让状态栏**。真机实测：探针挂在 `align(TopStart)` 时从 y=0 起画，
                // 标题行落在状态栏（0~84px）底下 —— 而状态栏归 SystemUI，点击被系统截走，
                // 于是「点一下收起」这个唯一的交互**根本点不动**，探针只能一直挡着。
                .statusBarsPadding()
                // 初始位置让开顶栏（汉堡按钮所在的 56dp）。
                // 这是**初始值**而不是唯一解 —— 拖动能覆盖掉这个假设不成立的页面。
                .padding(top = 56.dp)
                .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                .onSizeChanged { selfWidth = it.width; selfHeight = it.height }
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, delta ->
                        // 消费掉，否则拖动会同时滚动下面的列表。
                        change.consume()
                        dragX = (dragX + delta.x).coerceIn(0f, maxX)
                        dragY = (dragY + delta.y).coerceIn(0f, maxY)
                    }
                }
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
