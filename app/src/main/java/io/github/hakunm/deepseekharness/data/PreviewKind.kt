package io.github.hakunm.deepseekharness.data

/**
 * 会话文件预览该怎么显示。
 *
 * 早先所有文件都走同一条路：把内容塞进等宽 `Text` 里。于是 `.md` 的 `# 标题`、
 * `**粗体**` 全是原样字符，`.html` 更惨，满屏尖括号 —— 用户看到的是源码，
 * 不是文件。网页版会渲染 Markdown，App 却不会：这是**偷懒**，不是设计。
 *
 * 这里把「按什么渲染」抽成纯逻辑，好让分派规则能被 JVM 单测钉住：
 * 判断依据全是文件名与 contentType，不涉及任何 UI。
 */
enum class PreviewKind {
    /** 渲染成 Markdown（标题、列表、表格、粗体）。 */
    MARKDOWN,

    /**
     * 结构化预览 HTML。
     *
     * 刻意**不**用 WebView 直接渲染：WebView 能跑任意 JS 与网络请求，而这里的
     * 内容来自会话里的任意文件 —— 那等于让一份不可信的文件在手机上拿到执行权。
     * 所以只提取结构（标签、标题、段落、链接、表格），丢掉脚本与样式。
     */
    HTML,

    /** 源码视图：等宽字体，不做高亮但保留缩进与换行。 */
    SOURCE,

    /** 二进制：不该按文本渲染。 */
    BINARY,
}

/** 二进制判定用：这些扩展名一律不走文本预览。 */
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "avif",
    "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war",
    "mp3", "wav", "flac", "ogg", "m4a", "aac",
    "mp4", "mov", "avi", "mkv", "webm",
    "so", "dylib", "dll", "exe", "bin", "class", "apk", "aab",
    "woff", "woff2", "ttf", "otf", "eot",
    "db", "sqlite", "sqlite3", "wasm",
)

/** Markdown 的扩展名（含常见的文档变体）。 */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mdx")

/** 直接当纯文本读的扩展名 —— 保留换行但不做 Markdown 解释。 */
private val PLAIN_TEXT_EXTENSIONS = setOf(
    "txt", "log", "csv", "tsv", "json", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "env", "properties", "xml", "properties",
)

private val SOURCE_EXTENSIONS = setOf(
    "kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "py", "rb", "go",
    "rs", "c", "h", "cc", "cpp", "hpp", "cs", "swift", "php", "pl", "lua", "sh",
    "bash", "zsh", "fish", "ps1", "sql", "r", "m", "mm", "scala", "dart", "ex", "exs",
    "gradle", "cmake", "make", "dockerfile", "vue", "svelte", "css", "scss", "less",
    "graphql", "proto", "tf", "tfvars", "patch", "diff",
)

/**
 * 决定一个文件用哪种方式预览。
 *
 * 判据顺序是刻意的：
 * 1. **扩展名优先**。用户点开 `.md` 就是想看 Markdown，不该被 `contentType` 带走
 *    —— 浏览器给的 MIME 在本地机器上五花八门（`text/x-markdown`、`text/plain`、
 *    空串都见过），拿它当主判据会得到「有时渲染有时不渲染」的最坏体验。
 * 2. 扩展名不认识时**退回 contentType**，这样服务端返回 `text/html` 的无名文件
 *    至少不会被当成源码。
 * 3. 二进制**永远**不走文本预览，不管 MIME 说什么 ——
 *    `application/octet-stream` 配一个 `.png` 的文件是常态，
 *    而把二进制当 UTF-8 解出来只会得到一堆替换字符。
 *
 * @param fileName 完整文件名或路径；取最后一段的扩展名，**大小写不敏感**。
 * @param contentType 服务端给的 MIME，可为空。
 */
fun previewKind(fileName: String, contentType: String? = null): PreviewKind {
    val extension = extensionOf(fileName)
    if (extension.isEmpty()) return byContentType(contentType)
    if (extension in BINARY_EXTENSIONS) return PreviewKind.BINARY
    if (extension in MARKDOWN_EXTENSIONS) return PreviewKind.MARKDOWN
    if (extension == "html" || extension == "htm" || extension == "xhtml") return PreviewKind.HTML
    if (extension in PLAIN_TEXT_EXTENSIONS) return PreviewKind.SOURCE
    if (extension in SOURCE_EXTENSIONS) return PreviewKind.SOURCE
    return byContentType(contentType)
}

/**
 * 取扩展名（小写，不含点）。
 *
 * 刻意**不**用 `substringAfterLast('/', "")` + `substringAfterLast('.', "")` 那串链：
 * 后者在缺少分隔符时返回的是「整个原串」而不是传入的默认值，于是
 * `README.md` 这种**不含 `/` 的裸文件名**会被当成「扩展名 = README.md」，
 * 一个都匹配不上，静默退化成源码预览 —— 而这正是用户看到的现象。
 *
 * 这里用「最后一个点必须在最后一个斜杠之后」这个显式判据，没有歧义。
 */
private fun extensionOf(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    // 点在开头（`.gitignore`）不算扩展名；没有点也不算。
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
}

private fun byContentType(contentType: String?): PreviewKind {
    val type = contentType?.trim()?.lowercase().orEmpty()
    if (type.isEmpty()) return PreviewKind.SOURCE
    if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")) {
        return PreviewKind.BINARY
    }
    if (type.startsWith("text/")) return PreviewKind.SOURCE
    if (type == "application/json" || type == "application/xml" || type == "application/yaml") {
        return PreviewKind.SOURCE
    }
    // application/pdf、application/octet-stream 等一律当二进制：按文本画出来只会是乱码。
    return PreviewKind.BINARY
}
