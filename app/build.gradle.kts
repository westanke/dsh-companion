import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名配置有三处候选，按顺序取第一个真实存在的：
//   ① `-PdshSigningProperties=<路径>`（CI / 临时指定）；
//   ② 本仓库内的 `.secrets/deepseek-harness-android/` —— **实测默认就该找到这个**。
//      早先这里只写 `../.secrets/...`（仓库外），而实际配置在**仓库内**，
//      于是那次出包必须显式传 -P 才过 verifyDedicatedReleaseSigning，白折腾一轮；
//   ③ 仓库外的 `.secrets/`（多项目共享签名时用，保留兼容）。
// 三处都在 `.gitignore` 内（规则 `*.jks` 与 `signing.properties`），不会误提交。
val signingFile = providers.gradleProperty("dshSigningProperties").orNull
    ?.let(::file)
    ?: sequenceOf(
        rootProject.file(".secrets/deepseek-harness-android/signing.properties"),
        rootProject.file("../.secrets/deepseek-harness-android/signing.properties"),
    ).firstOrNull { it.isFile }
val signingValues = Properties().apply {
    signingFile?.takeIf { it.isFile }?.inputStream()?.use(::load)
}
val hasDedicatedSigning = signingValues.isNotEmpty()

android {
    // v2.2.41（versionCode 10066）：**包名迁移** `io.github.hakunm.deepseekharness` → `io.github.westanke.dshcompanion`。
    //   ① 基于什么：华为应用市场驳回「应用与『DeepSeek』名称相似、未提供商标权属证明」；应用名同时改为「DSH伴侣」。
    //   ② 为什么连包名一起换：旧包名带上游作者 hakunm 的命名空间与 deepseekharness 字样，
    //      落在应用市场的人工审核视野里同样是混淆源；而 applicationId 一变就是**另一个 App**，
    //      老用户无法覆盖升级，必须先卸载（会清掉已保存的连接）。这是有意接受的代价，不是疏忽。
    //   ③ 干了什么：87 个 Kotlin 文件的 package/import 整体迁移（main 60 / test 26 / androidTest 1），
    //      源码目录由 io/github/hakunm/deepseekharness 搬到 io/github/westanke/dshcompanion；
    //      namespace 与 applicationId 同步；中文/英文 strings.xml 的 app_name 与 connect_title 同步。
    //   ④ 测什么：scripts/build.sh docsCheck + :app:testDebugUnitTest（用例数见 CHANGELOG-DEV）+ compileDebugKotlin 真执行。
    //   旧包名留在 git 历史里，需要追溯时看 tag v2.2.40 及更早。
    namespace = "io.github.westanke.dshcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.westanke.dshcompanion"
        minSdk = 26
        targetSdk = 36
        // 版本号必须高于上游（1.0.0 / 10000），否则无法覆盖安装，用户在手机上也无法区分装的是哪个。
        // 1.1.0 = 多地址连接层 + 配置导入；1.2.0 = 插件清单页；
        // 1.4.0 = 会话文件 + 发送行为可配置 + 发图片/文件 + 消息可复制 + 链接可点 + 会话图片可见；
        // 1.5.0 = 会话里的 dsh-ui 交互卡片（此前只有网页版能渲染，手机上退化成一坨 JSON）；
        // 1.6.0 = 会话产生的文件直接列在消息流末尾（此前只有顶栏一个图标按钮，功能在但入口藏得深）；
        // 1.7.0 = dsh-ui 卡片诊断开关：卡片没渲染时用户自己就能给出证据，不必等我们出新包；
        // 1.8.0 = 发送失败不再吞字（原文自动放回输入框）+ 失败原因说人话 + 图片取不到时说明真实原因；
        // 1.9.0 = 长按让给「选择片段」（此前被「复制整条」吃掉），整条复制改用双击；
        // 2.0.0 = 会话详情页新增 **WebView 实现**（assets/chat.html，自带 Markdown 与卡片渲染），
        //          与原生版并存、由设置里的「聊天页实现」开关切换 —— 原生版是走过真机验证的资产，
        //          所以默认仍走原生。联网请求全部留在原生侧，令牌不进页面；
        // 2.0.1 = 修 WebView 版「会话读取不出来」：原生侧把 web 与 pageReady 补进
        //          LaunchedEffect 的 key（首帧 AndroidView.factory 还没跑，effect 直接早退，
        //          之后 key 不变就永不再跑，init 压根发不出去）；
        //        同时页面加**诊断条**：init 收到 / 命令已载入 / 历史已载入 / 拉取失败 四个节点，
        //          底部黄条实时显示、点一下复制走。四条早退路径过去在界面上长得一模一样，
        //          只能靠猜 —— 猜了三轮全错，这次让页面自己说。
        // 2.0.2 = 删掉「没连上电脑就直接早退」那个静默分支 —— 它和 2.0.1 的 web/pageReady
        //          是同一个坑：早退让 init 一次都发不出去，用户永远看不到原因；
        //        init 改成握手：页面第一件事回调 pageReady，原生每 250ms 重发、收到回报才停，
        //          「发早了」与「压根没发」第一次变得可区分；页面 3 秒无诊断则自报家门。
        //        顺带修附件链四个 bug：appContext as? Activity 恒为 null 导致选择器打不开、
        //        连点附件并行轮询互抢 uri、发送后清理方向反了、indexOf 判字段依赖 JSON 顺序。
        // 2.0.3 = 不再靠猜，加**启动烙印**：脚本开头就把标题从静态文案「载入中...」改写掉，
        //          并挂 window.onerror 把异常直接印在屏幕上。
        //        判据：标题停在「载入中...」= 脚本一行没跑；停在「DSH」= 脚本跑完了、
        //        只是 init 没来；停在「JS出错」= 中途抛异常。这三种过去长得一模一样，
        //        而我为此连错两版。兜底自检也改成按标题分流，不再只查诊断条。
        // 本仓库相对上游的功能变更见 docs/project/CHANGELOG-DEV.md。
        // 2.0.4 = 诊断从页面内部挪到**原生 UI**。
        //          页面自证连着四版都没报出有效信息 —— 因为一旦页面真的一行脚本都没跑，
        //          页面自己就没有任何东西能报告了。这是观测手段和被观测对象绑定的死结。
        //          现在用 Compose 原生 Text 直接画在 WebView 上面，不经过 JavaScript：
        //          哪怕页面彻底死了，原生侧照样显示 onPageStarted / onPageFinished /
        //          加载失败码 / web / pageReady / initAck / 连接状态。
        //          同时记录 onPageStarted —— 之前只挂了 onPageFinished，
        //          「压根没开始加载」和「加载了但没结束」这两种情况长得一模一样。
        // 2.0.5 = 修诊断面板自身的死循环（SideEffect 每帧追加，把真正的事件行挤出窗口），
        //          并加一条**决定性探测**：原生执行一句 JS 问「window.dshChat 在不在」，
        //          通过第二个桥 Android.__probe 把答案读回原生显示。
        //          这一步能一刀切开两种情况：页面里没有 dshChat（脚本没执行）
        //          vs 有 dshChat 但 init 里抛了（脚本执行了但炸了）。
        //          2.0.4 的证据：pageReady=true 但 initAck=false 且标题仍是静态文案，
        //          即 onPageFinished 触发而页面 JS 零执行。
        // 2.0.6 = 修真机故障「页面停在『正在读取会话…』+ 探测报 dshChat 不存在」。
        //          根因：onRelease 销毁 WebView 时**没有重置 bridge.initAcked**。
        //          bridge 由 remember 创建，整个 Composable 生命周期共用一个实例，
        //          而 AndroidView 每次重建都换一份新的页面上下文；上一个实例留下的
        //          initAck=true 让重发逻辑在第一个 tick 就判定「已确认」直接返回，
        //          新页面永远收不到 init —— 症状正是探测报「dshChat 不存在」。
        //          onRelease 同步补上 removeJavascriptInterface("Android")。
        //          诊断同步加强：探测句从「在/不在」扩成一次读回 readyState / 页面节点数 /
        //          桥是否存在 / title / empty，把「没加载」「脚本没跑」「脚本炸了」一次分开；
        //          页面侧把 window.onerror 的原始错误钉在 window.__dshErr 供原生读回。
        //          另加 tools/chat-html-smoke.mjs：在 node 里跑一遍页面顶层脚本，
        //          断言 window.dshChat 已定义且原生调用的 6 个方法齐备 ——
        //          「整段 script 抛异常导致页面彻底死」过去只能靠真机截图才发现。
        // 2.0.7 = 探测脚本**实际内容**与一组全局符号是否存在。
        //          2.0.6 的数据已经把静态分析能查的都排除了：页面完整
        //          （ready=complete / nodes=39 / scripts=1 / scriptLen 与源文件一字不差），
        //          却一行 JS 都没执行，且没有 err= 字段说明 onerror 还没被赋值就抛了。
        //          现在直接问页面：脚本首 24 字符长什么样，
        //          以及 toast / note / App 这几个顶层符号到底存不存在。
        //          这几个是 function 声明与 var，脚本跑过一行就必然存在，
        //          比看标题可靠 —— 标题可能被别的东西覆盖。
        // 2.0.8 = 探测改成**一次问完 + 一键复制**。
        //          起因是流程问题：之前每次只问一两个点，用户靠截图回传，
        //          截屏必然裁掉信息，于是又回到猜。这次把六组检查项
        //          （页面状态 / WebView 环境 / 桥接注入 / 脚本本体 /
        //            全部顶层符号 / 自动结论）一次跑完，
        //          拼成多行文本存进原生侧，点面板一下全部进剪贴板。
        //          面板可滚动、高度封顶，避免关键行被挤出屏幕。
        // 2.0.9 = 分段插桩。2.0.8 的 51 行报告已经把静态分析的路走完：
        //          函数声明全部存在（说明脚本被完整解析、无语法错误），
        //          但 window.dshChat 不存在、标题仍是静态文案，
        //          而本地同一份文件能正常跑出 dshChat。
        //          执行恰好停在第 977 行的 window.dshChat = { 之前，
        //          每一行都验证过合法 —— 静态分析到此为止。
        //          于是沿脚本推进路径埋 5 个标记（started / REST / Markdown /
        //          消息渲染 / dshChat-assign），由原生探测读回，
        //          一次定位到停在哪一段。
        // 2.1.0 = 纠正一个读错过的判据，并把异常捕获提到脚本第一行。
        //        此前一直用 typeof window.onerror === "object" 当作「onerror 已赋值」的证据，
        //        但浏览器里window.onerror 默认是 null，而 typeof null === "object" ——
        //        这字段从头到尾没证明过任何事。更糟的是 onerror 排在第 26 行，
        //        它之前抛的异常全被静默吞掉，于是 __dshErr 恒为 null，
        //        看起来像「没抛异常」，其实可能抛过。现在：
        //        · onerror 装到第 1 行，之后不可能再漏；
        //        · 用 window.__err 记录原始错误（探针读 err= 字段）；
        //        · 用 window.__mk 记录执行停在哪一段（探针读 mk= 字段）。
        // 2.1.1 = WebView 聊天页终于通了（2.1.0 真机验证：脚本完整执行、init 送达、
        //          命令载入成功，只剩一个 GET 带body 的错）。
        //        真 bug：payload.toRequestBody(null) 给 GET 发了零长度 body，
        //          Node 端直接回「method GET must not have a request body.」。
        //          改为 GET/DELETE 一律 method(verb, null)，不构造 RequestBody。
        //        同时撤掉排障期加的原生诊断面板与页面插桩标记 —— 它们完成了使命，
        //          留着只会挡住聊天界面。
        // 2.1.2 = 修「只有工具调用、正文全空」。
        //   真机反馈：会话能加载了，但界面上全是「工具调用 / 工具结果」，一条正文都没有。
        //   根因：页面 pickText 只有 typeof d.message === "string" 一支，而真实历史里
        //   assistant/message 的形状是 data.message = { role, content:[{type,text}] }，
        //   是**对象**不是字符串，于是正文整个落空。
        //   拿真实会话日志离线核过：745 条助手消息，旧实现提取出 **0 条**。
        //   修法：递归下降一层处理 d.message.content，并处理 d.content 本身是数组的情况
        //   （user/message 就是）；同时给 contentText 一个 contentText() 抽段函数，
        //   只认 text 与 reasoning 段。
        //   顺带修两处：tool/call 正文在 d.arguments（字符串），早先统一调 pickText 取不到；
        //   以及正文为空的事件跳过渲染，否则会留下一串只有标题的空「DSH」气泡。
        //   离线复核：助手正文 0 → 571 条，跳过 185 条纯 tool-call 事件（本来就没正文）。
        // 2.1.3 = 修两块挡界面的：
        //   · 「本次产生的文件」与「当前任务」改成 <details>，默认折叠。
        //     原来两个区块常驻展开，文件一多就吃掉小半屏，聊天内容被顶到屏幕外。
        //   · 诊断条从 position:fixed;bottom:0 改为插在消息流末尾、输入区之上。
        //     原来它悬浮在屏幕底部，直接压住「发送」按钮，点都点不到。
        //   · toast 的 bottom 从 88px 抬到 104px，同样避开输入框。
        //   · 修「会话里上滑偶尔弹出侧边栏」：body 上的 overscroll-behavior:none 覆盖不到
        //     真正可滚的 #log，滑到内容边界继续拖会被系统当成侧滑返回；
        //     同时长路径 / 长代码行横向撑开也会触发。页面侧给每个滚动容器单独声明
        //     overscroll-behavior:contain 并禁止横向溢出，原生侧关掉横向滚动条与边缘辉光。
        // 2.2.0 = 消息左右分栏（微信式），并修「发完消息 init 重发三次」。
        //   · 布局：用户气泡靠右、蓝底白字、限宽 78%；助手消息靠左、不限量宽。
        //     助手内容常有表格 / 代码块 / 卡片，塞进窄气泡反而更难读。
        //   · 重发：init 那条 effect 的 key 里曾有 session?.running，
        //     于是每发一条消息 → 会话进入运行态 → key 变 → 整条 effect 重跑
        //     → 再发一次 init → 页面把命令/历史/审批重新拉一遍。
        //     running 改由一条独立 effect 推给页面的 setRunning。
        //   · 顺带撤掉排障期留在正式包里的 60 项页面探测代码。
        // 2.2.0 = 合并包：一次装到位，含 2.1.4 的三个界面修复 + 左右分栏 + init 重发修复。
        //   1. 消息左右分栏（微信式）：用户靠右蓝底白字限宽 78%，助手靠左不限量宽 ——
        //      助手内容常有表格/代码块/卡片，塞窄了反而更难读。
        //   2. 「本次产生的文件」「当前任务」改 <details> 默认折叠，原来常驻展开会吃掉小半屏。
        //   3. 诊断条从 fixed 悬浮改为插在消息流末尾，原来它压住「发送」按钮。
        //   4. 修「发完消息 init 重发三次」：init 那条 effect 的 key 里曾有 session?.running，
        //      每发一条消息 → 会话进入运行态 → key 变 → 重发 init → 命令/历史/审批重拉一遍。
        //      running 改由独立 effect 推给页面的 setRunning。
        //   5. 修「上滑偶尔弹出侧边栏」：overscroll-behavior 之前只写在 body 上、盖不到真正
        //      可滚的 #log，滑到边界继续拖被系统当成侧滑返回；另加长路径横向溢出治理。
        //   6. 撤掉排障期留在正式包里的 60 项页面探测代码与插桩标记。
        // 2.2.1 = 修 2.2.0 实测反馈的三个问题。
        //   ① 上滑仍偶尔弹出侧边栏（会话/文件/插件/设置）：
        //      那是**原生**的 ModalNavigationDrawer，它默认允许从任意位置横向拖动打开，
        //      与 WebView 的滚动抢同一个手势通道。WebView 聊天页开着时把它关掉
        //      （gesturesEnabled = false），汉堡按钮照常可用。
        //      同一个根因也解释了「会话里上下滑动不流畅」——滚动中途被抽屉抢走。
        //   ② dsh-ui 卡片显示有问题：原先是原生抽出卡片再集中推给页面塞在消息流**末尾**，
        //      而原文里的围栏仍当代码块渲染 —— 同一张卡片显示两次（底部的卡片 + 原位的灰 JSON）。
        //      改为页面按同一套判据就地切开、原位渲染（chat.html 的 splitUi）。
        //   ③ 渲染器的字段名读错：table 要 columns+rows(数组的数组) 而不是对象数组；
        //      callout 正文是 content，原先读 text/message 全是空；keyvalue 压根没处理。
        //      另补齐 badge/progress/steps/timeline/diff/divider 等原生支持的类型。
        // 2.2.2 = 输入区三个问题。
        //   ① 键盘弹起挡住输入框：MainActivity 调了 enableEdgeToEdge()，而 targetSdk 是 36 ——
        //      从 Android 15 起边到边模式下清单里的 adjustResize 会被系统忽略，窗口不再随键盘
        //      缩小，WebView 铺到键盘底下。改为在 Compose 侧用 Modifier.imePadding() 自己减掉
        //      IME 高度，WebView 跟着变小，页面 flex 布局自然把输入区留在可见范围内。
        //   ② + 与发送按钮移进输入框、变小：原来三个独立控件横排（圆形 + / 圆角输入框 /
        //      圆形发送键），输入框被夹在中间既没占满宽度、圆角也对不齐。现在是一整条药丸。
        //   ③ 禁用态不再整体压透明度（会连底色一起淡掉），改成透明底 + 灰字，位置不跳。
        // 2.2.3 = 修 dsh-ui 卡片内容全空（真机 2.2.2 实测：结构出来了、三个空框）。
        //   根因：节点有两种形状，页面只认了一种。
        //     ① 原生包装过的 {type, value:{...}}；
        //     ② **原始 spec** {type, ...字段} —— 字段与 type 同级，真实卡片就是这个形状。
        //   页面写的是 `var v = node.value || {}`，遇到形状 ② 得到空对象，
        //   于是 callout/table 全部渲染成空框。这次直接从会话日志里取出真实卡片 JSON 定案，
        //   不再按猜测写字段名。
        //   另修两处同一个数据暴露的问题：
        //     · badge 的真实字段是 variant（不是 tone），并补 ✓/✗ 前缀；
        //     · rows 里混着一个非数组的组件对象（结尾的 callout），改按整行组件渲染；
        //       表格单元格里嵌的 badge 对象也要走组件分派，否则显示 [object Object]。
        //   离线用该真实 spec 验证：9 项断言全过。
        //   ② 修「思考过程被当正文灌进聊天气泡」（真机截图里那一大段英文内部推理）。
        //      修正文全空时把 reasoning 与 text 一起取了，两者混在一起。
        //      改为 partsText(c, want) 按段类型各取各的：reasoning 进「思考过程」折叠块，
        //      只有 text 进正文 —— 与原生 ChatPresentation 的做法一致。
        //      离线核过两条真实会话：912 条助手消息，276 条含思考、516 条含正文、0 泄漏。
        //   ③ 工具调用/结果整组折叠（用户反馈「一堆工具调用、工具结果，太乱」）。
        //      早先每次调用、每个结果各占一个折叠块 —— 实测那份会话有 1775 个块。
        //      现在按 callId 把调用与结果配成一条，并把连续的工具事件合成一组：
        //      组头只报次数与工具名摘要，组内每条一行（工具名 + 参数预览 + 成败），
        //      点开单条才看完整参数与结果。1775 块 → 668 组。
        //      关键取舍：断组判据从「任何非工具事件」改为「**只有会渲染出可见内容**才断」，
        //      否则 step/start、step/end 与只含 tool-call 的 assistant/message 会把组冲断
        //      （第一版就是这么写的，实测只降 2%，等于白干）。
        //   ④ 修「+ 号弹层只有关闭/应用有反应」（用户实测）。
        //      根因：里面两个选项按钮压根没绑事件，只有底部「应用」能触发，
        //      而它永远取第一个选项（data-a="image"）—— 所以点「选择文件」毫无反应，
        //      点「应用」也只是去开图片选择器，且华为 ROM 上落到「文件/下载」界面。
        //      改法：sheet() 的 onOk 传 null 时不渲染「应用」，选项自己就是动作、点完即关；
        //      原生 pickFile 识别 image/* 时改用 ACTION_PICK（相册），
        //      其余仍走 ACTION_OPEN_DOCUMENT（文件浏览器）—— 让按钮文案与实际界面一致。
        // 2.2.4 = 修卡片内表格列重叠（真机 2.2.3 实测：表头挤成「问题法」，两列叠在一团）。
        //   根因：表格样式里写了 `.card table{display:block;overflow-x:auto}` ——
        //   用 display:block 换横向滚动是常见写法，但它让 table **脱离表格布局算法**，
        //   列宽算不出来，几列就叠在同一位置。
        //   正确做法：滚动容器套在**外面**（.tw），table 自己保持 display:table。
        //   两处生成表格的地方（table 组件、chart 兜底）都补了外层。
        // 2.2.5 = 修「进会话就看见一堆自己没发过的东西」（真机反馈，很显眼）。
        //   user/message 这个事件类型里混着大量**系统注入**：运行上下文、时间上下文、
        //   后台任务通知、子代理完成、模型选择、技能清单、上下文压缩点、插件上下文…
        //   只有 source.kind === "user" 才是用户真的发出去的。
        //   早先不判断一律渲染成蓝色用户气泡 —— 那份真实会话里 200 条 user/message，
        //   只有 57 条是用户发的，其余 143 条全是注入。
        //   现在与原生 ChatPresentation.userItems 的判据一致：非 user 的收进折叠块，
        //   并用 contextLabel 按 source.kind 给出人能读的名头（运行上下文 / 时间上下文…）。
        //   实时路径的上下文块也改用原生已取好的 title。
        //   离线核过：57 条用户气泡 + 144 条带名头折叠块。
        // 2.2.6 = 修「会话进行中堆出一长串『思考过程』，重新进会话就正常」。
        //   根因：setLive 每帧都 host.innerHTML = "" 以为清空了，
        //   但 addAct / addMsg / addToolGroup 全是往 **#log** 追加的 ——
        //   那个清理清的是一个空壳，每次 revision 变化就把当前内容再往聊天区追加一份。
        //   流式期间 revision 每秒变好几次，于是思考块越堆越多；
        //   重新进会话时 renderHistory 会 log.innerHTML = ""，所以看着又正常了。
        //   修法：引入渲染落点 sink() —— null 时落 #log，流式期间指向 #liveHost，
        //   7 处渲染函数改用它；setLive 进入前设、出来复位，renderHistory 也复位。
        //   同时把连续的思考合并成一块（一个回合每换一个 step 原生就重置一次块，
        //   不合并就是一串），并让思考落在它所属的正文**之前**。
        // 2.2.7 = 修卡片表格列被压窄、文字压到隔壁列（真机 2.2.6 截图）。
        //   根因：上版把 table 的宽度写成了**硬约束** width:100%，而单元格是 nowrap；
        //   内容比容器宽时列被强行压窄，文字只能溢出格子压到右边一列
        //   （「Office 文档（officecli）」被 ✓ 徽标盖住）。
        //   改成 min-width:100% —— 内容窄时铺满，内容宽时表格撑开、由外层 .tw 横向滚。
        //   另给单元格内的徽标加了 2px 左边距。
        //   ③ 流式思考改为**一行**（与官方观感一致）：运行期间只显示「当前思考」，
        //      一行、右侧省略号截断、内容滚动更新，不再往聊天区落折叠块；
        //      完整思考内容等这一轮结束后由「思考过程」折叠块承载。
        //   ④ 撤掉会话底部的诊断黄条 #diag —— 它是排障期看「init 有没有到」用的，
        //      问题早已解决，留着只是多一条黄条。note() 仍写 console.log，
        //      需要线索时按 DshChatWebView 从 logcat 捞。
        // 2.2.8 = 修「会话进行中正文与工具块在同一位置不停闪」。
        //   根因：setLive 每帧先 host.innerHTML = "" 再全量重建，
        //   正文气泡与工具块都被拆掉重建一次 —— 用户看到的就是「在同一位置不停地换」。
        //   修法：**按块就地更新**。先把这次要显示的块排成 descs（每块带稳定 key：
        //   思考合成一行 key=__think、工具合成一组 key=__tools、其余用原生给的 id），
        //   再逐位比对 host.children：同 key 同类型只换内容、不换节点；
        //   变了才替换那一个；块数变少才删多余的。
        //   配套把「建」与「填」拆开：fillAssistantBubble（正文）、
        //   setThinkingInto（思考行）、refillToolGroup（工具组内部列表）。
        //   离线用真实 reconcile 代码段验证：第二帧新建节点 0 个、节点身份不变。
        // 2.2.9 = 修 2.2.7 引入的白屏（真机报「Script error. @0」）。
        //   根因：2.2.7 撤掉 #diag 元素时**漏删了它的顶层 onclick 绑定**。
        //   那行是脚本加载时执行的：$("diag") 返回 null，null.onclick 抛 TypeError，
        //   整个脚本当场中断 —— window.dshChat 都没定义，页面全白。
        //   而 file:// 页面把错误详情屏蔽成「Script error. @0」（每个本地文件都是
        //   独立不透明源），真机上几乎无从排查。
        //   修法：① 删掉失效绑定；② 顶层绑定一律改用 bind() —— 拿不到元素时代入
        //   一个游离节点接住赋值，不让一处失误把整个脚本带死；
        //   ③ 删掉引用了已不存在元素的 paintDiag 死代码（note 只写 console）；
        //   ④ 新增 tools/chat-html-check.mjs：体检「引用的 id 是否都存在」
        //      「顶层绑定是否都用 bind()」「接口是否齐全」，改完页面先跑它。
        // 2.2.10 = 两处按真机反馈修正。
        //   ① 思考条改位置：从消息流里挪到**消息区下方、输入区上方**那条
        //      （原来挂诊断黄条的地方），有思考就显示一行、没有自动隐藏。
        //      它是运行状态而不是会话内容，插在消息流里会随消息滚动、把内容挤开。
        //   ② 流式期间切掉**未闭合的 dsh-ui 围栏**：围栏闭合前它只是普通文本，
        //      会先以原始 JSON 代码块的形态显示一次，闭合后再变卡片 ——
        //      用户看到的就是「这里闪了一下」。节点复用治不了，因为内容换了形态。
        //      与定稿渲染的取舍相反：那边未闭合整段保留（不丢内容），
        //      这边是中间态，藏起来比露出半截 JSON 好看。
        // 2.2.11 = 修「表格显示成原始竖线」（真机截图证据：| 项 | 之前 | 现在 | 原样显示）。
        //   根因：md() 里**压根没有表格支持** —— 代码围栏/行内代码/链接/引用/标题/
        //   列表/粗斜体/删除线都有，就是没有表格。我回复里大量用表，手机端于是
        //   整段显示成竖线，而网页版是正常表格。上一版猜的「围栏闪」不是主因。
        //   修法：md() 增强格解析 —— 整块抽出占位（必须在 \n→<br> 之前，
        //   否则表格行会被 <br> 拆散），最后统一生成 <table>，外面套 .tw 横向滚。
        //   流式期间另加 stripOpenTable：分隔行没到之前先不显示，
        //   避免「原始竖线 → 表格」跳一下（与未闭合围栏同一取舍）。
        //   ⑤ 运行中显示工具活动（用户对比电脑端："这是电脑上，中间差这多内容"）。
        //      实时通道 chat.message.delta 只承载正文与思考；工具事件被插件归一化成
        //      chat.session.event，只带 {sessionId, eventType, seq}，连工具名都没有 ——
        //      于是整轮运行期间页面一条工具活动都看不到，而网页版边跑边显示。
        //      好在工具事件一到就会重载历史（chat.* 兜底分支），完整数据已在 state.history。
        //      ChatWebScreen 据此把「本轮已发生的工具」抽出来经 setLiveTools 推给页面，
        //      页面渲染成一个工具组。触发键用历史事件数。
        // 2.2.12 = 修 2.2.11 引入的表格显示崩坏（真机：单元格漏出 @@IN0@@、「环节」竖着一字一行）。
        //   两个错都在我：
        //   ① 占位符展开顺序反了 —— @@TB（表格）排在 @@IN（行内代码）**之后**，
        //      表格 HTML 插进去时行内代码那步早跑完，单元格里的占位符原样漏到屏幕。
        //      改成表格最先展开，再让 @@CB/@@IN 扫过整段（含刚插进去的单元格）。
        //   ② `.md table` 里写着 display:block —— 这与 2.2.4 在 `.card table` 上
        //      修掉的是**同一个错误**，当时只修了卡片那处、漏了这里。
        //      table 变成 block 就脱离表格布局算法，列宽算不出来，压成一字一列。
        //      改成滚动容器 .tw 套外面、table 保持 display:table。
        //   体检工具新增「表格没有被写成 display:block」，两处一起收口。
        //   ③ 修「一直在同一个位置说这一段，就把上一段没了」。
        //      历史与实时区是分工的：历史=已提交，实时区=在飞的那一段。
        //      而 ChatWebScreen 里推历史的 effect 写着 `if (liveSession == null) return` ——
        //      liveSession 正是它的 key，liveChat 被置空那一刻正好是 null，
        //      于是**恰好在最需要推送历史的一瞬提前返回**，历史永远推不出去；
        //      叠加 LiveChatState「换 step 就重置、旧内容交给 REST 历史补回来」的设计，
        //      重置掉的部分没有任何东西补，整轮就只剩当前这一段。
        //      修法：原生运行中也照推历史；页面 renderHistory 重画时把 #liveHost
        //      摘下来、画完挂回末尾（并放在「空会话」判断之前）。
        //      注：跨 step 累积的改法已试验后撤回 —— 会与历史重复，
        //      且 LiveChatGroupingTest 明确把「重置」钉为刻意行为。
        //   ④ 历史会话不再显示「思考过程」（用户：没人看的）。
        //      实时那条思考行（#thinkbar）保留 —— 那是运行状态，与历史不是一回事。
        //   ⑤ 工具显示改用网页版那种动作摘要：「运行了代码，已写入文件，执行了命令」。
        //      做法照着一字不差还原：run_code 的 arguments 里能看到它调了哪些内层工具。
        //      真实数据核过（1129 次 run_code：bash 748 / edit 158 / read 116 / write 53，
        //      其中 bash+write 42 例正对应上面那句）。离线验证三句措辞全部重现。
        //      原生实时推送也改为送原始工具名 + 原始参数，否则流式期拼不出内层动作。
        // 2.2.13 = 工具组表头不再报「工具调用 N 次」。
        //   用户：「显示了 运行了代码，修改了文件 等，就没必要显示 工具调用 x 次 了」。
        //   动作摘要已经把「做了什么」说清楚，次数是给日志看的；
        //   顺带去掉 .tnames 的 margin-left（前面没有元素了）。
        // 2.2.14 = 取消实时区，全部内容只走历史一条路（用户原话：
        //   「发出来的就当做历史对待，别自己替换了」）。
        //   页面 setLive 由 118 行缩到 20 行，只保留「把思考喂给底部那条思考行」；
        //   正文/工具/上下文全部由 renderHistory 渲染，不再有第二套渲染路径。
        //   配套：原生在收到 chat.message.delta 时也 scheduleChatRefresh()
        //   （250ms 去抖 + 单飞），这样流式文字不必等消息落盘就能进历史。
        //   此前实时区换 step 就重置、而历史又不推，两者叠加正是
        //   「这一段说完上一段没了」的成因；双轨制取消后这一类问题整体消失。
        // 2.2.15 = 把 WebView 页的呈现方式移植到原生（用户要求，除「本次产生的文件」外全部）。
        //   ① 工具显示改官方口径的动作短语（不再是 Bash / Read / Code 这种英文类别词）：
        //      ChatPresentation 新增 toolActionsFor()，判据与页面同源 ——
        //      run_code 扫 arguments 里的内层工具（tools.bash/write/edit/read…）。
        //   ② 连续的工具调用**合并成一行**（mergeToolRuns），标题报动作摘要，
        //      多条时展开可看每次调用 —— 原生原先是一次调用一行，跑几十次就是一屏。
        //   ③ 历史不再显示「思考过程」（REASONING），流式那一段保留。
        //   ④ 上下文注入默认隐藏，跟设置页的 showContext 开关联动。
        //   遗留项与「原生本已具备」的取证记在 docs/project/webview-port-todo.md。
        //   （原写作 webview-parity-todo.md，文件名写错了，由 genui-table 复核指出。）
        // 2.2.16 = 2.2.15 之后的三项并行改动合入：
        //   ① 思考条挪到消息区下方、输入区上方（ChatScreen 的 ChatThinkBar）；
        //      流式 REASONING 不再进消息流，无思考时整条不渲染（不占高度）。
        //   ② 卡片内表格：列宽由写死的 140dp 改为 max(96dp, 可用宽度/列数) ——
        //      装得下铺满、装不下横向滚；另修表头分割线在无界约束下量成 0 宽的问题。
        //      注：横向滚动本来就有（v1.9.0 f6b359f 起），真正没对齐的是列宽。
        //   ③ 文档：新增 docs/project/webview-port-todo.md（移植清单与未核实项）。
        // 2.2.17 = 输入区照 WebView 页摆法重做（用户选定方案 A：完全照页面）。
        //   药丸压成**一行**：`+` · 输入框 · 发送（边框与圆角由药丸统一画，输入框自己无边框）。
        //   模型 / 智能体 / 权限模式 / 插话全部搬进顶栏新增的 `⋯` 菜单；
        //   附件入口由两个平铺 IconButton 合成 `+` 弹出的两项；
        //   运行中提示由独立一行改为占位符（照 chat.html 的做法）。
        //   功能零退化：权限三档 + 完整访问二次确认、插话、附件、Enter/软键盘发送键全部保留。
        //   另补 values-en 缺的 show_context_title/desc（先前只加了中文，英文漏了）。
        // 2.2.18 = 【未单独出包，内容已并入 2.2.19】
        //   输入框高度改为随内容自适应（真机反馈：写死 34dp，第二行文字被裁）。
        //   网页版 #ta 是 min-height:36px / max-height:120px，现在对齐成 44~120dp；
        //   行内对齐改 Alignment.Bottom（网页版是 align-items:flex-end）。
        //   要点：BasicTextField 用 fillMaxWidth 而不是 fillMaxSize —— 后者会撑满
        //   heightIn 的上限，变成「永远 120dp 高」。
        //   标「未单独出包」是因为这里升过号却没打包，2.2.19 的注释又把它列为①；
        //   不注明的话读变更史会以为 2.2.18 是个已发布的版本。
        // 2.2.19 = 自 2.2.17 之后累积六项改动，统一出包（用户：「等这次修复完成统一打包」）。
        //   ① 输入框高度**随内容自适应**（44~120dp）。此前写死 34dp，敲到第二行就被裁。
        //      对齐网页版 #ta 的 min-height:36px / max-height:120px，行内对齐改 Bottom。
        //   ② 设置页三个开关统一走 SwitchRow。此前三处各写各的 Row：一个
        //      padding(bottom=4dp)、一个 padding(vertical=8dp)，页面上就是「中间那个
        //      开关上下多出一截」；其中一处还多带一句没作用的 Arrangement.SpaceBetween。
        //   ③ **修「点开关不变化，必须再点别的才跳」**：useWebChat/showContext 此前由 UI
        //      直接读 SharedPreferences —— 那不是 Compose 状态，读不建立订阅、写不触发重组。
        //      现在两个开关进 HarnessState，setter 调 update{copy}，UI 读 state。
        //      顺带修：HarnessState 在初始/disconnect/loadSnapshot 三处被整份重建，
        //      全都没带偏好字段 —— genUiDebug 从来没被恢复过、busySendMode 每次断开
        //      都被打回默认。新增 withPreferences() 统一盖上。
        //   ④ 模型配置从设置页拆成独立导航页（ModelsScreen.kt，Plugins 与 Settings 之间）；
        //      设置页 697 → 297 行并删净 30 个死 import。模型页永远可进（与设置页同取舍：
        //      看不到入口就不知道是权限问题、也不知道去哪儿开）。
        //   ⑤ 交付物改读内核的 `deliverables/presented` 事件（present 工具触发，带绝对路径
        //      + 人写的说明）。此前 displayItems() 只认三种事件，这一类被整类丢掉 ——
        //      用户在 App 里看不到 agent 交付的报告与安装包。旧的「嗅工具参数」保留为兜底。
        //   ⑥ 启动永远先落连接页 + 连接页两种方式默认收起：
        //      进程级门闩 connectGate（刻意不进 HarnessState，否则会被整份重建冲掉），
        //      只由用户的三个主动动作放行；冷启动自动重连成功**仍然停在连接页**。
        //      导入配置文本与手动配对都改成默认收起的入口行（共用 ConnectionEntryRow），
        //      互斥展开。另修 connectHostInternal 失败分支漏置 restoring=false
        //      （自动重连失败会让转圈永远停住）。
        // 2.2.20 = 修「进入应用」按钮灰着点不动（真机截图：已连接 + 按钮禁用 = 死路）。
        //   根因：按钮的启用条件是 `!busy && !connecting` —— 那是为「连接这台电脑」
        //   准备的（要并发探活、自动选路，忙时确实该禁）。但「进入应用」是**纯本地跳转**，
        //   不碰网络；自动重连成功后只要后台还有一次请求在跑（loadSnapshot、会话刷新
        //   都算 busy），按钮就灰着，用户既进不去、也没有第二个入口。
        //   现在按 isActive 分开判：已连上那台只看「有没有地址」。
        //
        //   另修：**同一个会话的文件清单两处不一致**。会话底部那块已改成读
        //   deliverables/presented，而标题栏文件夹弹层（SessionFileList）还在
        //   SessionFileRefs.refs() 嗅工具参数 —— 底部是 agent 交付的报告与安装包，
        //   弹层却是一堆嗅出来的路径（真机反馈：「标题栏的文件夹中的不合适」）。
        //   抽成共用的 sessionFileList()，两处同一个来源，不可能再走偏。
        // 2.2.21 = 补齐三项与「真机体感」直接相关的能力（本轮）。
        //   ① **修 /技能名 调用**：此前只要输入以 `/` 开头就整条送去 executeCommand，
        //      技能名不在宿主命令表里 → COMMAND_NOT_FOUND，**官方能用的技能一个都调不动**。
        //      现按「是否命中已注册命令」判别（isHostCommandLine）；不是命令就当普通消息
        //      原样发给模型，由 dsh-tool-skill 在 pre-step 注入技能正文 —— 与官方一致。
        //      匹配规则照抄插件 chat-adapter.ts 的正则，两边必须相同。
        //   ② **图片与 PDF 预览**：此前文件页只显示一行「二进制文件」，会话附件同理。
        //      现加 PreviewKind.IMAGE / PDF，**零新依赖**（系统 BitmapFactory + PdfRenderer）。
        //      文件页与会话附件两处共用同一组件与同一套判据，避免「一处能看一处不能」。
        //      加上限：图片 24MiB/12MP，PDF 32MiB/100 页，超限给出路而不是白屏。
        //   ③ **工具调用详情（trajectory）**：官方有、我们没有。手机上开不了终端，
        //      工具的输入/输出/成败/耗时是唯一的事后排查手段。现在卡片可展开看全部，
        //      失败的带标记；连续工具合并成一行后仍能逐条展开。
        //   另含 ask_user_question 问答卡片（原生 + WebView 两套，见 CHANGELOG）。
        // 2.2.22 = 修「问答卡片刷不出来」：改用事件驱动，**不用轮询**。
        //   之前只在 chat.message.committed / turn.* / approval.* 这几类事件上刷新，
        //   而提问超时的那一刻内核发的是 tool/result —— 它被插件降级成 chat.session.event，
        //   不在集合里，于是**信号已经到了 App 却没被认领**。
        //   现在：① 认领 tool/result 帧（isToolResultFrame，按 data.eventType 精确判）；
        //     ② 兜底：chat.turn.end 且该会话已不在运行时补拉一次，覆盖「agent 很快收尾、
        //        整轮都没接住」的情况。两条都是事件驱动，全程无定时器。
        // 2.2.23 = App 认领插件新加的**问答专属帧**，两端都能答且答完自动收场。
        //   · `chat.userQuestions/requested` → 立刻刷新（提问一落地就有通知，
        //     不必再等 tool/result 帧或轮次结束这两个间接信号）。
        //   · `chat.userQuestions/answered` / `dismissed` → **就地摘掉那张卡**，
        //     电脑端答完后手机端不必等下一次 GET 才消失。
        //   · `PendingQuestionSet` 新增 `viaBridge`（默认 false，兼容老服务端）。
        // 2.2.24 = 接住 `chat.turn.failed`：**模型失败不再让客户端傻等**。
        //   此前限流(429)/配额/密钥失效这些终局失败完全到不了手机 —— 插件把
        //   turn/end 的 reason 整段丢掉，客户端收到的与「正常跑完」一模一样，
        //   于是转圈等到天荒地老（用户原话：「要不然客户端一直在傻傻的等」）。
        //   插件现为失败多发一帧 chat.turn.failed，本版三件事：
        //   ① 事件层：`markSessionStopped()` **本地立即**把会话置为不在运行
        //      （服务端标记与该帧到达之间有几百毫秒空档，期间停止按钮还亮着）；
        //      同时 notify() 弹出服务端原话、清流式缓冲、拉历史、撤挂起的提问卡。
        //   ② 数据层：新增 `ChatItemKind.FAILURE` + `ChatTurnFailure`；
        //      实时帧 `chatTurnFailure()` 与历史帧 `turnEndItem()` **两条路都渲染** ——
        //      失败后用户往往先退出再进会话，实时帧只到一次，历史是唯一能留住它的地方。
        //   ③ 渲染层：原生 `TurnFailureRow`（红标题 + 错误码 + 原话，**不折叠**，
        //      长按复制整条好把 request id 带去排查）；WebView 页同步做。
        //   三个刻意取舍：不翻译 message（本地化按官方 failureMessage() 划分）；
        //   max-tokens 不算失败（那是「回答被截断」，官方渲染成黄色警示）；
        //   登出停止单独标 stopped、标题用「任务已停止」。
        //   注意 429 的 code 是**空串**，客户端按空串保留而非当缺失。
        // 2.2.25 = 纯文本模型发的图，手机端也**看得见**（用户原话：
        //   「看，人类如何看懂，把图片显示出来就好了，给一堆路径」，并指路
        //   「你已接入了官方的图片显示方法，只需要把那个图片的路径读出来」）。
        //   上一版解决的是「机器能不能读到图」：插件把图落进工作区 `.dsh-images/`，
        //   消息里追加两行说明（人话 + `[Image: source: /绝对路径]`）。
        //   本版补人这一半，全在客户端：
        //   ① 展示层只对**用户消息**解析那两行：按行摘掉说明，换成一个可显示附件
        //      （注入的上下文正文里也常引用路径，一个字都不动）；
        //   ② 取字节走官方那条现成通路 `resolvePath` → `readFile(rootId, path)`，
        //      与文件页预览同一个端点、同一份 `files.read` 授权，没为图片另开口子；
        //   ③ 渲染复用现有附件管线（缩略图 + 失败原因），新增一条
        //      「图片不在已授权的目录里」的中英文案。
        // 2.2.27 = 真机测试反馈的七个问题一次收口（纯客户端 + 插件各半）：
        //   ① 加载更早消息无反应 → 插件 `history()` 补 `beforeSeq` 分页分支：
        //      先用 follow 开场帧的 cursor 当 throughSeq，再调内核 `page()` 取更早的
        //      记录（此前无论传不传 beforeSeq 都只返回开场那一窗，点按钮像没反应）；
        //   ② 会话页返回键/左滑直接退出 APP → 原生 `ChatDetail` 补 `BackHandler`
        //      （WebView 版早就有，原生这条漏了）：首次返回退到会话列表，再按才退出；
        //   ③ 思考模式切换受限 → 切模型时不再回退到 `current.reasoningEffort`：
        //      目标模型不支持推理时传 null，支持时用它的 defaultEffort。
        //      此前从 high 档会话切到不支持 high 的模型会 MODEL_UNAVAILABLE；
        //   ④ 翻历史时被自动滚到底 → 加 `isAtBottom` 判定，只在底部附近才自动跟随；
        //      不在底部时右下角出现浮动按钮，点一下回到底部；
        //   ⑤ 输入法回车显示「换行」却触发发送 → 普通回车一律放行换行（中文输入法
        //      的软键盘回车也会派发 Key.Enter，不能只当物理键盘处理），
        //      发送只走「发送」action 键与发送按钮，物理键盘保留 Ctrl/Meta+Enter；
        //   ⑥ 中文界面启动报错显示英文 → 网络层 IOException 家族按异常类型本地化
        //      （超时/找不到主机/连接被拒/连接中断 + 连不上电脑），中英双份文案；
        //   ⑦ 设置页权限药丸露协议串 → 按界面语言显示（读取聊天/发送消息/写入文件…），
        //      认不出的新 scope 仍保留原文。
        // 另修一处既有测试不同步：ChatPresentationTest 还在断言 `error.title`，
        //   代码早已改为 `titleRes` 资源 id（stash 验证过，与本轮改动无关）。
        // 2.2.28 = 补上一个空头支票：会话里写着「长按可选中复制片段」，
        //   实际上整条消息的渲染路径里**根本没有 SelectionContainer**，
        //   长按什么也不弹（用户原话：「双击不出来选择项」）。
        //   根因是 2.2.x 早期为了避开「combinedClickable 吃掉长按导致选择手柄
        //   弹不出来」那件事，把长按整个让了出去 —— 却忘了**还得有人接**：
        //   双击复制整条实现了，选择片段从来没实现，提示文案却两头都写了。
        //   修法：`ChatItem` 内部包一层 `SelectionContainer`。
        //     · 外层 `combinedClickable` **只声明 onClick/onDoubleClick、不声明 onLongClick**，
        //       所以它不消费长按 —— 长按穿透到内层弹选择手柄，双击仍由外层复制整条；
        //       **千万别给外层补 onLongClick**，那正是早期翻车的原因。
        //     · 包在 Box 内层而不是给每个 Text 单独包：助手正文是第三方 Markdown
        //       组件渲染的（com.mikepenz.markdown.m3），内部有几个 Text 由它决定，
        //       外层包一次就覆盖全部段落、代码块与列表项。
        // 2.2.29 = 修耗电 + 修跳转 + 两处界面优化（用户真机反馈）：
        //   ① **耗电主因**：`scheduleChatRefresh()` 原是 `delay(1000)` 后回 while
        //      判条件，而**每个事件帧都把 chatRefreshPending 置 true**（事件分发
        //      末尾那个 `else ->` 兜底分支什么都命中）。于是模型流式输出期间每秒
        //      十几帧，循环一直转 —— 实际是**整个回答期间每秒拉一次全量 history**，
        //      几百条消息反复下载。改成 **400ms 静默去抖**：事件不断就重置窗口、
        //      任务不起跑；等这一轮安静下来才拉那唯一一次。实时流式显示不受影响
        //      （chat.message.delta 走 liveChat 独立渲染，token 级推送）。
        //   ② 浮动「跳到最新」按钮原本只跳到**当页尾部**：`displayItems.lastIndex`
        //      漏算了「复制手势提示」「加载更早消息」「本次产生的文件」三类固定
        //      条目，看着到底了其实还差一截。改用
        //      `listState.layoutInfo.totalItemsCount - 1`（LazyColumn 权威计数）。
        //   ③ 连带修 `isAtBottom` 判定：原来手算 `displayItems.size + 2`，固定条目
        //      增减时会误判「已经到底」，让自动跟随突然开火把翻历史的人拽回底部 ——
        //      正是本条最初要修的问题。两处统一用 totalItemsCount。
        //   ④ `isAtBottom` 改 `derivedStateOf`：`LaunchedEffect` + `mutableStateOf`
        //      在**每滑一帧都重新赋值**（哪怕值没变也触发重组）；derivedStateOf
        //      只在读到的值真变了才通知下游，长列表滑得越多省得越多。
        //   ⑤ 浮动按钮半透明（alpha 0.88）+ **滚动中隐去、停手再浮现**：
        //      滑动时它压在滚过的文字上像脏点。用 `animateFloatAsState` 驱动 alpha
        //      而非 AnimatedVisibility —— 这里 BoxScope/ColumnScope 混排，
        //      AnimatedVisibility 两个重载会被解析歧义卡住，自己驱动 alpha 既
        //      绕开那个坑，也把出现/隐去平滑化（140ms）。
        // 2.2.30 = 熄屏断流、亮屏接回（策略由用户定：**熄屏=断连，亮屏=实时**）。
        //   这是耗电的第二大头：`eventSubscription` 原先只在 onCleared() 与主动断开时
        //   关闭，App 切后台/熄屏后 WebSocket 一直连着 —— 息屏后 WiFi 射频与 TCP
        //   keepalive 全程在线，系统还会反复唤醒进程。
        //   ① MainActivity 动态注册 ACTION_SCREEN_OFF / ACTION_SCREEN_ON
        //      （`ContextCompat.registerReceiver` + RECEIVER_NOT_EXPORTED：API 34 起
        //      动态接收器强制要声明导出性，不写直接抛 SecurityException）。
        //   ② **为什么不用 onStop**：切到别的 App、分屏时屏幕还亮着，用户看得到内容、
        //      实时性仍有价值，那时断流是白白牺牲；只有真熄屏（电源键/合盖/超时黑屏）
        //      保持连接才是纯耗电。`ACTION_SCREEN_OFF` 正好把这两种情形分开。
        //   ③ ViewModel 新增 pauseEvents() / resumeEvents()：熄屏只关流，
        //      **保留 active 连接与令牌**（不走 disconnect() —— 那会把 active 一起清掉，
        //      亮屏得重走探活+配对校验，代价远大于收益）。
        //   ④ 亮屏时补三样，缺一样用户就会觉得「手机卡住了/什么也没发生」：
        //      会话列表 + 当前会话历史（复用 scheduleChatRefresh，自带 400ms 去抖与单飞），
        //      以及**审批面板与待答问题** —— 这两类不走 history，熄屏期间若正好有
        //      审批在等或 Agent 提问了，不单独补刷新就永远不出现，而电脑端还在等决定。
        //   ⑤ `eventsPaused` 标志用来区分「熄屏暂停」与「用户主动断开」：disconnect()
        //      会清掉它，否则下次亮屏会把用户刚断开的连接又接回去。
        // 2.2.31 = 输入框 `@` 调取当前工作目录的文件/文件夹（对齐官方那条功能）。
        //   官方输入框两种补全：`/` 加载命令（本 App 早有），`@` 取工作目录文件/夹（本轮补）。
        //   ① 数据来源：会话对象自带 cwd（绝对路径），而列举接口要「根 id + 相对根的路径」，
        //      中间过一次 `api.resolvePath(cwd)` 翻译 —— 客户端没有根的绝对路径，自己算不出来。
        //      任一步失败就静默放弃（mentionEntries 保持 null），`@` 出不来不该弹出错误打断打字。
        //   ② 与「文件」页的 directory 状态**刻意分开**存（新增 mentionEntries 字段）：
        //      那个是用户自己点来点去的浏览状态，`@` 必须始终指向本会话 cwd；
        //      共用会让「在文件页翻到别处，回会话里补全出错目录」。换会话时也要清空，
        //      否则切过去的头几百毫秒会列上个会话的文件、手快选中就发出错误路径。
        //   ③ 触发规则（纯逻辑放 data/MentionQuery.kt，有 15 条单测）：
        //      `@` 必须在行首或空白之后。`foo@bar.com` 这种**不触发** ——
        //      宽松匹配会在正常打字时突然弹列表，比不弹更烦。这是本块最容易被改坏的地方。
        //   ④ 选中只替换「@…末尾」那一段，不做整行替换：用户往往已写了半句
        //      「帮我看看 @xxx」，整行替换会把前半句吃掉。
        //   ⑤ 插入**绝对路径**（对齐官方与内核惯例）：相对路径会让模型先猜「相对于谁」，
        //      猜错就是一次无效工具调用。目录结尾补 `/` 不加空格（便于继续下拉一级），
        //      文件结尾补空格（结束这个词）。
        // 2.2.32 = 「插件」页从只读清单升级为可操作：详情增强 + 手机上直接启停
        //   （配套插件端 v2.3.2 的 profile 判定修复 + v2.3.3 的 PATCH 端点）。
        //   ① 详情：description 直接显示（手机上只有一串 @scope/name@1.2.3 时，
        //      用户靠它判断「这个能不能停用」）；homepage 可点（服务端已剥 git+/.git）；
        //      updateAvailable 只在**确实有新版本**时提示 —— 它是三态，null 表示没查，
        //      显示成「已是最新」就是撒谎。
        //   ② 启停：Switch 只对 `toggleable` 且本设备有 settings.write 时出现。
        //      不能启停的**必须说明原因**（官方包 / 没装 / 声明缺失），否则用户会去找
        //      一个根本不存在的开关。
        //   ③ **先确认再执行**：这会改电脑上 profile 的 package.json，写坏了 dsh 下次
        //      起不来、而用户此刻在手机上。对话框里说清「会写进电脑上的插件清单，
        //      并自动备份原文件」。
        //   ④ 成功后重拉清单：本地那份 loaded 还是旧值，不重拉开关会弹回原位，
        //      用户会以为没生效（实际生效了，只是要重启 DSH 才算数）。
        //   ⑤ 把「要重启」说在明处：页面上有条件地显示一行说明，操作成功也给一次性
        //      提示 —— 服务端恒返回 restartRequired=true，不说用户会以为功能坏了。
        //   ⑥ 「检查更新」单独一个按钮：它是重活（每个包一次 registry 请求，清单从
        //      毫秒级变成好几秒），与顶栏那个轻快的「刷新」不是一类动作。
        //   ⑦ 顺手更正一条过期文案：`plugins_unavailable_profile_unknown` 原本写
        //      「请确认 DSH 启动时带有 --profile 参数」—— 那是 v2.3.2 之前的说法，
        //      现在 `dsh web` 简写也能识别了。
        // 2.2.33 = 修 2.2.31 那个 `@` 补全**从写出来就没成功列出过文件**的 bug
        //   （配套插件端 v2.3.5）。用户报「会话里用 @ 有问题，只显示『读不到这个
        //   会话的工作目录』」。
        //   ① 根因一（注释写反了）：2.2.31 这里把 cwd 记成「绝对路径」，
        //      **实际是「相对授权根」的 wire 路径** —— 服务端 ChatSessionView.cwd
        //      的注释一直这么写。于是把它送去 `resolvePath` 时，服务端对相对路径
        //      **硬拒绝**（400 PATH_NOT_ABSOLUTE），补全必然拿不到数据。
        //      而且那一步翻译本身就是多余的：会话里已经有 rootId，列举接口要的正是
        //      「根 id + 相对根的路径」，两个值都现成，中间那次转换纯属绕路。
        //   ② 根因二（空串被当成「没有」）：会话工作目录**正好等于授权根本身**时，
        //      服务端算出的相对路径是**空串**（path.relative(root, root) === ''）——
        //      那是合法值，意思是「根目录」。原先 `takeIf { it.isNotBlank() }`
        //      把它当成「没有工作目录」直接放弃，界面于是显示「读不到」。
        //   ③ 根因三（App 手里没有绝对路径）：RootView 刻意不含根的 realPath
        //      （那是机器信息），所以拼不出官方那种绝对路径。服务端 v2.3.5 新增
        //      可选字段 `cwdAbsolute` 补上这一环；拿不到时（老服务端）降级插入
        //      相对根的路径 —— 能用，但不完整。
        //   ④ MentionQuery.absolute 一并纠错：列举接口给的 entry.path 是
        //      **相对授权根**的完整路径，会话目录只是它的一段前缀 —— 正确操作是
        //      **换前缀**（把前缀换成绝对根），不是「cwd + 相对路径」那种拼接。
        //      旧实现与旧单测都按错的语义写，一起改了；顺带钉住「前缀要按整段比对」，
        //      否则 "ws" 会误配 "wsx/a.txt"、拼出一个不存在的路径。
        //   ⑤ 面板文案分三态（新增 mentionFailed 字段）：`null`+未失败 = 还在读、
        //      `null`+失败 = 读不到、空列表 = 真的是空的。此前「加载失败」会被说成
        //      「这个目录还是空的」—— 把失败谎报成空，和插件页那句
        //      「package.json 可能损坏」是同一类毛病。
        // 2.2.34 = 会话页三处减法 + `@` 面板可滚动（纯 App 侧，不动插件）。
        //   ① **`@` 面板上划会拉出抽屉**（用户实测）：看着像抽屉乱抢手势，
        //      实际是那个面板用的是 `Column` + `forEach`，**本身根本不能滚动**，
        //      还硬截 8 条。用户上划时列表纹丝不动、手势没人消费，就顺着通道传给了
        //      外层 `ModalNavigationDrawer`。改法两层：列表换成 LazyColumn（可滚动，
        //      上限 264dp，条数 8 → 50）；另外面板开着时把抽屉的横划手势也关掉
        //      （新增 `HarnessUiState.composerOverlay`，与既有的 `webChatOpen` 同一套思路）。
        //      两道都要：列表滚到顶/底时手势仍会外溢，而面板之外的横划也不该开抽屉。
        //   ② **取消会话页顶部的「长按可选中复制…」提示条**（用户要求）。
        //      连同 `copyHintDismissed` 状态与中英两条字符串一起删。
        //   ③ **取消消息流末尾的「本次产生的文件」区块**（用户要求）。
        //      原生侧删 `SessionFilesFooter` 与只服务它的 `sessionFiles` 变量；
        //      WebView 侧同步删（`chat.html` 的 `<details id="files">` 元素、`setFiles`/
        //      `openFile`、`window.dshChat.setFiles` 导出、`#files` 系列 CSS，
        //      以及 ChatWebScreen 里那段推送）—— 两边显示同一批文件，只删一边会不一致。
        //      注意顶部那个文件夹按钮**保留**：`sessionFileList` 仍被它的弹层用着。
        // 2.2.35 = 长文件名改为横向滚动显示（纯 App 侧）。
        //   ① 起因是用户报「设置-文件中的文件名过长，显示不全，判断不出到底是哪个文件」。
        //      查下去这里格外严重：文件列表第二行给的是「类型 · 大小」，对区分文件
        //      **毫无帮助**，而文件名又常常「又长又前缀雷同」——几个
        //      `DeepSeek-Harness-companion-v2.2.xx.apk` 截断后长得一模一样。
        //   ② 先试过放宽到 3 行（超长名仍截断），用户随后明确要「滚动显示」，于是
        //      四处统一改用 `Modifier.basicMarquee()`：文件列表项、文件预览标题、
        //      会话「文件」弹层的文件名、输入框 `@` 补全面板的文件名。
        //   ③ 为什么是持续滚动（`iterations = Int.MAX_VALUE`）：只滚一遍的话，
        //      动画结束会回到起点，结果与截断无异 —— 等于没改。`basicMarquee`
        //      只在文本**溢出时**才动，短名字不受影响；每轮之间默认还夹 1.2s 停顿。
        //      `LazyColumn` 会回收屏幕外的项，真正同时在滚的只有当前可见的几条。
        //   ④ 各处的第二行（类型·大小 / 相对路径 / 完整路径）**一律不动** ——
        //      它们是会换行、更适合阅读的那份参照，与滚动的名字互补。
        //   另：`artifacts/` 起只留「必须的」（两个上游基线包）与「最新的」，
        //   已发布到 Release 的 APK 不在本地囤积（见 STATUS）。
        // 2.2.36 = 平板（wide）界面三处优化，全部只在 wide 分支，手机端零改动。
        //   ① 起因是平板截图里会话列表标题大面积截断（`/media/wangke/OFF…` 被腰斩），
        //      根因是会话列表栏宽沿用**手机抽屉的 304.dp**，原样搬到了平板上。
        //      改成随屏宽伸缩 `sessionPaneWidth`：0.25 × 屏宽，夹在 320–440dp。
        //      纯函数 + 单测钉边界（840dp 屏 → 320dp 下限，1920dp 屏 → 440dp 上限）。
        //   ② 会话行标题在 wide 下从单行放宽到**两行**（手机保持单行）：
        //      长路径标题换行比截断更可读，这是截图里最扎眼的伤。
        //   ③ 未选会话时右侧原来只画一句居中的「选择一个会话」——
        //      平板上那是超过一半屏幕的纯空白。改为 `SessionOverviewPane`：
        //      主机状态 + 最近 5 个会话（点击直接选中）+ 新建会话入口。
        //      刻意不显示「最后活跃时间」：内核 `ChatSession.updatedAt` 单位未定
        //      （毫秒/秒），猜错就显示 1970 年 —— 锦上添花的东西宁可不做。
        //   ④ 聊天详情页顶栏标题 wide 下同样允许两行：兜底到长 cwd 路径时不再腰斩。
        //   ⑤ 标题兜底链（title → workspaceTitle → cwd → id 前 12 位）抽成纯函数
        //      `sessionDisplayTitle`，会话列表与概览面板共用一份，不再各抄一遍。
        // 2.2.37 = 平板导航抽屉默认收起 + 软/硬键盘发送键（纯 App 侧，全部只在 wide 或
        //          物理键盘场景，手机端零改动）。
        //   ① **导航面板默认收起**（用户方案：「设置的抽屉继续默认收起来，让会话页面
        //      显示区域更大」）。原来 wide 分支常驻一根 264dp 的面板，白白吃掉 20% 屏宽。
        //      改为默认收起、左缘留 48dp 汉堡按钮，点它以 overlay 形式从左侧滑出 264dp
        //      抽屉（与手机 ModalNavigationDrawer 同一套观感，只是这里常驻且状态驱动）；
        //      点抽屉外的透明区域、或切 section / 宽窄变化时自动收起 —— 抽屉是一次性的，
        //      选完就该把屏幕还给内容。内容区因此从「屏宽 - 264dp」变成整屏。
        //   ② **软/硬键盘发送键**。先说一个既有事实：软键盘的「换行」键与物理 Enter 在
        //      `onKeyEvent` 里派发**同一个** `Key.Enter`，事件本身分不出来源（老代码注释
        //      2005-2007 行已踩明）。所以 Enter 一律换行、发送只走 Ctrl+Enter 是底线，
        //      不能改。这次补的是两件真问题：
        //      · 平板 + 外接键盘是常态，点输入框却仍弹出占掉半屏的软键盘 —— 纯浪费。
        //        检测到物理键盘（`Configuration.keyboard == KEYBOARD_QWERTY`）时，
        //        输入框获焦即主动 hide 软键盘控制器，不依赖实验 API、不改 KeyboardOptions。
        //      · 硬键盘用户根本不知道 Ctrl+Enter 能发送。空闲占位符追加「Ctrl+Enter 发送」
        //        （`composerHint` 新增 IDLE_PHYSICAL 态）。只加在空闲态：运行中两段文案
        //        本身已在说「发送即插话/排队」，再拼一段会超出占位符的一行容量。
        // 2.2.38 = 修 v2.2.37 两个实测回归（用户报「抽屉图标和会话列表标题重复了，
        //          无法点击」+「硬键盘回车、Ctrl+回车、Shift+回车 都是换行」）。
        //   ① **汉堡按钮不再浮在内容之上**。v2.2.37 把它按 `align(TopStart)` 压在内容左缘，
        //      正好盖住 ChatScreen 会话列表栏顶部的「会话」标题 —— 那一整片点击被上层吃掉。
        //      改为内容左缘一条**独立的 48dp 竖条**（装着开关），自己占位、不遮任何内容，
        //      内容区从它右边开始，天然不重叠。代价是内容区少 48dp，相比常驻 264dp 面板
        //      仍省 216dp，且顺带当「有导航可开」的常驻提示。
        //   ② **软键盘 hide 改到输入框获焦时**。v2.2.37 的 `LaunchedEffect(physicalKeyboard)`
        //      在 Composer 组合时执行 —— 那时输入框**还没焦点**，hide 是空操作；等用户点
        //      输入框，软键盘照弹，物理键盘的 Enter / Ctrl+Enter 全被 IME 吞掉，
        //      `onKeyEvent` 一个事件都收不到（表现为「回车就是换行」——换行正是 IME 行为）。
        //      改为 `BasicTextField.onFocusChanged` 监听焦点，为真即 hide。
        //      顺带按「无用即删」删掉没调 requestFocus 的 FocusRequester。
        // 2.2.39 = 修 Ctrl+Enter 发送（用户第三轮反馈里唯一保留的一项，本版无界面改动）。
        //   ① **`KeyEvent.FLAG_SOFT_KEYBOARD` 来源判据**（值 2）：IME 通过
        //      `sendKeyEvent()` 送进来的事件都带它，外接键盘的不带。于是
        //      「触摸屏的 Enter 一律换行、只有物理键盘的 Ctrl+Enter 才发送」，
        //      不再依赖「IME 不会补发带修饰键的事件」这种没保证的假设。
        //      判据抽成纯函数 `isSoftKeyboardEvent(nativeFlags)` 并单测。
        //   ② 配套保留 v2.2.38 的软键盘 hide 时机修复（组合时 → 输入框 onFocusChanged 获焦时）。
        //   **本版不含界面改动**：曾在这里试过「会话列表也可收起」（48dp 竖条开关、
        //      收起时把 320–440dp 全让给详情），用户实测后要求回到 v2.2.38 的界面
        //      （会话列表常驻、不收起；一级导航仍然默认收起，那是 App.kt 的另一处）。
        //      相应代码、字符串（session_pane_expand / session_pane_collapse）、
        //      SessionPaneToggle 组件已全部删除，ChatScreen.kt 的 wide 分支
        //      与 v2.2.38 逐字一致（只多一段「曾试过、已回退」的注释备查）。
        // v2.2.40（versionCode 10065）：**纯重构，无任何界面或行为改动**。
        //   基于什么：TECH-DEBT.md 行数台账上的三条债（ChatScreen.kt 3882 /
        //     HarnessViewModel.kt 2277 / ChatPresentation.kt 825）全部超 800 行预算。
        //   为什么：改聊天页任何一处要在 3600 行里定位，改 ViewModel 要先想清属于哪个域。
        //   干了什么：按职责边界拆成 ui/chat/ 下 12 个文件 + Harness*ViewModel.kt 8 个
        //     域文件 + data/ChatPresentation 5 个文件。ViewModel 域函数写成
        //     `HarnessViewModel` 的**扩展函数**而非子类，调用点写法完全不变，
        //     所以 ui/ 下所有界面一行都没改；每个函数实现逐字保留。
        //   代价：原 private 的部分成员提升为 internal（同 module 可见，比 public 窄）；
        //     JSON 实例改名 chatJson/chatPrettyJson 避免与 GenUiBlocks 的私有 json 遮蔽。
        //   测了什么：testDebugUnitTest 27 类 / 290 用例 / 0 失败（与拆分前逐项一致）、
        //     assembleDebug 通过、docsCheck 8 文档 43 任务一致、app/src 零文件超 800 行。
        //   未做：真机验收 —— 本版就是为此出的包，**不发布**。
        // v2.2.41（versionCode 10066）：**应用改名 + 包名迁移**，唯一一版会破坏「覆盖安装」的版本。
        //   基于什么：华为应用市场驳回「应用与『DeepSeek』应用名称相似，未提供相关授权或商标权属证明」
        //     （知识产权类），逐字对上了本 App 旧名 `DeepSeek Harness`。
        //   为什么：NOTICE 里那份 MIT 授权只覆盖 logo 路径的**著作权**，不是「DeepSeek」这个**商标**，
        //     著作权 ≠ 商标权，拿它去过审大概率不认。商标风险在**用户可见的应用名**上，
        //     堵住可见名称是刚需；包名一并换是因为旧包名带着上游作者 hakunm 的命名空间
        //     与 deepseekharness 字样，落在人工审核视野里同样是混淆源。
        //   干了什么：
        //     ① 应用名 `DeepSeek Harness` → **`DSH伴侣`**（英文环境 `DSH Companion`）；
        //        连接页标题改为「**连接 你的 DSH 服务端**」/ `Connect to your DSH server`
        //        —— 它描述的是用户自己部署的服务端，不是本 App，全名反而指错了对象。
        //     ② 包名 `io.github.hakunm.deepseekharness` → **`io.github.westanke.dshcompanion`**
        //        （namespace + applicationId 同步，源码目录整体搬家）。
        //     ③ `ConnectScreenTest.kt` 原本硬编码英文字面量 "Connect to DeepSeek Harness"，
        //        改名必红；改为按 en 配置开 Context 读 `values-en`，且**不用 activity.getString**
        //        —— 语言切换走 AppCompatDelegate.setApplicationLocales 会触发 Activity 重建，
        //        切换瞬间 getString 仍可能拿到旧 locale 的值。
        //   代价（有意接受）：**applicationId 变了就是另一个 App**，老用户无法覆盖升级，必须先卸载，
        //     而卸载会清掉已保存的连接与令牌，需重新配对。这是本次唯一无法两全的点。
        //     旧包名保留在 git 历史（tag v2.2.40 及更早），需要追溯时去那里看。
        //   测了什么：testDebugUnitTest 全量用例 0 失败、docsCheck 通过、
        //     compileDebugKotlin 真执行（不是 UP-TO-DATE）。UI 测试需真机/模拟器，本地未跑。
        //   未做：真机验收 —— 本版需重新上架，等发话。
        // v2.2.42（versionCode 10067）：**对标补强 A/B/C 三批 + Whaleness 会话页舒适度**（界面与协议两侧同动）。
        //   基于什么：dsh-study/分析-2026-10-16/ 的六份对比报告（对 Whaleness dsh-mobile 与
        //     dsh-plugin-mobile-gateway），以及用户拍板的三条要求（A/B/C 三批全做、会话页舒适度对齐、
        //     设置页选项合并）。姊妹仓 dsh-remote-bridge 同期升 2.3.8。
        //   干了什么：A 批 —— 图片点开与存相册、代码/JSON 高亮、滚动冻结、过程分组+耗时摘要、失败重试、
        //     消息时间戳、按 atSeq 从某条用户消息分叉、已归档会话列表（恢复不做，内核能力不可证）、
        //     字号缩放与外观设置合并；B 批 —— 架构守卫、请求代际隔离、事件去抖、投影缓存、
        //     Clock/Transport 接缝；C 批客户端侧 —— 能力位探测、断档检测 resync、订阅基线、
        //     连接即重放、交付物实时事件、会话统计与轨迹页。
        //   为什么：这批跨 71 个文件、同时动界面与协议，需要一个能与已发布 2.2.41 区分的版本号。
        //   测了什么：testDebugUnitTest 44 类 / 410 用例 / 0 失败、docsCheck 8 文档 / 46 任务一致、
        //     lintDebug 通过、compileDebugKotlin 真执行（不是 UP-TO-DATE）。
        //   未做：真机验收 —— 所有界面改动均未上机，等发话。
        // v2.2.43（versionCode 10068）：**纯仓库卫生，无任何代码或界面改动**。
        //   基于什么：清查仓库根目录下 `.wm` 类文件夹时发现，v2.2.42 从「四打」排到「十一打」
        //     留下的 12 个「解包取证」目录（.wm~.wm8 / .apkcheck / .apkcheck2 / .m3 / .mdlib）
        //     被一次 git add -A 误提交，共 146 文件 / 40.9 MB；同时 .gitignore 对 .secrets/
        //     只有 *.jks / *.keystore / signing.properties 三条按文件名的规则。
        //   为什么：前者让每个 clone 白拖 41 MB 垃圾，且看起来像有意保留的资产、后来者不敢删；
        //     后者是真实泄露口子 —— 往 .secrets/ 放一个 secret.txt 就会随 git add -A 进三个远端，
        //     而那目录里是发布签名密钥（dsh-release.jks）与两份口令文件。
        //   干了什么：commit 79d015c 删净 12 个目录并补 /.wm*/ /.apkcheck*/ /.m3/ /.mdlib/ 四条规则；
        //     commit afbea7d 补 /.secrets/ 整目录忽略（第 23 行）。本版只随附版本号，
        //     **APK 代码与 v2.2.42 完全一致**，没有任何功能或界面差异。
        //   测了什么：docsCheck 8 文档 / 46 任务一致；忽略规则正负向实测（真建 .wm9 / .apkcheck3 /
        //     .m3 / .mdlib 四个目录均命中，docs/ 与 app/ 未误伤）；漏洞回归（.secrets/__probe__/
        //     secret.txt 已不被 git 看见）；全量单测本轮重跑，数字见 TESTING.md。
        //   未做：真机验收 —— 无界面改动，不需要上机。
        // v2.2.44（versionCode 10069）：工具专用块 + 权限档位 Pill + chat.html 对标补全 + 会话页存量修复。
        //   基于什么：《官方Web界面与APP功能对比 2026-10-18》第一梯队两项
        //     （工具名分布按本机真实会话日志实测：3000 条，bash 71% / edit 9% / read 8% / write 2%）；
        //     以及用户逐项点名的 chat.html 与原生界面差距、「回答被截断」提示缺失（真机 2026-10-18 复现）。
        //   干了什么：data/ChatToolBlocks.kt（分发 + ANSI 剥离，失败回落 Generic）；
        //     ui/chat/ChatToolBlocks.kt（Read/Terminal/Diff 三种专用块，接入 ToolCallDetailBlock）；
        //     ui/chat/ChatPermissionPill.kt（发送区常驻权限档位，与 ⋯ 菜单同源同动作）；
        //     chat.html 拆分为 355 行壳 + 6 JS + 1 CSS（assets/chat/，逐字搬迁、拼接 diff 为零）；
        //     chat.html 对标补全（模型按厂家树型、工具轨迹与统计面板、@ 文件补全、▤ 靠右、/ 面板落回）；
        //     HarnessTruncationViewModel（turn/end 的 reason.kind=='max-tokens' 落截断提示，配套 bridge v2.4.1）；
        //     debug/DiagLog + HarnessDiagViewModel（诊断日志开关）、ConnectImportPanel、ChatScrollFreeze 落底跟随。
        //   测了什么：新 ChatToolBlocksTest 16 例；全量 55 类 / 478 用例 / 0 失败；
        //     docsCheck 8 文档一致；lintDebug 0 error；行数门禁绿（ChatDetail 压回 800 内）。
        //   未做：@ 补全与截断提示的真机端到端复核（截断提示需 bridge ≥ 2.4.1）。
        // v2.2.45（versionCode 10070）：真机验收三缺陷收口 + 权限档位打通 + 会话页九条交互整改。
        //   基于什么：v2.2.44 真机验收报告（.verify-v2244/，vivo V2080A 5.5 小时，3 失败 + 4 有缺陷）
        //     与用户逐条点名的九项交互反馈（顶栏太挤、状态不直观、文件打不开、权限改不了、发送/停止混在一起）。
        //   干了什么：① F1 权限档位——bridge 补 projections 透传与 permission-presets BFF，
        //     App 改为「当前值取投影 + 候选取目录」两路合成，候选项为空仍显示只读档位；
        //     ② F2 结案——不是 App 缺陷，是键盘弹起让对话框上移 426px 导致点击落空（已入 PITFALLS）；
        //     ③ F3 @ 补全——创建路径补 loadMentionEntries + 面板加重试；
        //     ④ 九条交互：权限 Pill 移到状态行、状态改「已连接/断开」+绿红点、相对路径与 autolink 能打开、
        //     文件类型不支持调系统应用打开、追加引导灰置时说明原因、创建会话可选权限档位、
        //     刷新/停止收进 ⋯ 菜单、输入区控件垂直居中 + 发送箭头向上、发送键三态（有内容=发送/运行中空=停止）；
        //     ⑤ 权限描述全本地化（中文界面不再中英混显）；⑥ W1 统计口径只数真用户轮次、W2 表格行内代码、W3 未保存保护。
        //   测了什么：57 类 / 505 用例 / 0 失败；docsCheck 8 文档；lintDebug 0 error；
        //     真机逐条核对（顶栏、状态点、Pill 切换、停止键、文件打开、权限菜单全中文）。
        //   未做：「用其他应用打开」未真机验证（缺 docx/zip 素材）；桥最低版本升到 2.4.2。
        // v2.2.46（versionCode 10071）：**后台任务面板**（会话 ⋯ 菜单 →「后台任务」）。
        //   基于什么：远程场景下「任务跑飞了看不到也停不掉」——人在外面，卡住的 bash 任务只能干等。
        //     服务端先行：bridge 2.4.3 新增 /chat/sessions/{id}/jobs 三端点 + 三个能力位。
        //   干了什么：data/JobViews.kt（wire 数据）+ DshClient 三方法 + supportsSessionJobs
        //     能力位判据 + JobsPanelState/HarnessJobsViewModel（开面板/读清单/终止/展开输出）
        //     + ChatJobsSheet（任务卡、状态徽标、运行时长、输出等宽区、终止确认、手动刷新）。
        //     不做实时推送：服务端就是 REST 只读一帧，不为「看起来实时」在手机上挂轮询。
        //   测了什么：59 类 / 524 用例 / 0 失败；docsCheck 8 文档 + README 双站 14 项；
        //     lintDebug 0 error；真机验收入口/清单/输出/刷新全绿。
        //   未做：终止确认框未真机点到（本机没有 running 任务）。
        // v2.2.47（versionCode 10072）：**文档链接带位置后缀打不开**（真机截图反馈，与鸿蒙版同源双端修复）。
        //   基于什么：模型引用文档几乎总带位置 —— `docs/project/STATUS.md:1`、`/abs/STATUS.md#L101`
        //     都是常见写法。这些后缀不是路径的一部分，原样发给 `roots/resolve` 只会回「不是普通文件」。
        //   两层根因：① 位置后缀被当成路径的一部分（文件名里真的多了个 `#L101` / `:1`）；
        //     ② 分类用 indexOf(':') 一刀切认 scheme，`docs/x.md:1` 被判「未知协议」，连拼 cwd 的机会都没有。
        //   干了什么：data/ChatLinkTarget.kt 新增 stripLocationSuffix()（只剥确定形态 :数字 / #L数字，
        //     含 :12-34 / #L12-L34 / :12:5，大小写不敏感；最多剥两段；剥成空串则不剥）
        //     + 纯函数 joinUnderCwd()（先剥后缀再拼 cwd）；绝对路径在 classify() 里剥、
        //     相对路径在 ChatLinkHandlers 拼 cwd 前剥。刻意不动 `https://e.com/a#L1`（页内跳转）
        //     与 `http://host:8080`（端口），靠「网页分支返回原文」保住。
        //   测了什么：60 类 / 542 用例 / 0 失败；docsCheck 8 文档 + README 双站 14 项；lintDebug 0 error。
        //   未做：真机复验由用户自行完成。
        // v2.2.48（versionCode 10073）：**三笔未发版合并发版**（表格贴行 + 应用内更新检查 + 会话内联交付物卡片）。
        //   基于什么：三笔分别完成于 2026-10-20 / 10-21（前两笔真机已复验），都没赶上 v2.2.47 的车，
        //     一直以「未发版」挂在 CHANGELOG-DEV 里；本次随 v2.2.48 一起发出。
        //   干了什么：① Markdown 表格贴行 —— ChatMarkdownNormalize.kt 渲染前补表格前边界空行
        //     （org.intellij.markdown 不认无空行 GFM 表格），AssistantBody plain/prose 两分支接入，
        //     连带修三列表首列被窄列截断（ChatMarkdownTable.kt 仅 ≥3 列首列按权重折行）；
        //   ② 应用内更新检查 —— 设置「关于」区条目，UpdateChecker.kt 按界面语言分流渠道
        //     （zh*→Gitee、其他→GitHub），下载到 cacheDir/updates/ + SHA256SUMS 校验 + FileProvider 安装引导；
        //   ③ 会话内联交付物卡片 —— ChatItemKind.DELIVERABLE + displayItems() 两段式冲刷
        //     （每轮 turn/end 处出卡、钉轮次收尾、同轮同路径单卡），ChatDeliverableCard.kt
        //     点按走 openSessionFile + 文件面板。
        //   测了什么：62 类 / 575 用例 / 0 失败；docsCheck 8 文档 + README 双站；lintDebug 0 error。
        //   未做：③ 交付物卡片真机复验由用户自行完成（①②已复验）。
        // v2.2.49（versionCode 10074）：**模型/插件设置页对照官方 Web 对齐**（模型页大改 + 插件页搜索/展开卡 + bridge 2.5.0 目录元数据）。
        // v2.2.50（versionCode 10075）：**真机验证收口 2.2.49 三处小瑕疵**（目录通道状态/校验文案、切换 tab 残留候选）。
        // v2.2.51（versionCode 10076）：**配色主题四套 + 平板零风险项 + bridge 2.5.1 安全模型同步**。
        //   基于什么：bridge v2.5.1 把管理面判据从「来源是否回环」升级为四层（隧道模式下管理面整体禁用），
        //     本 App 只消费 /api/v1、不碰 /manage，因此管理面收紧对本 App 零影响；但新增四个错误码需要文案，
        //     AGENTS.md §5 已补「桥的安全模型变了客户端必须跟着过一遍」的对照表。
        //   干了什么：① 外观新增「配色主题」四套（纸感/暖米/青灰/高对比，各含明暗两份色板），
        //     与既有的「界面风格（跟随系统/浅/深）」正交，默认「纸感」逐一字段等于改造前配色，不切就看不到变化；
        //     ThemePalettes.kt 承载色板、AppTheme 进 AppPreferences（存 name 不存 ordinal）；
        //   ② O-02：840dp 魔数收敛成 AdaptiveBreakpoints.kt 的具名常量 + 四档纯函数，与旧判据二进制等价；
        //   ③ O-06：会话/文件列表补 contentType（Message 与 ProcessGroup 混排不再错复用合成槽位）；
        //   ④ O-08：连接状态语义色改主题成对色，深色底 3.47/3.16 → 9.90/7.79（达 WCAG AA）；
        //   ⑤ O-10：附件删除键触摸区 30dp → 48dp（图标仍 15dp）。
        //   O-03 核实后为现状（三页自 e2ba3dd 起已居中），未再改。
        //   测了什么：66 类 / 588 用例 / 0 失败（新增 AppThemeTest 8 例 + PaneModeTest 4 例 + 偏好 1 例）；
        //     docsCheck 双向一致（STRUCTURE 132 锚点 / 113 源文件）；lintDebug 0 error。
        //     零漂移证据：AppThemeTest 的 66 个 ARGB 逐字段断言 + 真机切主题往返内容区逐像素 0 差异。
        //   未做：真机已验（V2080A，lastUpdateTime 21:18:51 → 23:17:03），平板 wide 未复验。
        // v2.2.52（versionCode 10077）：**过程组折叠修三层 + 正文图片能看 + 历史不再被刷新抹掉**（2026-10-22 真机反馈驱动）。
        //   基于什么：用户连报三件「看不了」的事 —— ①加载更早消息后整屏只剩「耗时 xxx · N 次工具调用」；
        //     ②不展开时页面上只有一排组头，正文被顶到屏幕外；③助手正文里的图片位置是一块空白。
        //   干了什么：①`OlderMessagesButton` 记下 index/偏移/条目数，数据变长后把 index 加回增量再 scrollToItem，
        //     视口原地不动、新内容出现在上方，并补加载态防连点；
        //   ②空助手消息（只有 reasoning + tool-call，真实会话里 46 条中 45 条如此）不再算「消息」，
        //     而是折进过程组 —— 它们此前把一条连续的工具流水劈成几十个组头；
        //   ③正文 Markdown 图片走附件通路（绝对路径 + 图片扩展名 → 缩略图/点开大图），
        //     其余（相对路径、非图片、远程）一律降级成 `[名字](目标)` 链接，绝不让渲染器收到 `![]()` 留白；
        //   ④`refreshHistory` 改用 `mergeRefreshedHistory`，刷新不再抹掉已经加载出来的旧页
        //     （真机日志 totalItemsCount 5→2 反复回退，正是「加载更早后又变空白」的根因）。
        //   测了什么：67 类 / 625 用例 / 0 失败（新增 HistoryMergeTest 4 例、ChatDisplayGroupsTest 3 例、
        //     ChatPresentationTest 3 例 + 1 例不可识别图片目标）；docsCheck 双向一致；assembleRelease 成功。
        //   真机验了什么：过程组不再被劈碎、图片位置出现缩略图、加载更早后内容留住（用户 2026-10-22 确认「显示出来了」）。
        // v2.2.53（versionCode 10078）：**设备身份上报：让桥知道这是哪台设备**（2026-10-23，配套桥 v2.6.1）。
        //   基于什么：用户在桥的设备表里看到 8 条一模一样的「Android 设备」，认不出谁是谁。
        //     根因在桥（配对生成配置文本时硬编码占位名），但桥侧无法从请求里推断机型 ——
        //     本 App 用 OkHttp，默认 UA 是 `okhttp/x.y.z`，既没有平台也没有型号，
        //     所以桥的 UA 兜底对本 App 完全失效。必须由客户端显式上报。
        //   干了什么：①`DshClient.authenticated(builder)` 统一加 `X-Dsh-Device: android/${Build.MODEL}`
        //     —— HTTP 与 WebSocket 建连走的是同一个 builder，一处加两处生效；
        //   ②`DeviceView` 增加三个可选字段 `platform` / `model` / `nameSource`，
        //     桥 v2.6.1 起会回填，老桥不返回时为 null（`ignoreUnknownKeys` + 默认值，不会崩）。
        //   为什么用结构化头而不是改 User-Agent：UA 是给整个 HTTP 栈看的公共字段，
        //     拿它塞业务信息会和代理、CDN、日志系统打架；专用头能表达「平台/型号」两段，
        //     且桥侧有平台白名单，认不出就整条丢弃，不会猜。
        //   测了什么：68 类 / 627 用例 / 0 失败（新增 2 例：身份头存在性与老桥缺字段兼容）；
        //     docsCheck 双向一致；lintDebug 0 error；assembleDebug 成功。
        //   真机验了什么：未验 —— 需要桥 v2.6.1 部署后，设备表里出现「Android · <机型>」。
        // v2.2.54（versionCode 10079）：**连接页顶栏归位：DSH 图标与语言/诊断按钮同一条中线**（2026-10-24）。
        //   基于什么：用户截图反馈「语言 调试两个按钮距离顶部太靠近，操作不便，美观没有，
        //     挪到与 DSH 图标一条线」。拆开是两个独立毛病，各有一个根因：
        //     ①「太靠近顶部」是 `MainActivity.enableEdgeToEdge()` 的直接后果 —— 整页没吃状态栏
        //       inset，顶栏（`vertical = 4.dp`）实际画在状态栏正下方，48dp 触控区上缘几乎贴着
        //       系统图标；②「不在一条线」是因为 `BrandMark()` 长在滚动区里（`Spacer(18.dp)` 之后），
        //       与顶栏之间隔着一次「滚动区起点」的层级差 + 18dp 空白。
        //   干了什么：①根容器加 `statusBarsPadding()`；②`BrandMark()` 从滚动区提到顶栏行首，
        //     与两个 `IconButton` 走 `Alignment.CenterVertically` 同轴，`Arrangement.SpaceBetween`
        //     把按钮推到右端；③滚动区删掉 `Spacer(18.dp)` + `BrandMark()`，标题 `top` 20→24dp。
        //   为什么敢把图标从滚动区拿走：它本来就是**常驻装饰**而非可滚内容，滚上去看不见它
        //     没有任何信息损失（标题与副标题仍在滚动区顶部）；顺带标题区不再被 40dp 图标 + 18dp
        //     空白顶下去，首屏能多露出一张电脑卡片。
        //   为什么图标侧 20dp / 按钮侧 8dp 刻意不等：IconButton 自带 12dp 内边距，8+12=20
        //     使按钮图标视觉左缘与滚动区 24dp 文字边距同一条竖线（差 4dp 在 40dp 圆角方块上
        //     肉眼看不出）。
        //   测了什么：docsCheck 双向一致（136 锚点 / 117 源文件）；lintDebug 0 error；
        //     assembleDebug 成功；单测数量不变（本版纯布局，无逻辑改动）。
        //   真机验了什么：V2080A 装包后逐像素核对（1080x2400）—— brand 方块 120x120px、
        //     诊断图标、语言图标三者**水平中线完全重合于 y=179.5**；UI 层级 `bounds` 确认
        //     brand `[60,120][180,240]`、诊断 `[768,108][912,252]`、语言 `[912,108][1056,252]`
        //     （144px = 48dp，符合设计系统触控区下限）；中/英 × 浅/深四组截图均无裁切。
        //   已知遗留：诊断浮层打开时压住 BrandMark 左下角（`DiagProbe` 固定在 TopStart）。
        //     按 PITFALLS.md「浮层不调死坐标」的既有教训不改坐标 —— 浮层本就可拖动。
        // v2.2.56（versionCode 10081）：**模型容量显示 + 空白会话复用正式发版**（2026-10-28）。
        //   基于什么：桥 v2.7.0 补上了模型目录的 contextWindow / maxTokens，并给
        //     POST /chat/sessions 加了可选的 reuseBlank。安卓端两处都早就留了位却没接：
        //     ①ModelView 从 v2.2.49 起就反序列化 contextWindow，但 ui/ 里只有编辑供应商的
        //       表单用它，模型选择器一行都没显示 —— 用户选模型时看不到「这个模型能装多少」；
        //     ②createSession 从不传 sessionId，桥 v2.6.x 下每点一次「新会话」都新建一个空白，
        //       于是同一工作区里堆出好几个空会话。
        //   干了什么：①ModelRow 在描述下面加一行小字显示上下文容量（只在 contextWindow 非空时
        //       出现），格式化走新增的 data/ModelCapacityLabel.kt（1024 进制，1M/1.5K）；
        //     ②CreateSessionRequest 加 reuseBlank 字段、createSession 加同名可选参数，
        //       默认 null —— kotlinx 不编码 null，所以老桥收到的请求体逐字节不变。
        //   为什么 reuseBlank 默认 null 而不是 true：null 让「谁支持这个语义」由桥决定 ——
        //     桥 v2.7.0 省略即 true（对齐 DSH 0.2.1 桌面行为），更老的桥不认识这个字段就
        //     维持原样。写死 true 会让请求体多出一个老桥不认识的字段，虽然被 ignoreUnknownKeys
        //     忽略，但那是「靠对方的宽容工作」，不是契约。
        //   为什么格式化函数放在 data/ 而不是 UI 层：它是纯数字→字符串，值得单测；放在
        //     ChatSheets.kt 里既超 800 行预算又没法单测。
        //   测了什么：新增 ModelCapacityLabelTest 7 例（1.0M/1.5M/16M/128K/1.5K/512，以及
        //     1024→1.0K 但 1000→1000 钉住进制）；单测 69 类 / 634 用例全绿；docsCheck
        //     137 锚点 / 118 源文件双向一致；lintDebug 0 error。
        //   真机验了什么：未验 —— 需要桥 v2.7.0 部署后，模型选择器里应出现「上下文 1.0M tokens」。
        // v2.2.57（versionCode 10082）：**对齐桥 v2.8.0 —— 分块上传 / 定时任务 / 官方语音三项 REST BFF**（2026-10-29）。
        //   基于什么：桥 v2.8.0 为三项原本只有 /ws/mobile 帧的能力补了 REST 端点（只增不改），
        //     契约见桥仓 docs/project/BFF-REST-CONTRACT.md。App 没有 /ws/mobile 客户端，
        //     走 REST 才能在不依赖 WS 帧的前提下拿到同语义能力。
        //   干了什么：
        //     ① 数据层新增 DshClientBff.kt（12 个扩展端点，复用 DshClient 唯一 OkHttp 客户端）、
        //        BffModels.kt（上限常量：块 1 MiB / 文件 256 MiB / 音频 4 MiB / 转写超时 180 s）、
        //        ChunkPlan.kt（分块计划与切片）、CronHint.kt（cron → 人话）、WavHeader.kt（PCM16 → WAV）；
        //     ② VM：HarnessSchedulesViewModel / HarnessSpeechViewModel；HarnessAttachmentsViewModel
        //        对 >4 MiB 且能力位支持的附件走分块（续传 + 409 补块一次 + 上传进度 chip）；
        //     ③ UI：ScheduleSheet（⋯ 菜单入口）、SpeechInput + SpeechRecorder（麦克风按钮、
        //        录音状态条、RECORD_AUDIO 运行时权限与永久拒绝引导）；
        //     ④ BridgeCapabilities 三个能力位（**定稿单值名** attachment-chunked-upload-v1 /
        //        chat-schedules-v1 / speech-transcription-v1，桥侧 tests/capabilities.spec.ts 有对应钉子）；
        //     ⑤ 双语 strings.xml 各 52 键；清单只新增 RECORD_AUDIO。
        //   跨仓对齐（桥侧实现落地后复核）：内核没有 ScheduleRecord 暂停语义，所以「停用」= 删除
        //     （先弹确认框，正文点明不可恢复），「启用」= PATCH enabled:true，409 单独文案；
        //     Schedule.createdAt 恒 null，不可据此排序（排序走 nextRunAt）。这两条按内核行为写成
        //     @patch 五要素，until 写明「内核补上 pause/resume 后改回 PATCH」。
        //   测了什么：新增 DshClientBffTest(17) / ChunkPlanTest(8) / CronHintTest(11) / WavHeaderTest(4) /
        //     BffCapabilitiesTest(5) / SchedulePolicyTest(7)；单测 75 类 / 711 用例 / 0 失败；
        //     lintDebug 0 error；docsCheck 双向一致。
        //   真机验了什么：未验 —— 录音兼容性、权限三态在 vivo ROM、分块对真实桥（单测用 MockWebServer 模拟）。
        //     退化行为已钉住：老桥 404 → isRouteMissing() → 入口隐藏；available:false 是正常结果不是错误。
        // v2.2.58（versionCode 10083）：**会话通道切到 /ws/mobile —— WS 优先、REST 兜底**（2026-10-30）。
        //   基于什么：用户拍板「C 档：全量 WS 优先 + REST 兜底」。此前 App 一条 WS 帧都没发过，
        //     DshClient.eventsUrl() 拼的是 /api/v1/events，全仓 ws/mobile 只出现在两行注释里；
        //     而桥的 /ws/mobile 网关早已跑通，缺的是客户端与对外契约。配套桥 v2.8.1。
        //   干了什么（P1-P3 只是换通道，P4 才是唯一的新功能）：
        //     ① P1 传输层基座 data/ws/：MobileWsTransport（复用 DshClient.defaultHttpClient，
        //        唯一网络边界不变）、ChannelState 四态（降级判据唯一真理源）、WsFrames、
        //        WsRequestRouter（requestId 配对 + 按 kind 兜底，兜底时同 kind 串行）、WsPorts 按域切端口；
        //     ② P2 Session/Message/Event；③ P3 HITL/Command/Model；
        //     ④ P4 目标写操作 goal-edit/pause/resume/clear（桥侧 compare-and-set，必须带
        //        ref:{id, revision}）—— 这是全程唯一真正新增的用户能力，此前 REST 路由里 goal 零命中；
        //     ⑤ 补齐两处「最后一米」（桥侧同改）：首屏 history 带 projections、session-create 带
        //        workspaceId/agentPreset/reuseBlank、select-agent-preset 接入（原注释「桥侧无此帧」是错的）；
        //     ⑥ 接收侧两条：认 connection-resync-required（重取历史 + 清 seq 基线）、429 认 Retry-After。
        //   为什么「读失败一律回落、写失败只在帧没发出去时回落」：写操作超时意味着不知道服务端
        //     做没做，重试可能改两次名、归档两次、分叉出两个会话。
        //   为什么回落判据不看版本号：能力位里没有「是否回显 requestId」这种位，靠「发了有没有回」自适应。
        //   测了什么：单测 86 类 / 866 用例 / 0 失败；lintDebug 0 error；assembleDebug 成功；
        //     docsCheck 双向一致（STRUCTURE 162 锚点 / INTERFACES 136 锚点）。
        //   真机验了什么：P2 顶栏绿点 + 104 会话 + 正文实时更新；P3 模型域读+写；
        //     P4 四项写操作全过（revision 每次 +1，判据取桥侧 WS 回读）。
        //     未验：approval-response、command-execute；桥侧心跳/连接上限/背压要重启插件才生效。
        //
        // v2.2.59（versionCode 10084）：**修复 WS sessions 帧裸数组解码崩溃**（2026-10-30，补丁版）。
        //   基于什么：v2.2.58 发版后真机对桥 v2.8.2 验收「新建会话」时抓到——会话创建成功，列表却立即报
        //     JsonDecodingException: Expected JsonObject, but had JsonArray … body of …SessionList。
        //   根因：同一份会话列表两通道形状不同。REST GET /chat/sessions 包一层 {items:[…]}，
        //     WS sessions 帧的 data.sessions 直接是数组（桥 listSessions 本就返回数组），而
        //     WsSessionPort.sessions() 复用了 REST 的 SessionList(items) 模型去解 JsonArray，必炸。
        //     真机真帧取证：data keys=['sessions','requestId']、Array.isArray(sessions)=true、len=105。
        //   修了什么：WS 侧改用 ListSerializer(ChatSession.serializer()) 直接解数组；删掉 WsPorts.kt
        //     里只为 REST 形状存在的私有 SessionList；补一条真形状回归用例（此前 WsSessionPortTest
        //     没有任何 sessions 帧用例——手造回帧喂什么形状都过，真桥才拆穿假设）。
        //   一并补记真机验收：桥 v2.8.2 下 command-execute 三档 success、approval-response 协议闭环
        //     accepted:true 且放行命令真落地（v2.8.1 的 409 是桥侧 rpcId bug，见桥仓 PITFALLS）。
        //   测了什么：单测 86 类 / 867 用例 / 0 失败；lintDebug、assembleDebug、docsCheck 全绿。
        //     真机：v2.2.58 复现崩溃 → v2.2.59 新建会话不再报错、自动进入新会话、列表正常。
        //
        // v2.2.60（versionCode 10085）：**纯文档一致性修复重打包，让发版 tag 落在绿 CI 上**（2026-10-30，补丁版）。
        //   背景：v2.2.59 的发布包功能正确，但 GitHub verify 红在 ArchitectureGuardTest「坑位索引与
        //     PITFALLS 正文双向一致」——AGENTS.md §7 索引条目正则 `^- `([^`]+)`` 不允许条目内嵌反引号，
        //     索引提取出的触发词是「REST 包 items」，而 PITFALLS.md 标题写的是「REST 包 `{items}`」，
        //     两边字面不等，867 用例 1 失败。
        //   修了什么：只把 PITFALLS.md 标题统一成「REST 包 items」（正文仍讲 {items} 形状），无任何代码/资源改动，
        //     APK 与 v2.2.59 内容等价（体积同为 3,324,284 字节）。发 v2.2.60 而非移动 v2.2.59 tag，
        //     是因为已发布的 tag/Release 不改写。
        //   测了什么：ArchitectureGuardTest 定向复跑通过；docsCheck + testDebugUnitTest + lintDebug +
        //     assembleDebug 四项 --rerun-tasks 全绿（867 用例 0 失败）。
        //
        // v2.2.61（versionCode 10086）：**智能团队只读面板 + 自然语言指挥条 + 权限档位分色**（2026-10-30）。
        //   基于什么：《手机端智能团体管理与会话功能研究报告 2026-10-30》P0/P1 裁剪方案；
        //     官方 Web 的智能团体 UI 本身就是只读监工面板（一个入口、两张只读表，唯一动作 openTeammate），
        //     建成员/派任务全靠 Lead agent 持有的模型工具，因此手机端无需新增任何桥写协议。
        //   干了什么：
        //     ① 数据层 data/AgentTeamProjection.kt：容错解析 history 投影 agentTeam（members/tasks/failure）
        //        与 subagentCatalog（id 与 bridge fixture 的 sessionId 双键都认，缺字段全有兜底）；
        //     ② UI 层 ui/chat/TeamPanelSheet.kt：成员名册（角色+在岗/组建中/失败状态）、共享任务板
        //        （四态/负责人/前置依赖/写范围/未就绪）、子会话目录三节只读卡片，底部「新成员/派任务/
        //        问进度」三枚指挥 chips → 预填话术对话框 → 作为普通消息发给 Lead（队列消息，不是插话）；
        //     ③ 入口两处：会话 ⋯ 菜单一级「智能团队」项（用户 2026-10-30 要求不藏子菜单），
        //        以及标题副行权限 Pill 之后的「智能团队」胶囊（primaryContainer 品牌色，与三档语义色区分）；
        //     ④ 权限三档分色分图标（用户 2026-10-30 要求）：只读=信息蓝 Lock、工作区写入=琥珀 Edit、
        //        完整访问=危险红 WarningAmber，未知档位中性灰；图标复用菜单侧既有 permissionIcon()，
        //        颜色取 App 统一语义色板（SemanticInfo/Warn/Err Container），不硬编码 hex。
        //   测了什么：新增 AgentTeamProjectionTest（5 用例：全字段/极简 fixture/sessionId 键/空投影/脏数据）
        //     与 PermissionToneTest（2 用例：三档映射+未知回退）；单测 88 类 / 874 用例全绿；
        //     docsCheck（STRUCTURE 164 锚点双向一致）、lintDebug 0 error、assembleDebug 全绿。
        //   真机验了什么：V2080A 覆盖安装（lastUpdateTime 17:52）——本会话副行依次为绿点「已连接」、
        //     红色「完整访问」胶囊、品牌色「智能团队」胶囊；点团队胶囊直达面板，lead/负责人/在岗、
        //     任务板空态、2 个「可续用」子会话（两个调研子智能体 id 正确）、指挥对话框预填正确。
        //     未做（报告 P2，需桥侧改造）：点成员跳进子会话（桥滤掉 origin:subagent 会话）、
        //     parentSessionId 入站透传。
        //
        // v2.2.62（versionCode 10087）：**小屏顶栏重排 + 修复流式期加载更早历史被冲掉**（2026-10-30，补丁版）。
        //   基于什么：v2.2.61 真机当天用户连提四点——①「智能团队」胶囊在 360dp 小屏只显示「智能」两字；
        //     ②全局应用栏（DSH伴侣/会话）与会话标题上下间距太大；③长标题被文件按钮挤掉宽度；
        //     ④模型正在流式输出时点「加载更早消息」，加载出的历史一闪即消失。
        //   干了什么：
        //     ① ui/chat/ChatHeader.kt 顶栏改两段式：标题行只放 返回/标题/⋯（文件按钮移出，长标题
        //        可用宽度约 570px→738px）；状态行独占整行，已连接/权限/智能团队从左边距排起，
        //        Spacer 把文件按钮顶到行尾——小屏下「智能团队」四字完整不再被裁。
        //     ② ui/App.kt 全局 TopAppBar 在会话详情页收高到 46dp 并隐去重复的「会话」分区副标题，
        //        程序标题与会话标题间距收紧；列表页保持默认高度不变。
        //     ④（核心竞态）HarnessViewModel.scheduleChatRefresh() 的流式自动刷新原来拿回最后一页后
        //        直接整体替换 history，把 loadOlderHistory 已合并的更早页冲掉；改走既有的
        //        mergeRefreshedHistory() 合并口（早先 seq 保留、尾页新内容接上、hasMore 沿用用户页）。
        //        HarnessSessionsViewModel 里另两处同患裸覆盖（isHostCommandLine 命令后、
        //        selectPermissionPreset 后）一并改。
        //   测了什么：HistoryMergeTest 增 autoRefreshDuringStreamingKeepsOlderLoadedPage
        //     （previous=1,2,3,10,11 / 流式刷新只回尾页 10,11,12 hasMore=false → 合并 1,2,3,10,11,12
        //     且 hasMore 保持 true）；单测 88 类 / 875 用例全绿；docsCheck、lintDebug 0 error、
        //     assembleDebug 全绿。
        //   真机验了什么：V2080A（1080x2400,360dp）强制停止重启后核对 base.apk SHA 与本地一致
        //     （教训：上一轮误以为装机成功，实际 install job 被提前 kill，lastUpdateTime 是假象）。
        //     dump 实测：「智能团队」[531,318][663,366] 四字完整；文件图标移到状态行行尾；
        //     全局栏只剩「DSH伴侣」一行无「会话」副标题；标题区加宽。④ 的点按现场受流式期
        //     列表自动回底影响难稳定手动复现，以竞态根因+定向回归用例兜底。
        //
        // v2.2.63（versionCode 10088）：**修复 v2.2.62 在高状态栏机型上应用栏整行被裁**（2026-10-30，热修）。
        //   基于什么：v2.2.62 发出后华为真机（1080x2440）截图反馈「顶端的又看不见了」——状态栏下面
        //     直接是会话标题行，汉堡键/「DSH伴侣」/刷新按钮整行消失。
        //   根因：v2.2.62 给 M3 TopAppBar 硬套 Modifier.height(46.dp)，而 TopAppBar 自己仍按
        //     statusBars inset 内缩；vivo V2080A 状态栏矮（约 24dp）时内容行还剩 ~22dp 勉强渲染，
        //     华为状态栏更高，46dp 总高减去 inset 后内容行被压没，整行不显示。硬高度与 inset 是
        //     两套布局机制，叠加必然在某种状态栏高度下翻车。
        //   干了什么：ui/App.kt 会话详情页弃用 M3 TopAppBar，改用自建 Row：
        //     windowInsetsPadding(WindowInsets.statusBars) 自己消费状态栏 inset，内容行固定 48dp
        //     （IconButton 最小触控高），总高 = 各机型状态栏 + 48dp，任何机型内容都不会被裁；
        //     列表页与其他分区仍用默认 TopAppBar 不变。
        //   测了什么：编译通过；无新单测（纯布局）。真机 V2080A 覆盖安装强杀重启后 dump：
        //     应用栏 打开导航[36,120]+DSH伴侣[156,125]+刷新[972,120] 整行健在，其下会话标题 y270，
        //     两栏间距收紧且不再重叠；状态行四字完整。华为端由用户复验。
        //
        // v2.2.64（versionCode 10089）：**团队成员/子会话可点开查看 + 模型弹窗按供应商折叠**（2026-10-31，次版本）。
        //   基于什么：用户三条真机反馈——①「智能团队的成员、任务完成情况我无法自己查看」；
        //     ②「团队面板中的子会话无法查看」；③「会话页菜单中的模型更换点击反应较慢，另外模型需按照
        //     供应商收缩模式，点击那个厂家，那个展开」。
        //   干了什么：
        //     ① 子会话可查看：团队面板里每张子会话卡加整卡可点 + 行尾右箭头，点开弹「成员的对话」
        //        只读 Dialog（新建 ui/chat/SubagentDialog.kt）。数据走桥新增的只读窄口
        //        GET /chat/sessions/{lead}/subagents/{sub}/messages（新建 data/DshClientSubagents.kt，
        //        拆文件是因为直接加进 DshClient.kt 会把它顶到 822 行、超 AGENTS.md §4.2 的 800 行预算）。
        //        刻意不复用 ChatItem 渲染：那个带图片/重试/分叉/审批回调，在只读窗口里全是无效入口。
        //     ② 模型弹窗按供应商折叠（新建 ui/chat/ModelSheetGroups.kt）：默认只展开当前选中模型所在
        //        的供应商，点折叠头展开/收起，箭头 rotate 0°→180° 动效，0 模型的供应商禁用点击，
        //        收起态折叠头带勾与「当前」标记。折叠状态用 remember {} + LaunchedEffect(currentProvider)
        //        而非 remember(groups) —— 否则每次重拉 sessionModels 都会把用户手动展开的供应商悄悄收起。
        //        展开用 if (expanded) { items(...) } 而非 AnimatedVisibility —— 后者在 LazyColumn 里
        //        会破坏 item key 复用。
        //     ③ 点击响应加速：ModelSheet 内加 pending 乐观态，点选立刻高亮不等网络，行尾 14dp 转圈，
        //        服务端确认后清空，同目标重复点击吞掉，6 秒超时兜底（不用全局 busy，怕误伤别的操作）。
        //   测了什么：DshClientTest 增 requestsSubagentHistoryUnderLeadSessionPath（钉住两个 id 都得
        //     进路径、query 正确）；strings 中英各 +12 键（subagent_* 7 + model_group_* 5）；
        //     单测 88 类 / 876 用例全绿；docsCheck（STRUCTURE 165 锚点 / 145 源文件双向一致）、
        //     lintDebug 0 error、assembleDebug 全绿。
        //   真机验了什么：V2080A（1080x2316）强制停止重启并核对 base.apk SHA 与本地一致后，
        //     团队面板点 e083dffc… 子会话卡片 → 完整显示该子智能体当初写的 7 节调研报告原文，
        //     底部「只读」提示正确；模型弹窗 8 家供应商折叠正常（WorkBuddy 带「当前」默认展开），
        //     点 DeepSeek 头展开出 DeepSeek-V4.1-Flash / DeepSeek-V4-Pro，点选立即高亮。
        //   依赖：需要 dsh-remote-bridge >= 2.9.0（能力位 subagent-history-v1）。旧桥会走
        //     ROUTE_NOT_FOUND → 映射成 PLUGIN_TOO_OLD，界面提示「桥版本太旧」而非报原始错误码。
        //
        // v2.2.65（versionCode 10090）：**抽屉显示真实设备 + 行数守卫覆盖 assets**（2026-10-31，补丁版）。
        //   改了什么：
        //     #7 抽屉顶部显示 `HarmonyOS · ICL-AL10` 但机器是 vivo V2080A。根因不是 bug：
        //       桥侧那条设备记录 model 早已是 V2080A，name 停在配对时声明的旧值，而
        //       「声明过的名字不再自动覆盖」是刻意的。改法取 issue 的方案 1 ——
        //       名字与桥回传 model 不一致时补一行「当前设备 · Android · V2080A」。
        //     #4（一半）interact.js 实际 1059 行、台账记着 803。根因：台账 2026-10-18 就写着
        //       「登记范围扩大到 assets/chat/*.js」，但 ArchitectureGuardTest 只扫
        //       app/src/main/java，assets/ 全程无人检查，数字漂了 256 行没人发现。
        //       守卫新增第二个扫描面，台账数值同步修正。
        //   真机验了什么：V2080A 装 debug 包（sha256 与本地产物逐字节一致），
        //     抽屉显示两行 —— `HarmonyOS · ICL-AL10` + `当前设备 · Android · V2080A`。
        //   仍未做：interact.js 本身没拆，仍 1059 行（#4 的另一半）。
        //   依赖：无（纯客户端改动，不需要新桥能力位）。
        //
        versionCode = 10090
        versionName = "2.2.65"
        // 构建水印：git 短哈希 + 构建时刻，显示在设置页「关于」的版本行下面。
        // 为什么需要：真机反馈「改了没生效」时，唯一能一锤定音的办法是让用户看到**他装的
        // 到底是哪一次构建** —— 版本号在多次重打之间不变，哈希每次构建都不同。
        val buildStamp: String = try {
            val git = providers.exec {
                commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
            if (git.isBlank()) "nogit" else git
        } catch (_: Exception) {
            "nogit"
        }
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        // 只保留 git 哈希：它在多次重打之间必然不同，足够一锤定音；
        // 时间戳需要 android.text / java.time，在 defaultConfig 里都解析不到，不值当为它引依赖。
        buildConfigField("String", "BUILD_TIME", "\"\"")

        // ── 诊断日志总开关（debug/DiagLog.kt）──────────────────────────────
        // 基于什么：`docs/project/PITFALLS.md` 实测过 vivo 真机 logcat 抓不到
        // 自有 App 的 `Log.i`，上一轮只能把探针数字渲染到屏幕上取证、用完就删。
        // 本轮要做会话页两条实现路径（原生 ChatDetail vs WebView ChatWebScreen）
        // 与窗口/屏幕变化的持续观察，把那条一次性探针固化成三通道日志
        // （logcat / filesDir/diag.log / 屏上浮层）。
        // 为什么放 defaultConfig：debug 与 release 的默认值都从这里取，
        // release 包 `DIAG_LOG=false` → 三个通道全静默、零行为变化；
        // 要临时在 release 上开，改 buildTypes 里这一行即可，不碰代码。
        buildConfigField("boolean", "DIAG_LOG", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (signingValues.isNotEmpty()) {
            create("dedicated") {
                storeFile = file(signingValues.getProperty("storeFile"))
                storePassword = signingValues.getProperty("storePassword")
                keyAlias = signingValues.getProperty("keyAlias")
                keyPassword = signingValues.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("dedicated")
            // 诊断日志三通道在 debug 包全开：logcat / filesDir/diag.log / 屏上浮层。
            // release 保持 defaultConfig 的 false（见上方 DIAG_LOG 注释）。
            // 注意：同名的 buildConfigField 会覆盖 defaultConfig 的值，这正是要的效果 ——
            // 想在哪一类包里开日志，就改哪一处，不用动代码。
            buildConfigField("boolean", "DIAG_LOG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("dedicated")
        }
    }

    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

val verifyDedicatedReleaseSigning = tasks.register("verifyDedicatedReleaseSigning") {
    group = "verification"
    description = "Requires the project-specific signing key before packaging a release."
    inputs.property("dedicatedSigningConfigured", hasDedicatedSigning)
    doLast {
        check(inputs.properties["dedicatedSigningConfigured"] == true) {
            "Dedicated signing config is required. Set dshSigningProperties."
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn(verifyDedicatedReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
