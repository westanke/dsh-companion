package io.github.westanke.dshcompanion.ui

/**
 * 手机端抽屉灰幕的两种状态：**画不画**、以及**吃不吃点击**。
 *
 * 这两件事必须分开看：灰幕在抽屉关闭动画期间还要继续画（不然会突兀闪一下），
 * 但绝不能再吃点击（这正是 #21 的坑）。
 */
internal data class DrawerScrimState(
    /** 这一层要不要出现在树上（可见）。 */
    val visible: Boolean,
    /** 这一层要不要吃掉落在它上面的点击。 */
    val catchesTaps: Boolean,
)

/**
 * 抽屉灰幕的**纯判据**：Compose 只负责画，判定只负责算。
 *
 * ## 为什么要有这个文件
 *
 * 2026-11-18 修一个真机 bug（#21）：会话详情页「当前任务」面板右端的折叠箭头、
 * 顶栏右上角的 ⋯ 菜单、以及各页面右上角的图标按钮，全都点不动 ——
 * 而点面板标题文字又能正常收起。
 *
 * 根因是抽屉灰幕那一层：它是 `fillMaxSize()` + `padding(start = 288dp)` 的一个 Box，
 * 浅色下 alpha = 0（官方就是全透明），旧写法是**永远在树上**，只靠
 * `clickable(enabled = drawerOpen)` 控制回调。但 **`clickable` 本身就是一次
 * pointerInput** —— 带 pointerInput 的容器**永远参与命中测试**，`enabled = false`
 * 只关掉回调，不关掉命中。于是这层「视觉上不存在」的透明层一直盖在右缘
 * 216px（真机 1080px 屏上的 x ≥ 864）上，把那条边的点击全吃了。
 *
 * 命中区被截断的位置正好是 **288dp = 864px**（真机二分点击实测：
 * x = 820 能点、x = 870 不能点），与 vivo 系统手势条的右缘 1008px 不符 ——
 * 这才排除了「系统手势条挡的」这一解释。
 *
 * ## 铁律
 *
 * **灰幕只有在抽屉真的开着的时候才吃点击。**
 *
 * 「视觉上透明」不等于「不吃点击」—— 这条区别就是本次的坑。
 * 旧注释「透明时它仍然不吃点击（没有 pointerInput 的容器不参与命中）」
 * 把这条规律套在了 `clickable` 上，是**错的**（clickable 就是 pointerInput），
 * 而且正是这句错注释把排查方向带偏了一轮。
 *
 * ## 为什么判据要单独成纯函数
 *
 * 这个 bug 的本质是「两个布尔条件的组合写错了地方」：
 * `drawerOpen`（用户意图）与 `drawerShift`（动画进度）各管一件事，
 * 混在一个 Modifier 链里就没法单测，也没法一眼看出哪条判据在起作用。
 * 这里把判据收成纯函数，单测直接钉住边界（见 `DrawerScrimHitTestTest`）。
 */
internal fun drawerScrimState(drawerOpen: Boolean, drawerShift: Float): DrawerScrimState {
    // 关抽屉时 `drawerOpen` 立刻变 false，但滑动动画还要跑 250ms ——
    // 动画期间灰幕必须继续画（不然暗色主题下会突兀地闪一下），
    // 所以「可见」看的是「开着 或者 还在滑」。
    val visible = drawerOpen || drawerShift > 0.01f
    // 但「吃点击」只看用户意图：抽屉一关，这层就必须把命中还给内容页。
    return DrawerScrimState(visible = visible, catchesTaps = drawerOpen)
}
