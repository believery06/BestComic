package com.mangareader.parser.mobi

/**
 * MOBI HTML 内容解析器。
 *
 * 将 MOBI 解压后的 HTML 内容解析为结构化的页面列表。
 * MOBI HTML 是 HTML 子集 + Kindle 自定义标签：
 * - `<mbp:pagebreak/>` — 强制分页
 * - `<img recindex="NNN">` — 引用第 NNN 号图片 record
 * - `<a filepos=NNNNN>` — 内部链接
 * - 标准 HTML: `<p>`, `<h1>`-`<h6>`, `<b>`, `<i>`, `<font>`, `<br>` 等
 *
 * 解析流程：
 * 1. 清理 HTML（移除 script/style，处理实体）
 * 2. 按 `<mbp:pagebreak/>` 和标题标签分割为逻辑块
 * 3. 将逻辑块拆分为适合屏幕大小的页面
 * 4. 提取 TOC（目录结构）
 */
class MobiHtmlParser {

    companion object {

        /** 每页最大字符数（粗略估算，后续由渲染器精确分页）。 */
        private const val MAX_CHARS_PER_PAGE = 2000

        /** 标题标签的模式。 */
        private val HEADING_PATTERN = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", RegexOption.DOT_MATCHES_ALL)

        /** mbp:pagebreak 标签。 */
        private val PAGEBREAK_PATTERN = Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)

        /** img recindex 引用。 */
        private val IMG_RECINDEX_PATTERN = Regex(
            "<img[^>]*recindex=[\"']?(\\d+)[\"']?[^>]*>",
            RegexOption.IGNORE_CASE
        )

        /** 普通 img src 引用。 */
        private val IMG_SRC_PATTERN = Regex(
            "<img[^>]*src=[\"']([^\"']*)[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        )

        /** HTML 实体映射。 */
        private val HTML_ENTITIES = mapOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
            "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
            "&lsquo;" to "\u2018", "&rsquo;" to "\u2019",
            "&ldquo;" to "\u201C", "&rdquo;" to "\u201D",
            "&laquo;" to "«", "&raquo;" to "»",
            "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
            "&deg;" to "°", "&plusmn;" to "±", "&times;" to "×",
            "&divide;" to "÷", "&frac12;" to "½", "&frac14;" to "¼",
            "&eacute;" to "é", "&egrave;" to "è", "&agrave;" to "à",
            "&ccedil;" to "ç", "&uuml;" to "ü", "&ouml;" to "ö",
            "&auml;" to "ä", "&szlig;" to "ß", "&ntilde;" to "ñ"
        )
    }

    /** 内容块类型。 */
    enum class BlockType {
        HEADING,        // 标题
        PARAGRAPH,      // 段落
        IMAGE,          // 图片引用
        PAGE_BREAK,     // 强制分页
        EMPTY           // 空行/分隔
    }

    /** 结构化的内容块。 */
    data class ContentBlock(
        val type: BlockType,
        val text: String = "",
        val level: Int = 0,          // 标题级别 (1-6)
        val imageIndex: Int = -1,    // 图片 record 索引
        val imagePath: String = ""   // 图片路径（EPUB 风格）
    )

    /** 解析后的页面。 */
    data class ParsedPage(
        val blocks: List<ContentBlock>,
        val plainText: String,       // 纯文本（用于 TTS）
        val hasImages: Boolean,
        val imageIndices: List<Int>  // 该页引用的图片 record 索引
    )

    /** 目录条目。 */
    data class TocEntry(
        val title: String,
        val level: Int,
        val pageIndex: Int
    )

    /** 完整的解析结果。 */
    data class ParseResult(
        val pages: List<ParsedPage>,
        val toc: List<TocEntry>,
        val title: String,
        val author: String
    )

    /**
     * 解析 MOBI HTML 内容为结构化页面。
     *
     * @param html 解压后的完整 HTML 字符串
     * @param title 书名（来自 EXTH/fullName）
     * @param author 作者（来自 EXTH）
     * @return 解析结果
     */
    fun parse(html: String, title: String = "", author: String = ""): ParseResult {
        if (html.isBlank()) {
            return ParseResult(emptyList(), emptyList(), title, author)
        }

        // 1. 清理 HTML（只移除 script/style，不做实体解码——避免解码后的 < 被误认为标签）
        val cleaned = cleanHtml(html)

        // 2. 分割为逻辑块（在此阶段解码实体，因为块内已移除标签）
        val blocks = splitIntoBlocks(cleaned)

        // 3. 将块组合为页面
        val pages = composePages(blocks)

        // 4. 提取 TOC
        val toc = extractToc(pages)

        return ParseResult(pages, toc, title, author)
    }

    // ── HTML 清理 ────────────────────────────────────────────────────────

    /**
     * 清理 HTML：只移除 script/style 等非内容标签，**不做实体解码**。
     * 实体解码在 stripTags 之后进行，避免 `&lt;` 解码为 `<` 后被误认为标签删除。
     */
    private fun cleanHtml(html: String): String {
        var s = html

        // 移除 script 和 style 标签及其内容
        s = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("<link[^>]*>", RegexOption.IGNORE_CASE).replace(s, "")

        // 移除 XML 声明和 DOCTYPE
        s = Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOT_MATCHES_ALL).replace(s, "$1")

        return s
    }

    // ── 块分割 ───────────────────────────────────────────────────────────

    /**
     * 将清理后的 HTML 分割为内容块。
     * 保留结构信息（标题级别、图片引用、分页标记）。
     */
    private fun splitIntoBlocks(html: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()

        // 先用 mbp:pagebreak 分割为逻辑区域
        val sections = PAGEBREAK_PATTERN.split(html)

        for ((index, section) in sections.withIndex()) {
            // 在 section 之间插入 PAGE_BREAK 块
            if (index > 0) {
                blocks.add(ContentBlock(BlockType.PAGE_BREAK))
            }

            if (section.isBlank()) continue
            parseSection(section, blocks)
        }

        return blocks
    }

    /**
     * 解析一个逻辑区域（两个 pagebreak 之间的内容）。
     */
    private fun parseSection(section: String, blocks: MutableList<ContentBlock>) {
        // 提取标题
        var remaining = section
        val headingMatches = HEADING_PATTERN.findAll(section).toList()

        if (headingMatches.isNotEmpty()) {
            var lastEnd = 0
            for (match in headingMatches) {
                // 标题前的内容
                val before = section.substring(lastEnd, match.range.first)
                if (before.isNotBlank()) {
                    parseTextContent(before, blocks)
                }

                // 标题本身
                val headingText = stripTags(match.groupValues[1]).trim()
                if (headingText.isNotEmpty()) {
                    val level = extractHeadingLevel(match.value)
                    blocks.add(ContentBlock(BlockType.HEADING, headingText, level = level))
                }

                lastEnd = match.range.last + 1
            }

            // 剩余内容
            remaining = section.substring(lastEnd)
        }

        // 解析剩余内容
        if (remaining.isNotBlank()) {
            parseTextContent(remaining, blocks)
        }
    }

    /**
     * 解析文本内容，提取段落和图片引用。
     */
    private fun parseTextContent(text: String, blocks: MutableList<ContentBlock>) {
        // 提取图片引用
        val imgMatches = IMG_RECINDEX_PATTERN.findAll(text).toList()

        if (imgMatches.isNotEmpty()) {
            var lastEnd = 0
            for (match in imgMatches) {
                // 图片前的文本
                val before = text.substring(lastEnd, match.range.first)
                if (before.isNotBlank()) {
                    val paragraphs = splitParagraphs(before)
                    for (para in paragraphs) {
                        if (para.isNotBlank()) {
                            blocks.add(ContentBlock(BlockType.PARAGRAPH, para.trim()))
                        }
                    }
                }

                // 图片引用
                val recIndex = match.groupValues[1].toIntOrNull() ?: -1
                if (recIndex >= 0) {
                    blocks.add(ContentBlock(BlockType.IMAGE, imageIndex = recIndex))
                }

                lastEnd = match.range.last + 1
            }

            // 剩余文本
            val remaining = text.substring(lastEnd)
            if (remaining.isNotBlank()) {
                val paragraphs = splitParagraphs(remaining)
                for (para in paragraphs) {
                    if (para.isNotBlank()) {
                        blocks.add(ContentBlock(BlockType.PARAGRAPH, para.trim()))
                    }
                }
            }
        } else {
            // 无图片引用，纯文本
            val paragraphs = splitParagraphs(text)
            for (para in paragraphs) {
                if (para.isNotBlank()) {
                    blocks.add(ContentBlock(BlockType.PARAGRAPH, para.trim()))
                }
            }
        }
    }

    /**
     * 将 HTML 文本分割为段落。
     * 按 <p>、<br>、<div> 等块级标签分割。
     */
    private fun splitParagraphs(text: String): List<String> {
        // 先按块级标签分割
        val blockPattern = Regex(
            "</?(?:p|div|blockquote|li|ul|ol|table|tr|td|th|pre|section|article|header|footer|nav|aside)[^>]*>",
            RegexOption.IGNORE_CASE
        )

        val parts = blockPattern.split(text)
        val paragraphs = mutableListOf<String>()

        for (part in parts) {
            // 按 <br> 和 <br/> 进一步分割
            val lines = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).split(part)
            for (line in lines) {
                val stripped = stripTags(line).trim()
                if (stripped.isNotEmpty()) {
                    paragraphs.add(stripped)
                }
            }
        }

        // 如果分割后没有段落，尝试按换行分割
        if (paragraphs.isEmpty()) {
            val stripped = stripTags(text).trim()
            if (stripped.isNotEmpty()) {
                // 按双换行或单换行分割
                val byNewline = stripped.split(Regex("\\n\\s*\\n|\\n"))
                for (line in byNewline) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        paragraphs.add(trimmed)
                    }
                }
            }
        }

        return paragraphs
    }

    // ── 页面组合 ─────────────────────────────────────────────────────────

    /**
     * 将内容块组合为页面。
     * 考虑文本长度和图片，确保每页内容适中。
     */
    private fun composePages(blocks: List<ContentBlock>): List<ParsedPage> {
        if (blocks.isEmpty()) return emptyList()

        val pages = mutableListOf<ParsedPage>()
        var currentBlocks = mutableListOf<ContentBlock>()
        var currentText = StringBuilder()
        var currentImages = mutableListOf<Int>()

        fun flushPage() {
            if (currentBlocks.isEmpty()) return
            val text = currentText.toString().trim()
            if (text.isEmpty() && currentImages.isEmpty()) {
                currentBlocks.clear()
                return
            }
            pages.add(
                ParsedPage(
                    blocks = currentBlocks.toList(),
                    plainText = text,
                    hasImages = currentImages.isNotEmpty(),
                    imageIndices = currentImages.toList()
                )
            )
            currentBlocks = mutableListOf()
            currentText = StringBuilder()
            currentImages = mutableListOf()
        }

        for (block in blocks) {
            when (block.type) {
                BlockType.PAGE_BREAK -> {
                    flushPage()
                }
                BlockType.HEADING -> {
                    // 标题前结束当前页
                    flushPage()
                    currentBlocks.add(block)
                    currentText.append(block.text).append("\n\n")
                }
                BlockType.IMAGE -> {
                    // 图片独占一页
                    flushPage()
                    currentBlocks.add(block)
                    currentImages.add(block.imageIndex)
                    flushPage()
                }
                BlockType.PARAGRAPH -> {
                    // 检查是否需要分页
                    val estimatedLength = currentText.length + block.text.length + 2
                    if (estimatedLength > MAX_CHARS_PER_PAGE && currentText.isNotEmpty()) {
                        flushPage()
                    }

                    currentBlocks.add(block)
                    currentText.append(block.text).append("\n\n")
                }
                BlockType.EMPTY -> {
                    // 忽略空块
                }
            }
        }

        flushPage()

        // 如果没有生成任何页面但存在文本块，创建一个兜底页
        if (pages.isEmpty() && blocks.isNotEmpty()) {
            val allText = blocks.filter { it.text.isNotBlank() }
                .joinToString("\n\n") { it.text }
            if (allText.isNotBlank()) {
                pages.add(
                    ParsedPage(
                        blocks = blocks,
                        plainText = allText,
                        hasImages = blocks.any { it.type == BlockType.IMAGE },
                        imageIndices = blocks.filter { it.type == BlockType.IMAGE }.map { it.imageIndex }
                    )
                )
            }
        }

        return pages
    }

    // ── TOC 提取 ─────────────────────────────────────────────────────────

    /**
     * 从页面列表中提取目录结构。
     */
    private fun extractToc(pages: List<ParsedPage>): List<TocEntry> {
        val toc = mutableListOf<TocEntry>()

        for ((pageIndex, page) in pages.withIndex()) {
            for (block in page.blocks) {
                if (block.type == BlockType.HEADING && block.level <= 3) {
                    toc.add(TocEntry(
                        title = block.text,
                        level = block.level,
                        pageIndex = pageIndex
                    ))
                }
            }
        }

        return toc
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    /**
     * 移除 HTML 标签并解码实体，保留纯文本。
     * 先移除标签，再解码实体，避免 `&lt;` 解码为 `<` 后被误认为标签。
     */
    private fun stripTags(html: String): String {
        // 1. 移除所有标签
        var s = Regex("<[^>]+>").replace(html, "")

        // 2. 解码 HTML 实体
        for ((entity, replacement) in HTML_ENTITIES) {
            s = s.replace(entity, replacement)
        }
        // 数字实体
        s = Regex("&#(\\d+);").replace(s) { match ->
            try {
                val code = match.groupValues[1].toInt()
                if (code in 32..0x10FFFF) code.toChar().toString() else " "
            } catch (_: Exception) { " " }
        }

        return s
    }

    /**
     * 从标题标签提取级别。
     */
    private fun extractHeadingLevel(headingTag: String): Int {
        val match = Regex("<h([1-6])", RegexOption.IGNORE_CASE).find(headingTag)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    /**
     * 从 HTML 中提取所有图片 recindex 值。
     */
    fun extractImageIndices(html: String): List<Int> {
        return IMG_RECINDEX_PATTERN.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
    }

    /**
     * 提取纯文本内容（用于 TTS）。
     * 比 stripTags 更彻底，保留段落分隔。
     */
    fun extractPlainText(html: String): String {
        var s = html

        // 移除 script/style
        s = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(s, "")

        // 将块级标签替换为换行
        s = Regex("</?(?:p|div|h[1-6]|blockquote|li|br|tr)[^>]*>", RegexOption.IGNORE_CASE)
            .replace(s, "\n")

        // 移除所有标签
        s = Regex("<[^>]+>").replace(s, "")

        // 解码实体（在标签移除之后）
        for ((entity, replacement) in HTML_ENTITIES) {
            s = s.replace(entity, replacement)
        }
        s = Regex("&#(\\d+);").replace(s) { match ->
            try {
                val code = match.groupValues[1].toInt()
                if (code in 32..0x10FFFF) code.toChar().toString() else " "
            } catch (_: Exception) { " " }
        }

        // 合并空白
        s = Regex("[ \\t]+").replace(s, " ")
        s = Regex("\\n\\s*\\n+").replace(s, "\n\n")

        return s.trim()
    }
}
