package io.github.hakunm.deepseekharness.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.R
import kotlinx.coroutines.launch

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
        else -> ConnectedApp(state, viewModel, snackbar)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedApp(
    state: HarnessState,
    viewModel: HarnessViewModel,
    snackbar: SnackbarHostState,
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp

        // WebView 聊天页开着时，**关掉抽屉的滑动手势**。
        //
        // Material3 的 ModalNavigationDrawer 默认允许从屏幕任意位置横向拖动打开，
        // 它和 WebView 的滚动在同一个手势通道上抢触摸：用户在会话里上下滑动时
        // 只要手指带一点横向分量，抽屉就被拉出来（会话/文件/插件/设置），
        // 而且滚动会中途被打断——手感就是「不流畅」。这两个症状是同一个根因。
        //
        // 关掉手势不影响正常使用：左上角汉堡按钮照样能开抽屉（见 navigationIcon）。
        // 注意这里必须放在 BoxWithConstraints 内部：wide 是在下一行才算出来的。
        val webChatOpen = !wide && section == MainSection.Chat &&
            state.selectedSessionId != null && state.useWebChat
        val content: @Composable () -> Unit = {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (!wide) {
                        TopAppBar(
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Outlined.Menu, stringResource(R.string.navigation))
                                }
                            },
                            title = {
                                Column {
                                    Text(
                                        stringResource(R.string.app_name),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(section.label),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            actions = {
                                if (state.busy) {
                                    CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = viewModel::refreshDashboard) {
                                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        )
                    }
                },
            ) { padding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    AnimatedVisibility(
                        visible = wide,
                        enter = fadeIn() + slideInHorizontally { -it / 3 },
                        exit = fadeOut() + slideOutHorizontally { -it / 3 },
                    ) {
                        NavigationPanel(
                            state = state,
                            selected = section,
                            onSelected = { section = it },
                            onRefresh = viewModel::refreshDashboard,
                            modifier = Modifier.width(264.dp).fillMaxHeight(),
                        )
                    }
                    if (wide) VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
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
            content()
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !webChatOpen,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        drawerShape = MaterialTheme.shapes.large,
                    ) {
                        NavigationPanel(
                            state = state,
                            selected = section,
                            onSelected = {
                                section = it
                                scope.launch { drawerState.close() }
                            },
                            onRefresh = viewModel::refreshDashboard,
                            modifier = Modifier.width(304.dp).fillMaxHeight(),
                        )
                    }
                },
                content = content,
            )
        }
    }
}

@Composable
private fun NavigationPanel(
    state: HarnessState,
    selected: MainSection,
    onSelected: (MainSection) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopes = state.device?.scopes.orEmpty()
    Column(
        modifier.padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    state.device?.name ?: stringResource(R.string.mobile_workspace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.busy) {
                if (state.busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
            }
        }
        Spacer(Modifier.size(18.dp))
        MainSection.entries.forEach { item ->
            if (!sectionEnabled(item, scopes)) return@forEach
            NavigationDrawerItem(
                label = { Text(stringResource(item.label)) },
                selected = selected == item,
                onClick = { onSelected(item) },
                icon = { Icon(sectionIcon(item), null) },
                shape = MaterialTheme.shapes.medium,
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedIconColor = MaterialTheme.colorScheme.secondary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                StatusLabel(
                    if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                    state.eventsConnected,
                )
                Text(
                    state.endpoint,
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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
