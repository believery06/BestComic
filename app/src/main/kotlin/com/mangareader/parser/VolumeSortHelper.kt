package com.mangareader.parser

import java.util.Locale
import java.util.regex.Pattern

/**
 * 漫画分卷智能排序器。
 *
 * 支持解析以下杂乱命名并升序排列：
 * - 纯数字卷：第1卷、卷01、卷10、Vol.5、vol 12、VOL_03
 * - 章节混合：第1话、话07、Chap.9、Ch15、第32话
 * - 带前缀后缀：漫画名_卷05.zip、漫画名Vol10.cbz、漫画名_第8卷完整版.pdf
 * - 多段数字：XX_vol3_chap2.zip，优先按卷号主排序，再按章节号次排序
 * - 番外/特别篇/外传/OVA/短篇统一放在正传之后
 * - 无数字的杂项文件排在列表最末尾
 *
 * 比较规则：
 * 1. 正传数字卷号升序
 * 2. 同卷内章节号升序
 * 3. 番外等特殊篇全部在正传之后（内部仍按卷号/章节号升序）
 * 4. 无数字文件排在最后
 */
object VolumeSortHelper {

    private val EXTENSIONS = setOf("cbz", "cbr", "pdf", "zip", "rar", "epub", "7z", "tar", "mobi", "azw", "azw3")

    // 卷关键词：第X卷、卷X、Vol.X、VOL X、vol_X 等
    private val VOLUME_PATTERNS = listOf(
        // 中文：第1卷、卷01、第 1 卷
        Pattern.compile("(?:第\\s*)?(卷)\\s*([0-9]+)", Pattern.CASE_INSENSITIVE),
        // 英文：Vol.5、vol 12、VOL_03、volume 1
        Pattern.compile("(?:vol(?:ume)?)[_.\\s-]*([0-9]+)", Pattern.CASE_INSENSITIVE),
        // 简写：v01、v1（需避免匹配到其他单词，仅匹配独立 v）
        Pattern.compile("(?<![a-z])v\\s*([0-9]+)(?![a-z])", Pattern.CASE_INSENSITIVE)
    )

    // 章节关键词：第X话、话07、Chap.9、Ch15、Chapter 3、第32话
    private val CHAPTER_PATTERNS = listOf(
        Pattern.compile("(?:第\\s*)?(话|話)\\s*([0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:ch(?:ap(?:ter)?)?)[_.\\s-]*([0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:第\\s*)?([0-9]+)\\s*(话|話)", Pattern.CASE_INSENSITIVE)
    )

    // 特殊后置关键词
    private val SPECIAL_MARKERS = listOf("番外", "外传", "外伝", "特别篇", "特別篇", "短篇", "ova", "special", "extra", "bonus", "side story", "ss")

    // 在提取关键词前先剥离的前缀/后缀噪声（如【xxx】、[xxx]、（xxx）中带广告/网站名的部分）
    private val NOISE_BRACKETS = listOf(
        Pattern.compile("【[^】]*】"),
        Pattern.compile("\\[[^]]*]"),
        Pattern.compile("（[^）]*）"),
        Pattern.compile("\\([^)]*\\)")
    )

    /**
     * 对文件名列表进行分卷智能排序，返回新的有序列表。
     */
    fun sortNames(names: List<String>): List<String> {
        return names.sortedWith { a, b -> compareNames(a, b) }
    }

    /**
     * 比较两个文件名在分卷语义下的顺序。
     */
    fun compareNames(a: String, b: String): Int {
        val keyA = extractSortKey(a)
        val keyB = extractSortKey(b)
        return keyA.compareTo(keyB)
    }

    /**
     * 提取排序键。
     */
    private fun extractSortKey(name: String): SortKey {
        val clean = removeNoise(removeExtension(name)).lowercase(Locale.getDefault())
        return try {
            val volume = extractVolume(clean)
            val chapter = extractChapter(clean)
            val isSpecial = SPECIAL_MARKERS.any { clean.contains(it, ignoreCase = true) }
            SortKey(
                isSpecial = isSpecial,
                volume = volume,
                chapter = chapter,
                hasNumber = volume != null || chapter != null || extractAnyNumber(clean) != null,
                fallbackText = clean
            )
        } catch (_: Throwable) {
            // 任何解析异常都不崩溃，按无数字杂项处理
            SortKey(isSpecial = false, volume = null, chapter = null, hasNumber = false, fallbackText = clean)
        }
    }

    /**
     * 移除已知文件后缀，不参与排序识别。
     */
    private fun removeExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx <= 0) return name
        val ext = name.substring(idx + 1).lowercase(Locale.getDefault())
        return if (ext in EXTENSIONS) name.substring(0, idx) else name
    }

    /**
     * 移除常见的中文/英文噪声括号前缀/后缀（如微信公众号、网站水印），卷号/章节号位于其中的内容保留在 removeNoiseInner。
     */
    private fun removeNoise(name: String): String {
        var result = name
        for (pattern in NOISE_BRACKETS) {
            result = pattern.matcher(result).replaceAll("")
        }
        return result
    }

    /**
     * 提取卷号数字，优先级：卷/Vol > 任意卷相关标记。
     * 多段数字时优先取最靠前的卷关键词匹配；括号内的数字噪声已被 removeNoise 剥离。
     */
    private fun extractVolume(clean: String): Int? {
        for (pattern in VOLUME_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.find()) {
                // 对于 "第1卷" 模式，数字在 group(2)；对于 vol/chapter 模式，数字在 group(1)
                val numStr = matcher.group(2) ?: matcher.group(1)
                numStr?.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 提取章节号数字。
     */
    private fun extractChapter(clean: String): Int? {
        for (pattern in CHAPTER_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.find()) {
                val numStr = matcher.group(2) ?: matcher.group(1)
                numStr?.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 提取任意数字（用于无关键词但带数字的文件做兜底排序）。
     */
    private fun extractAnyNumber(clean: String): Int? {
        val matcher = Pattern.compile("[0-9]+").matcher(clean)
        return if (matcher.find()) matcher.group().toIntOrNull() else null
    }

    /**
     * 排序键数据结构。
     */
    private data class SortKey(
        val isSpecial: Boolean,
        val volume: Int?,
        val chapter: Int?,
        val hasNumber: Boolean,
        val fallbackText: String
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            // 权重1：无数字文件排最后
            if (!this.hasNumber && !other.hasNumber) {
                return this.fallbackText.compareTo(other.fallbackText, ignoreCase = true)
            }
            if (!this.hasNumber) return 1
            if (!other.hasNumber) return -1

            // 权重2：番外等特殊篇在正传之后
            if (this.isSpecial && other.isSpecial) {
                // 都特殊：先按卷号，再按章节号，最后自然文本
                return compareSpecial(other)
            }
            if (this.isSpecial) return 1
            if (other.isSpecial) return -1

            // 权重3：正传按卷号升序；未匹配到卷/话关键词时，使用文件名中的第一个数字兜底
            val volA = this.volume ?: extractAnyNumber(this.fallbackText) ?: 0
            val volB = other.volume ?: extractAnyNumber(other.fallbackText) ?: 0
            if (volA != volB) return volA.compareTo(volB)

            // 权重4：同卷按章节号升序
            val chapA = this.chapter ?: Int.MAX_VALUE
            val chapB = other.chapter ?: Int.MAX_VALUE
            if (chapA != chapB) return chapA.compareTo(chapB)

            // 兜底：自然文本排序
            return this.fallbackText.compareTo(other.fallbackText, ignoreCase = true)
        }

        private fun compareSpecial(other: SortKey): Int {
            val volA = this.volume ?: extractAnyNumber(this.fallbackText) ?: 0
            val volB = other.volume ?: extractAnyNumber(other.fallbackText) ?: 0
            if (volA != volB) return volA.compareTo(volB)

            val chapA = this.chapter ?: Int.MAX_VALUE
            val chapB = other.chapter ?: Int.MAX_VALUE
            if (chapA != chapB) return chapA.compareTo(chapB)

            return this.fallbackText.compareTo(other.fallbackText, ignoreCase = true)
        }
    }

    /**
     * 便捷方法：判断文件名是否包含番外/特别篇等后置标记。
     */
    fun isSpecialChapter(name: String): Boolean {
        val clean = removeNoise(removeExtension(name)).lowercase(Locale.getDefault())
        return SPECIAL_MARKERS.any { clean.contains(it, ignoreCase = true) }
    }
}