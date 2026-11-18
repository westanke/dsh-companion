package io.github.westanke.dshcompanion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.debug.DiagProbe
import io.github.westanke.dshcompanion.refreshDashboard
import io.github.westanke.dshcompanion.ui.theme.DshMotion
import io.github.westanke.dshcompanion.ui.theme.DshSpacing
import io.github.westanke.dshcompanion.ui.theme.DshTheme
import io.github.westanke.dshcompanion.ui.theme.DshTypography
import io.github.westanke.dshcompanion.ui.theme.LocalDshColors

private enum class MainSection(val label: Int) {
    Chat(R.string.chat), Files(R.string.files), Plugins(R.string.plugins),
    Models(R.string.models), Settings(R.string.settings),
}

@Composable
fun DeepSeekHarnessApp(viewModel: HarnessViewModel) {
    val state by viewModel.state.collectAsState()
    // 连接页门闩是**进程级**的（见 HarnessViewModel.connectGate）：这里只读，写入只发生在
    // 用户主动发起连接的那一刻（ConnectScreen 的 onEnterApp）。
    val connectGatePassed by viewModel.connectGate.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { localError(it) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbar.showSnackbar(errorMessage)
            viewModel.clearError()
        }
    }
    // 明暗口径与设置页一致：跟随系统 / 强制浅色 / 强制深色，三档共用同一个判据。
    val darkTheme = state.interfaceStyle.isDark(isSystemInDarkTheme())    // 诊断探针挂在**最外层、且在内容之后**渲染。
    //
    // 为什么是这个位置（真机踩过两次）：
    //   · 放在每一页内部各挂一个 → 漏页、且要改多处；
    //   · 放在内容**之前** → 被后面的 Scaffold/Surface 整个盖住，看不见。
    //     Compose 的绘制顺序就是声明顺序，后声明的在上层。
    // 所以：一个 Box 包住全部内容，探针作为**最后一个**子项。
    Box(Modifier.fillMaxSize()) {
        // 官方模式：壳层（顶栏 / 抽屉 / 会话列表 / 设置页）整体用 DSH 官方色板。
        //
        // 聊天详情页**不在这一层里**：它在 ChatScreen 内部用 LegacyChatTheme 把暖调老样式
        // 原样包回去，保证「聊天页一个像素都不动」。两层各自还原，互不干扰。
        DshTheme(darkTheme = darkTheme) {
            when {
                // 连接页优先于一切：冷启动第一屏永远是它 —— 哪怕主机已经记住、哪怕后台已经自动连上。
                //
                // 判据刻意**不是** state.connected：冷启动会自动重连最近用过的那台电脑，连上之后
                // 它就是 true，拿它当条件会直接跳过连接页 —— 那正是这次要修的毛病。
                //
                // 也刻意排在 restoring 的转圈**之前**：冷启动的自动重连（restoring 为真）要依次探活
                // 全部地址，可能持续好几秒；转圈排在前面，这几秒里用户看到的就是转圈而不是连接页了。
                !connectGatePassed || !state.connected -> Box(Modifier.fillMaxSize()) {
                    ConnectScreen(state, viewModel, onEnterApp = viewModel::passConnectGate)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
                // 已放行、但快照仍在恢复：留一个转圈兜底。当前路径下 connected 为真时 restoring 必已置 false，
                // 所以正常看不到它；留着是为了不把「恢复中」误当成「已就绪」（ConnectedApp 需要 device 等一整套数据）。
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
                else -> ConnectedApp(state, viewModel, snackbar, darkTheme)
            }
        }
        DiagProbe("DSH伴侣", visible = state.diagLog)
    }
}

@Composable
private fun ConnectedApp(
    state: HarnessState,
    viewModel: HarnessViewModel,
    snackbar: SnackbarHostState,
    darkTheme: Boolean,
) {
    val scopes = state.device?.scopes.orEmpty()
    var section by remember(scopes) {
        mutableStateOf(
            when {
                "chat.read" in scopes -> MainSection.Chat
                "files.read" in scopes -> MainSection.Files
                else -> MainSection.Settings
            },
        )
    }
    // 抽屉开合状态：手机端是**推挤式**（官方 dsh-Chat 的做法），平板端是覆盖式。
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerShift by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = tween(DshMotion.drawerShiftMs, easing = FastOutSlowInEasing),
        label = "drawer-shift",
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 840dp 魔数收敛成具名常量的三档判据（见 AdaptiveBreakpoints.kt）。
        // **语义与改造前二进制等价**：isWideLayout(maxWidth) == maxWidth >= 840.dp，
        // 所以手机端（Compact / Medium）走的还是原来那条 else 分支，一个字符都没动。
        // 单测 PaneModeTest 在 599/600/839/840/1199/1200 六个边界上钉住这条等价性。
        val wide = isWideLayout(maxWidth)
        // 切 section / 切页面（wide 变化）时**自动收起**：抽屉是一次性的，
        // 选完就该把屏幕还给内容。这个 Effect 必须放在 BoxWithConstraints 内部 ——
        // wide 是下面才算出来的。
        LaunchedEffect(section, wide) { drawerOpen = false }

        // WebView 聊天页开着时，**关掉抽屉的滑动手势**。
        //
        // Material3 的 ModalNavigationDrawer 默认允许从屏幕任意位置横向拖动打开，
        // 它和 WebView 的滚动在同一个手势通道上抢触摸：用户在会话里上下滑动时
        // 只要手指带一点横向分量，抽屉就被拉出来（会话/文件/插件/设置），
        // 而且滚动会中途被打断——手感就是「不流畅」。这两个症状是同一个根因。
        //
        // 这里的抽屉是自绘的推挤式（左缘 20dp 热区才拖得动），本来就抢不到 WebView 的滚动，
        // 所以这条约束只作用于左缘热区：聊天页里它不参与手势判定。
        val webChatOpen = !wide && section == MainSection.Chat &&
            state.selectedSessionId != null && state.useWebChat
        val content: @Composable () -> Unit = {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (!wide) {
                        // 官方顶栏：44dp 一行（menu + 标题 + 右侧动作），没有二级状态行。
                        //
                        // 会话详情页上方仍保留这条「返回应用」的品牌行：聊天详情页自己的
                        // 标题栏在下面一行，两者不重叠。
                        val inChatDetail = section == MainSection.Chat &&
                            state.selectedSessionId != null && !state.useWebChat
                        ShellTopBar(
                            title = stringResource(
                                if (inChatDetail) R.string.app_name else section.label,
                            ),
                            onMenu = { drawerOpen = true },
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    Modifier.padding(14.dp).size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(onClick = viewModel::refreshDashboard) {
                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    // 平板（wide）左缘的**独立竖条**：48dp 宽，装着导航抽屉的开关。
                    //
                    // 为什么不再浮在内容之上：浮层版把汉堡按 `align(TopStart)` 压在内容左缘，
                    // 正好盖住 ChatScreen 会话列表栏顶部的「会话」标题 —— 那一整片点击被上层
                    // 吃掉，用户实测「抽屉图标和会话列表标题重复了，无法点击」。
                    // 竖条不遮任何内容：它自己占 48dp，内容区从它右边开始，天然不重叠。
                    if (wide) {
                        Surface(
                            color = LocalDshColors.current.bgLayer1,
                            modifier = Modifier.width(48.dp).fillMaxHeight(),
                        ) {
                            IconButton(
                                onClick = { drawerOpen = !drawerOpen },
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Icon(
                                    if (drawerOpen) Icons.Outlined.Close else Icons.Outlined.Menu,
                                    stringResource(R.string.navigation),
                                )
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = section,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        transitionSpec = {
                            (fadeIn() + slideInHorizontally { it / 14 }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 14 })
                        },
                        label = "main-section",
                    ) { target ->
                        when (target) {
                            MainSection.Chat -> ChatScreen(state, viewModel, wide)
                            MainSection.Files -> FilesScreen(state, viewModel, wide)
                            MainSection.Plugins -> PluginsScreen(state, viewModel)
                            MainSection.Models -> ModelsScreen(state, viewModel)
                            MainSection.Settings -> SettingsScreen(state, viewModel)
                        }
                    }
                }
            }
        }

        if (wide) {
            Box(Modifier.fillMaxSize()) {
                content()
                // 平板导航抽屉：默认收起（见 drawerOpen 的注释），
                // 展开时以 overlay 形式盖在内容之上 —— 288dp 与官方抽屉同宽。
                AnimatedVisibility(
                    visible = wide && drawerOpen,
                    enter = fadeIn() + slideInHorizontally { -it / 3 },
                    exit = fadeOut() + slideOutHorizontally { -it / 3 },
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Surface(
                            color = LocalDshColors.current.bgLayer1,
                            modifier = Modifier.width(DshSpacing.drawerWidth).fillMaxHeight(),
                        ) {
                            NavigationPanel(
                                state = state,
                                selected = section,
                                onSelected = {
                                    section = it
                                    drawerOpen = false
                                },
                                onRefresh = viewModel::refreshDashboard,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // 抽屉右侧这一条透明可点区域 = 点它收抽屉（ModalDrawer 也是这个语义）。
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { drawerOpen = false },
                        )
                    }
                }
            }
        } else {
            // 手机端：**推挤式**抽屉（官方 dsh-Chat 的做法）。
            //
            // 为什么不用 ModalNavigationDrawer：官方抽屉是「整页右移一个抽屉宽」，
            // 覆盖式会在抽屉右缘漏出输入框；这里把整页 content 平移 288dp，
            // 抽屉就填进左边让出来的空档里，一像素都不重叠。
            val dsh = LocalDshColors.current
            Box(Modifier.fillMaxSize()) {
                // 1. 页面本体：抽屉打开时整体右移一个抽屉宽。
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = DshSpacing.drawerPageShift.toPx() * drawerShift
                        },
                ) {
                    content()
                }
                // 2. 灰幕：浅色下官方是全透明，深色是 0x50000000。
                //
                //    **关抽屉后这层不再吃点击**（2026-11-18 修，#21）。
                //
                //    之前的写法是「永远在，但 clickable(enabled = drawerOpen)」：
                //    alpha=0 的 Box 在视觉上完全透明，可它带着 `.clickable`，
                //    而 `.clickable` 本身就是一次 pointerInput —— **带 pointerInput 的容器
                //    永远参与命中测试**，`enabled=false` 只关掉回调，不关掉命中。
                //    于是这层透明层一直盖在右边 288dp 上，把整条右缘（真机实测 x≥864px）
                //    的点击全吃掉：会话详情页「当前任务」面板右端的箭头、顶栏右上角的 ⋯ 菜单，
                //    以及各页面右上角图标按钮，全都点不动。
                //
                //    「没有 pointerInput 的容器不参与命中」这条只对**没有** pointerInput 的
                //    容器成立 —— 旧注释把它套在 clickable 上，是错的，已改掉。
                //
                //    判据收在 `drawerScrimState()`（纯函数，见 DrawerScrim.kt）：
                //    「画不画」跟「吃不吃点击」分开 —— 关抽屉的 250ms 动画里灰幕继续画
                //    （深色主题下不会突兀闪一下），但从用户点「关闭」那一刻起就不吃点击。
                val scrim = drawerScrimState(drawerOpen, drawerShift)
                if (scrim.visible) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = DshSpacing.drawerWidth)
                            .background(
                                dsh.drawerScrim.copy(alpha = dsh.drawerScrim.alpha * drawerShift),
                            )
                            .then(
                                if (scrim.catchesTaps) {
                                    Modifier.clickable { drawerOpen = false }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                // 3. 抽屉本体：从左边滑入，宽 288dp。
                if (drawerShift > 0.01f) {
                    Box(
                        Modifier
                            .width(DshSpacing.drawerWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = -DshSpacing.drawerWidth.toPx() * (1f - drawerShift)
                            },
                    ) {
                        Surface(color = dsh.bgLayer1, modifier = Modifier.fillMaxSize()) {
                            NavigationPanel(
                                state = state,
                                selected = section,
                                onSelected = {
                                    section = it
                                    drawerOpen = false
                                },
                                onRefresh = viewModel::refreshDashboard,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                // 4. 左缘 20dp 拖拽热区：官方靠它拉出抽屉。
                //    WebView 聊天页里不参与手势（webChatOpen），避免和网页滚动抢触摸。
                if (!drawerOpen && !webChatOpen && !state.composerOverlay) {
                    val dragTotal = remember { mutableFloatStateOf(0f) }
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(DshSpacing.drawerEdgeDragWidth)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { dragTotal.floatValue = 0f },
                                    onHorizontalDrag = { _, amount ->
                                        dragTotal.floatValue += amount
                                        if (dragTotal.floatValue > 24f) {
                                            drawerOpen = true
                                            dragTotal.floatValue = 0f
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }
    }
}

/**
 * 官方顶栏：44dp 一行 —— 左侧 menu，中间标题，右侧动作。
 *
 * 高度用 [DshSpacing.topBarHeight] 而不是写死 44：IconButton 的最小触控高是 48dp，
 * 硬卡 44 会把热区压到 48 以下（无障碍不达标），所以这里用 [heightIn] 兜下界。
 */
@Composable
private fun ShellTopBar(
    title: String,
    onMenu: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val dsh = LocalDshColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(dsh.bgBase)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = DshSpacing.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Outlined.Menu, stringResource(R.string.navigation), tint = dsh.labelPrimary)
        }
        Text(
            title,
            Modifier
                .weight(1f)
                .padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = DshTypography.pageTitle,
            color = dsh.labelPrimary,
        )
        trailing()
        Spacer(Modifier.width(DshSpacing.screenPad))
    }
}

/**
 * 侧边导航面板（官方抽屉内容）。
 *
 * 官方抽屉是「纯文字行 + 底部设置/连接点」，我们这里多了文件/插件/模型/设置四个分区，
 * 所以保留图标，但行的观感按官方来：选中项用 chipSelectedBg 底 + brand 文字。
 */
@Composable
private fun NavigationPanel(
    state: HarnessState,
    selected: MainSection,
    onSelected: (MainSection) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopes = state.device?.scopes.orEmpty()
    val dsh = LocalDshColors.current
    // 抽屉本体是覆盖在窗口上的（不吃系统栏 inset），所以要自己消费：
    // 顶部让开状态栏（否则品牌行会被状态栏切掉），底部让开导航栏
    // （否则 endpoint 那行会压在 Home 指示条上）。官方抽屉是 SafeArea，这里同理。
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.drawerItemPadH, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = DshSpacing.brandMarkGap),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = DshTypography.pageTitle,
                    color = dsh.labelPrimary,
                )
                Text(
                    state.device?.name ?: stringResource(R.string.mobile_workspace),
                    style = DshTypography.small,
                    color = dsh.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 配对时的名字一旦被声明（nameSource=declared），桥就**不再自动更新**它 ——
                // 这是刻意的，免得覆盖用户起的名字。代价是：把同一台设备（或同一份令牌）
                // 换到另一支手机上之后，这里会一直显示旧设备的平台与型号，而界面上没有任何
                // 线索说明它是「声明过的名字」还是「此刻这台机器」。
                //
                // 解法取 issue #7 的方案 1：名字与**桥回传的 model** 不一致时，补一行真实的
                // 当前设备。数据本来就有（DeviceView 的 platform/model），只是此前没显示。
                val declared = state.device?.name
                val actualModel = state.device?.model
                if (declared != null && actualModel != null && declared.contains(actualModel).not()) {
                    Text(
                        text = stringResource(
                            R.string.drawer_actual_device,
                            // platform 是客户端自报的小写标识（`android`），直接拼出来像型号的一部分；
                            // 首字母大写后读作「当前设备 · Android · V2080A」，与上面的名字区分开。
                            listOfNotNull(
                                state.device?.platform?.replaceFirstChar { it.uppercase() },
                                actualModel,
                            ).joinToString(" · "),
                        ),
                        style = DshTypography.caption,
                        color = dsh.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !state.busy) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                }
            }
        }
        Spacer(Modifier.size(DshSpacing.drawerGroupPadV))
        MainSection.entries.forEach { item ->
            if (!sectionEnabled(item, scopes)) return@forEach
            DrawerRow(
                label = stringResource(item.label),
                icon = sectionIcon(item),
                selected = selected == item,
                onClick = { onSelected(item) },
            )
        }
        Spacer(Modifier.weight(1f))
        // 底部：连接状态点 + 设备地址（官方抽屉底部是「设置 + 连接点」）。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DshSpacing.drawerFooterPadH, vertical = DshSpacing.drawerItemPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (state.eventsConnected) dsh.statusOnline else dsh.statusOffline),
            )
            Text(
                if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                Modifier.padding(start = 5.dp),
                style = DshTypography.caption,
                color = dsh.labelSecondary,
            )
        }
        Text(
            state.endpoint,
            Modifier.padding(
                start = DshSpacing.drawerFooterPadH,
                end = DshSpacing.drawerFooterPadH,
                bottom = DshSpacing.drawerItemPadV,
            ),
            style = DshTypography.caption,
            color = dsh.labelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 抽屉里的一行：图标 + 文字，选中态用官方 chipSelectedBg / chipSelectedFg。 */
@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dsh = LocalDshColors.current
    val tint = if (selected) dsh.chipSelectedFg else dsh.labelSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) dsh.chipSelectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                horizontal = DshSpacing.drawerItemPadH,
                vertical = DshSpacing.drawerItemPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = tint)
        Text(
            label,
            Modifier.padding(start = 12.dp),
            style = DshTypography.small,
            color = if (selected) dsh.chipSelectedFg else dsh.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sectionIcon(section: MainSection): ImageVector = when (section) {
    MainSection.Chat -> Icons.Outlined.ChatBubbleOutline
    MainSection.Files -> Icons.Outlined.FolderOpen
    MainSection.Plugins -> Icons.Outlined.Extension
    // 与 ChatScreen 里模型入口同一个图标，用户在会话里见过它，认得出这页是干什么的。
    MainSection.Models -> Icons.Outlined.ModelTraining
    MainSection.Settings -> Icons.Outlined.Settings
}

private fun sectionEnabled(section: MainSection, scopes: List<String>): Boolean = when (section) {
    MainSection.Chat -> "chat.read" in scopes
    MainSection.Files -> "files.read" in scopes
    // 插件清单走 /settings/plugins，和模型供应商同一个 settings.read 权限。
    MainSection.Plugins -> "settings.read" in scopes
    // 模型页：**永远可进**，与设置页同一取舍。
    //
    // 写成 `"settings.read" in scopes` 看着更「正确」，实际是错的：没权限时
    // 抽屉里根本不显示这一项，用户只看到「没有模型页」，既不知道是权限问题、
    // 更不知道该去哪儿开 —— 而 ModelsScreen 里那条「需要 settings.read」的提示
    // 因此永远不可达。设置页之所以是 true，正是为了让这类「为什么不可用」看得见。
    MainSection.Models -> true
    MainSection.Settings -> true
}

@Composable
private fun localError(value: String): String = when {
    value == "FILE_TOO_LARGE" -> stringResource(R.string.file_too_large)
    value == "BINARY_FILE" -> stringResource(R.string.binary_file)
    value.startsWith("ETAG_MISMATCH") || value.startsWith("HTTP 412") -> stringResource(R.string.etag_conflict)
    value.startsWith("RATE_LIMITED") -> stringResource(R.string.rate_limited)
    value.startsWith("AGENT_PRESET_LOCKED") || value.startsWith("DSH_AGENT_PRESET_LOCKED") ->
        stringResource(R.string.agent_preset_locked)
    // 配置文本解析失败必须给一句人能看懂的话：此刻用户人已经到了外面，
    // 屏幕上只有一段粘贴进来的文本，一句 "CONFIG_INVALID" 帮不了他。
    value.startsWith("CONFIG_INVALID") -> stringResource(R.string.import_invalid)
    else -> value
}
