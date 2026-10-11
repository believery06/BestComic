package com.mangareader.parser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.mangareader.R
import com.mangareader.data.ComicPage
import com.mangareader.data.ComicType
import com.mangareader.parser.mobi.MobiHeaderParser
import com.mangareader.parser.mobi.HuffCdicDecoder
import com.mangareader.parser.mobi.MobiHtmlParser
import com.mangareader.parser.mobi.PalmDocDecoder
import com.mangareader.parser.mobi.TextPageRenderer
import com.mangareader.utils.BitmapDecoderUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * MOBI / AZW3 (KF8) 完整解析器。
 *
 * 支持两种 MOBI 内容模式：
 *
 * **漫画模式**（Kindle Comic Creator / 图片型 MOBI）：
 * - 图片 record 是主要内容，每张图片一页
 * - 文本 record 很少（仅元数据/版权页）
 *
 * **电子书模式**（小说/文本型 MOBI）：
 * - 文本 record 是主要内容，经 PalmDOC 压缩存储 HTML
 * - 解压后解析 HTML，分页渲染为 Bitmap
 * - 图片 record 作为插图/封面嵌入
 *
 * 解析流程：
 * 1. 解析 PDB 头部获取所有 record 偏移表
 * 2. 解析 MOBI 头部（含 EXTH 元数据）
 * 3. 检测 KF8 边界（AZW3 双格式），优先使用 KF8 内容
 * 4. 解压文本 records（PalmDOC LZ77）
 * 5. 解析 HTML 为结构化内容块
 * 6. 扫描全部 records 提取图片
 * 7. 智能判断内容类型，生成对应的页面列表
 * 8. 文本页使用 StaticLayout 渲染为 Bitmap
 *
 * @see MobiHeaderParser MOBI 头部解析
 * @see PalmDocDecoder PalmDOC 解压
 * @see MobiHtmlParser HTML 内容解析
 * @see TextPageRenderer 文本页渲染
 */
class MobiParser : ComicParser {

    companion object {
        private const val TAG = "MobiParser"
        /** 单条 record 最大 100MB。 */
        private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024
        /** PDB 头部固定 78 字节。 */
        private const val PDB_HEADER_SIZE = 78
        /** 每个 record info 条目 8 字节。 */
        private const val RECORD_INFO_SIZE = 8
        /** 判定为电子书的最小文本字符数。 */
        private const val MIN_TEXT_CHARS_FOR_EBOOK = 200
        /** 判定为电子书的最少文本页数。 */
        private const val MIN_TEXT_PAGES_FOR_EBOOK = 2
        /** 文本 record 的最大解压大小（防止异常文件）。 */
        private const val MAX_DECOMPRESSED_BYTES = 50L * 1024 * 1024
    }

    private val stateLock = Any()
    private var randomAccessFile: RandomAccessFile? = null
    private var tempFile: File? = null
    private var imageRecords: List<ImageRecordInfo> = emptyList()

    // ── 数据类 ────────────────────────────────────────────────────────────

    private data class ImageRecordInfo(
        val recordIndex: Int,
        val offset: Long,
        val size: Long,
        val imageType: String
    )

    private data class PdbRecordEntry(
        val offset: Long,
        val attributes: Int
    )

    private data class ImageDetection(
        val imageType: String,
        val padding: Int
    )

    // ── ComicParser 接口 ──────────────────────────────────────────────────

    override fun close() {
        synchronized(stateLock) {
            runCatching { randomAccessFile?.close() }
            randomAccessFile = null
            imageRecords = emptyList()
            tempFile?.let { runCatching { it.delete() } }
            tempFile = null
        }
    }

    override suspend fun parse(context: Context, uri: Uri): ParserResult = withContext(Dispatchers.IO) {
        try {
            synchronized(stateLock) {
                close()

                val file = openFile(context, uri)
                val raf = RandomAccessFile(file, "r")
                randomAccessFile = raf

                val fileLength = raf.length()
                if (fileLength < PDB_HEADER_SIZE) {
                    close()
                    return@withContext ParserResult.Error(context.getString(R.string.parser_mobi_invalid))
                }

                // 1. 解析 PDB 头部
                val pdbRecords = parsePdbHeader(raf)
                if (pdbRecords.isEmpty()) {
                    close()
                    return@withContext ParserResult.Error(context.getString(R.string.parser_mobi_invalid))
                }

                val recordSizes = LongArray(pdbRecords.size) { i ->
                    val nextOffset = if (i + 1 < pdbRecords.size) pdbRecords[i + 1].offset else fileLength
                    (nextOffset - pdbRecords[i].offset).coerceAtLeast(0)
                }

                // 2. 解析 MOBI 6 头部
                val headerParser = MobiHeaderParser()
                val mobi6Header = headerParser.parse(raf, pdbRecords[0].offset)

                if (mobi6Header.encryption != MobiHeaderParser.ENCRYPTION_NONE) {
                    close()
                    return@withContext ParserResult.Error(
                        context.getString(R.string.parser_mobi_drm)
                    )
                }

                Log.i(TAG, "MOBI header: compression=${mobi6Header.compression}, " +
                        "textRecords=${mobi6Header.textRecordCount}, " +
                        "firstImage=${mobi6Header.firstImageIndex}, " +
                        "codepage=${mobi6Header.codepage}, " +
                        "kf8Boundary=${mobi6Header.kf8Boundary}, " +
                        "title='${mobi6Header.fullName}'")

                // 3. 检测 KF8，选择最佳内容源
                val kf8Header = if (mobi6Header.kf8Boundary > 0 &&
                    mobi6Header.kf8Boundary < pdbRecords.size
                ) {
                    try {
                        headerParser.parseKf8Header(
                            raf, pdbRecords[mobi6Header.kf8Boundary].offset
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse KF8 header, falling back to MOBI 6", e)
                        null
                    }
                } else null

                // 优先 KF8（更好的格式），否则 MOBI 6
                val activeHeader = kf8Header ?: mobi6Header
                val headerRecordIndex = if (kf8Header != null) mobi6Header.kf8Boundary else 0
                val usingKf8 = kf8Header != null

                Log.i(TAG, "Using ${if (usingKf8) "KF8" else "MOBI 6"} section, " +
                        "headerRecord=$headerRecordIndex")

                // 4. 解压文本 records
                val htmlContent = extractTextContent(
                    raf, pdbRecords, recordSizes,
                    activeHeader, headerRecordIndex
                )

                // 5. 解析 HTML
                val htmlParser = MobiHtmlParser()
                val parseResult = htmlParser.parse(
                    htmlContent,
                    title = activeHeader.fullName,
                    author = activeHeader.author
                )

                Log.i(TAG, "HTML parsed: ${parseResult.pages.size} pages, " +
                        "${parseResult.toc.size} TOC entries")

                // 6. 扫描图片 records
                // MOBI 6 的 firstImageIndex 指向共享图片区起始 record（全局索引）
                // KF8 的图片区从 kf8Boundary 之后开始，firstImageIndex 是相对于 KF8 起始的偏移
                val imageStartIndices = mutableListOf<Int>()
                if (mobi6Header.firstImageIndex > 0 && mobi6Header.firstImageIndex < pdbRecords.size) {
                    imageStartIndices.add(mobi6Header.firstImageIndex)
                }
                if (usingKf8) {
                    // KF8 的 firstImageIndex 是相对于 KF8 section 起始位置的偏移
                    val kf8FirstImg = kf8Header?.firstImageIndex ?: 0
                    if (kf8FirstImg > 0) {
                        val kf8ImageStart = mobi6Header.kf8Boundary + kf8FirstImg
                        if (kf8ImageStart in 1 until pdbRecords.size) {
                            imageStartIndices.add(kf8ImageStart)
                        }
                    }
                    // 如果没有有效的KF8 firstImageIndex，Phase 1的全量magic bytes扫描已覆盖
                }
                val images = findAllImageRecords(
                    raf, pdbRecords, recordSizes,
                    imageStartIndices
                )
                imageRecords = images

                Log.i(TAG, "Found ${images.size} image records " +
                        "(firstImageIndex=${mobi6Header.firstImageIndex})")

                // 7. 智能判断内容类型并生成页面
                val (screenW, screenH) = BitmapDecoderUtils.getScreenSize(context)
                val pages = buildPages(
                    context, parseResult, images,
                    screenW, screenH
                )

                if (pages.isEmpty()) {
                    close()
                    return@withContext ParserResult.Error(
                        context.getString(R.string.parser_mobi_no_images)
                    )
                }

                Log.i(TAG, "MOBI parsed: ${pages.size} total pages")

                ParserResult.Success(pages, ComicType.MOBI)
            }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MOBI parse failed", e)
            close()
            ParserResult.Error(
                context.getString(
                    R.string.parser_mobi_error,
                    e.message ?: context.getString(R.string.parser_unknown_error)
                )
            )
        }
    }

    // ── 文本内容提取 ─────────────────────────────────────────────────────

    /**
     * 从 MOBI/KF8 的文本 records 中解压出完整的 HTML 内容。
     */
    private fun extractTextContent(
        raf: RandomAccessFile,
        pdbRecords: List<PdbRecordEntry>,
        recordSizes: LongArray,
        header: MobiHeaderParser.MobiHeader,
        headerRecordIndex: Int
    ): String {
        // Huff/CDic 压缩（小说/文本型 MOBI 常见）：需要先加载 HUFF + CDIC record，
        // 且文本 record 从 HUFF/CDIC 之后开始。PalmDOC/无压缩无需这些前置 record。
        if (header.compression == PalmDocDecoder.COMPRESSION_HUFF) {
            return extractHuffText(raf, pdbRecords, recordSizes, header, headerRecordIndex)
        }

        val textStart = headerRecordIndex + 1
        val textCount = header.textRecordCount
        val firstImage = header.firstImageIndex

        // 确定文本 record 范围
        // 文本 records 从 headerRecordIndex+1 开始，共 textCount 个
        // 但不能超过 firstImageIndex（图片 record 开始位置）
        val textEnd = if (firstImage > 0 && firstImage < pdbRecords.size) {
            minOf(textStart + textCount, firstImage)
        } else {
            minOf(textStart + textCount, pdbRecords.size)
        }

        if (textStart >= textEnd || textEnd > pdbRecords.size) {
            Log.i(TAG, "No text records to extract (start=$textStart, end=$textEnd)")
            return ""
        }

        Log.i(TAG, "Extracting text records $textStart..${textEnd - 1} (${textEnd - textStart} records)")

        // 读取并解压文本 records
        val textRecords = mutableListOf<ByteArray>()
        var totalSize = 0L

        for (i in textStart until textEnd) {
            val size = recordSizes[i]
            if (size <= 0 || size > MAX_ENTRY_BYTES) continue
            if (totalSize + size > MAX_DECOMPRESSED_BYTES) {
                Log.w(TAG, "Text records exceed max size, truncating at record $i")
                break
            }

            val bytes = ByteArray(size.toInt())
            raf.seek(pdbRecords[i].offset)
            raf.readFully(bytes)
            textRecords.add(bytes)
            totalSize += size
        }

        if (textRecords.isEmpty()) return ""

        return try {
            val html = PalmDocDecoder.decompressTextRecords(
                textRecords, header.compression, header.charset
            )
            Log.i(TAG, "Decompressed ${textRecords.size} records -> ${html.length} chars")
            html
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress text records", e)
            ""
        }
    }

    /**
     * 解压 Huff/CDic 压缩的文本内容。
     *
     * 步骤：
     * 1. 定位并加载 HUFF record（Huffman 码表）
     * 2. 加载紧随其后的连续 CDIC record（短语字典）
     * 3. 文本 record 位于 HUFF/CDIC 之后，逐条独立解码后拼接
     */
    private fun extractHuffText(
        raf: RandomAccessFile,
        pdbRecords: List<PdbRecordEntry>,
        recordSizes: LongArray,
        header: MobiHeaderParser.MobiHeader,
        headerRecordIndex: Int
    ): String {
        val decoder = HuffCdicDecoder.Reader()

        // 定位 HUFF record：优先头部字段，其次 header 后的第一条，最后 record 1
        val candidates = listOf(header.huffRecordIndex, headerRecordIndex + 1, 1)
        var huffStart = -1
        for (cand in candidates) {
            if (cand <= 0 || cand >= pdbRecords.size) continue
            val bytes = readRecordBytes(raf, pdbRecords, recordSizes, cand) ?: continue
            if (!HuffCdicDecoder.isHuffRecord(bytes)) continue
            try {
                decoder.loadHuff(bytes)
                huffStart = cand
                break
            } catch (e: Exception) {
                Log.w(TAG, "loadHuff failed at record $cand", e)
            }
        }
        if (huffStart < 0) {
            Log.w(TAG, "HUFF compression declared but no HUFF record found")
            return ""
        }

        // 紧随 HUFF 之后的连续 CDIC records
        var cdicCount = 0
        var idx = huffStart + 1
        while (idx < pdbRecords.size && cdicCount < 256) {
            val bytes = readRecordBytes(raf, pdbRecords, recordSizes, idx) ?: break
            if (!HuffCdicDecoder.isCdicRecord(bytes)) break
            try {
                decoder.loadCdic(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "loadCdic failed at record $idx", e)
                break
            }
            cdicCount++
            idx++
        }

        val textStart = huffStart + 1 + cdicCount
        val textCount = header.textRecordCount
        val firstImage = header.firstImageIndex
        val textEnd = if (firstImage > 0 && firstImage < pdbRecords.size) {
            minOf(textStart + textCount, firstImage)
        } else {
            minOf(textStart + textCount, pdbRecords.size)
        }

        if (textStart >= textEnd || textEnd > pdbRecords.size) {
            Log.i(TAG, "No HUFF text records (start=$textStart, end=$textEnd)")
            return ""
        }

        Log.i(TAG, "HUFF text records $textStart..${textEnd - 1}, cdic=$cdicCount")

        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        for (i in textStart until textEnd) {
            val size = recordSizes[i]
            if (size <= 0 || size > MAX_ENTRY_BYTES) continue
            if (total + size > MAX_DECOMPRESSED_BYTES) {
                Log.w(TAG, "HUFF text exceeds max size, truncating at record $i")
                break
            }
            val bytes = readRecordBytes(raf, pdbRecords, recordSizes, i) ?: continue
            val decoded = try {
                // 每条 record 是自包含的比特流，必须单独解码
                decoder.unpack(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "HUFF unpack failed at record $i", e)
                ByteArray(0)
            }
            out.write(decoded, 0, decoded.size)
            total += decoded.size
        }

        val raw = out.toByteArray()
        Log.i(TAG, "HUFF decompressed ${textEnd - textStart} records -> ${raw.size} bytes")
        return String(raw, header.charset).trimEnd('\u0000')
    }

    /** 读取指定 record 的完整字节（带大小与越界保护）。 */
    private fun readRecordBytes(
        raf: RandomAccessFile,
        pdbRecords: List<PdbRecordEntry>,
        recordSizes: LongArray,
        index: Int
    ): ByteArray? {
        if (index < 0 || index >= pdbRecords.size) return null
        val size = recordSizes[index]
        if (size <= 0 || size > MAX_ENTRY_BYTES) return null
        return try {
            val bytes = ByteArray(size.toInt())
            raf.seek(pdbRecords[index].offset)
            raf.readFully(bytes)
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "read record $index failed", e)
            null
        }
    }

    // ── 页面构建 ─────────────────────────────────────────────────────────

    /**
     * 根据解析结果智能构建页面列表。
     *
     * 策略：
     * - 如果 HTML 解析产生了足够的文本页，使用结构化页面（文本渲染 + 插图）
     * - 如果图片远多于文本页，使用纯图片模式（漫画）
     * - 混合模式：文本页和图片页交错
     */
    private fun buildPages(
        context: Context,
        parseResult: MobiHtmlParser.ParseResult,
        images: List<ImageRecordInfo>,
        screenW: Int,
        screenH: Int
    ): List<ComicPage> {
        val textPages = parseResult.pages.filter { !it.hasImages }
        val imagePages = parseResult.pages.filter { it.hasImages }

        val totalTextChars = parseResult.pages.sumOf { it.plainText.length }

        Log.i(TAG, "Content analysis: textPages=${textPages.size}, " +
                "imagePages=${imagePages.size}, " +
                "images=${images.size}, " +
                "textChars=$totalTextChars")

        val isEbook = totalTextChars >= MIN_TEXT_CHARS_FOR_EBOOK &&
                textPages.size >= MIN_TEXT_PAGES_FOR_EBOOK &&
                textPages.size > imagePages.size

        val isComic = !isEbook && images.isNotEmpty()

        val imageByRecordIndex = images.associateBy { it.recordIndex }

        return when {
            isComic -> buildComicPages(images, parseResult, screenW, screenH)
            isEbook -> buildEbookPages(context, parseResult, imageByRecordIndex, screenW, screenH)
            else -> {
                if (images.isNotEmpty()) {
                    buildComicPages(images, parseResult, screenW, screenH)
                } else if (parseResult.pages.isNotEmpty()) {
                    buildEbookPages(context, parseResult, imageByRecordIndex, screenW, screenH)
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun buildComicPages(
        images: List<ImageRecordInfo>,
        parseResult: MobiHtmlParser.ParseResult,
        screenW: Int,
        screenH: Int
    ): List<ComicPage> {
        val imageTextMap = mutableMapOf<Int, String>()
        for (page in parseResult.pages) {
            for (recIndex in page.imageIndices) {
                imageTextMap[recIndex] = page.plainText
            }
        }

        return images.mapIndexed { index, info ->
            val textContent = imageTextMap[info.recordIndex] ?: ""

            ComicPage(
                index = index,
                name = "image_${index + 1}.${info.imageType}",
                load = { decodeImage(info, screenW, screenH) },
                loadStream = { openImageStream(info) },
                textContent = textContent,
                chapterTitle = ""
            )
        }
    }

    private fun buildEbookPages(
        context: Context,
        parseResult: MobiHtmlParser.ParseResult,
        imageByRecordIndex: Map<Int, ImageRecordInfo>,
        screenW: Int,
        screenH: Int
    ): List<ComicPage> {
        val isDark = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val renderer = TextPageRenderer(screenW, screenH, isDarkTheme = isDark)
        val pages = mutableListOf<ComicPage>()
        var pageIndex = 0

        for (parsedPage in parseResult.pages) {
            if (parsedPage.hasImages) {
                for (recIndex in parsedPage.imageIndices) {
                    val info = imageByRecordIndex[recIndex]
                    if (info != null) {
                        pages.add(
                            ComicPage(
                                index = pageIndex,
                                name = "image_${recIndex + 1}.${info.imageType}",
                                load = { decodeImage(info, screenW, screenH) },
                                loadStream = { openImageStream(info) },
                                textContent = parsedPage.plainText,
                                chapterTitle = findChapterTitle(parseResult, pageIndex)
                            )
                        )
                        pageIndex++
                    }
                }
            } else if (parsedPage.plainText.isNotBlank()) {
                val blocks = parsedPage.blocks
                val textContent = parsedPage.plainText
                val chapterTitle = findChapterTitle(parseResult, pageIndex)
                val currentIndex = pageIndex

                pages.add(
                    ComicPage(
                        index = currentIndex,
                        name = "page_${currentIndex + 1}",
                        load = {
                            renderer.renderPage(
                                blocks,
                                pageNumber = currentIndex + 1
                            )
                        },
                        loadStream = {
                            val bmp = renderer.renderPage(blocks, currentIndex + 1)
                            val bytes = renderer.encodeToPng(bmp)
                            bmp.recycle()
                            ByteArrayInputStream(bytes)
                        },
                        textContent = textContent,
                        chapterTitle = chapterTitle
                    )
                )
                pageIndex++
            }
        }

        return pages
    }

    /**
     * 查找某页所属的章节标题。
     */
    private fun findChapterTitle(parseResult: MobiHtmlParser.ParseResult, pageIndex: Int): String {
        // 从 TOC 中找到该页之前最近的标题
        val toc = parseResult.toc
        var title = ""
        for (entry in toc) {
            if (entry.pageIndex <= pageIndex) {
                title = entry.title
            } else {
                break
            }
        }
        return title
    }

    // ── PDB 头部解析 ─────────────────────────────────────────────────────

    private fun parsePdbHeader(raf: RandomAccessFile): List<PdbRecordEntry> {
        val headerBytes = ByteArray(PDB_HEADER_SIZE)
        raf.seek(0)
        raf.readFully(headerBytes)

        val numRecords = ((headerBytes[76].toInt() and 0xFF) shl 8) or
                (headerBytes[77].toInt() and 0xFF)
        if (numRecords <= 0 || numRecords > 65535) return emptyList()

        val infoBytes = ByteArray(numRecords * RECORD_INFO_SIZE)
        raf.seek(PDB_HEADER_SIZE.toLong())
        raf.readFully(infoBytes)

        val records = ArrayList<PdbRecordEntry>(numRecords)
        for (i in 0 until numRecords) {
            val base = i * RECORD_INFO_SIZE
            val offset = ((infoBytes[base].toLong() and 0xFF) shl 24) or
                    ((infoBytes[base + 1].toLong() and 0xFF) shl 16) or
                    ((infoBytes[base + 2].toLong() and 0xFF) shl 8) or
                    (infoBytes[base + 3].toLong() and 0xFF)
            val attributes = infoBytes[base + 4].toInt() and 0xFF
            records.add(PdbRecordEntry(offset, attributes))
        }
        return records
    }

    // ── 图片 record 扫描 ─────────────────────────────────────────────────

    /**
     * 扫描 PDB records 提取图片。
     *
     * 两阶段策略：
     * 1. **Magic bytes 全量扫描**：扫描所有 records，用 magic bytes 识别图片。
     *    探测偏移 0/1/2/3/4/6/8，兼容对齐填充。
     * 2. **firstImageIndex 补充扫描**：无论 Phase 1 找到多少图片，
     *    都从 firstImageIndex 开始扫描剩余区域，补充检测漏检图片。
     *    跳过已知的非图片资源 record（FLIS/FCIS/FDST/RESC/BOUND/DATP），
     *    对于未能通过 magic bytes 识别但位于图片区的 record，
     *    若大小合理（≥100字节）且不是资源 record，则标记为图片（默认 jpeg）。
     *
     * 这样解决了以下问题：
     * - 图片 record 有非标准填充（> 4 字节）导致 magic bytes 偏移异常
     * - 图片使用非标准格式头（如特殊 JPEG 变体）
     * - KF8 资源 record 与图片 record 混合存储
     * - 旧逻辑 images.size < 3 的阈值导致找到少量图片后停止扫描
     */
    private fun findAllImageRecords(
        raf: RandomAccessFile,
        pdbRecords: List<PdbRecordEntry>,
        recordSizes: LongArray,
        imageStartIndices: List<Int>
    ): List<ImageRecordInfo> {
        val images = mutableListOf<ImageRecordInfo>()
        val detectedOffsets = mutableSetOf<Long>()
        val probeSize = 32
        val probeBuf = ByteArray(probeSize)

        // Phase 1: Magic bytes 全量扫描
        for (i in pdbRecords.indices) {
            val recordOffset = pdbRecords[i].offset
            val recordSize = recordSizes[i]

            if (recordSize < 4 || recordSize > MAX_ENTRY_BYTES) continue
            if (pdbRecords[i].attributes and 0x40 != 0) continue
            if (isResourceRecord(raf, recordOffset, probeBuf)) continue

            val detection = detectImageInRecord(raf, recordOffset, probeBuf) ?: continue
            val imageOffset = recordOffset + detection.padding
            val imageSize = recordSize - detection.padding

            images.add(ImageRecordInfo(i, imageOffset, imageSize, detection.imageType))
            detectedOffsets.add(recordOffset)
        }

        Log.i(TAG, "Phase 1 (magic bytes): found ${images.size} images")

        // Phase 2: 从每个图片区起始点补充扫描
        for (startIndex in imageStartIndices.distinct().sorted()) {
            if (startIndex <= 0 || startIndex >= pdbRecords.size) continue
            var supplemented = 0
            for (i in startIndex until pdbRecords.size) {
                val recordOffset = pdbRecords[i].offset
                if (recordOffset in detectedOffsets) continue

                val recordSize = recordSizes[i]
                if (recordSize < 100 || recordSize > MAX_ENTRY_BYTES) continue
                if (pdbRecords[i].attributes and 0x40 != 0) continue

                // 跳过已知的非图片资源 record
                if (isResourceRecord(raf, recordOffset, probeBuf)) continue

                val detection = detectImageInRecordExtended(raf, recordOffset, probeBuf)
                if (detection != null) {
                    val imageOffset = recordOffset + detection.padding
                    val imageSize = recordSize - detection.padding
                    images.add(ImageRecordInfo(i, imageOffset, imageSize, detection.imageType))
                    detectedOffsets.add(recordOffset)
                    supplemented++
                } else {
                    images.add(ImageRecordInfo(i, recordOffset, recordSize, "jpeg"))
                    detectedOffsets.add(recordOffset)
                    supplemented++
                }
            }
            if (supplemented > 0) {
                Log.i(TAG, "Phase 2 (startIndex=$startIndex): supplemented $supplemented images")
            }
        }

        images.sortBy { it.offset }
        Log.i(TAG, "Total: ${images.size} image records from ${pdbRecords.size} PDB records")
        return images
    }

    /**
     * 检测 record 是否为 KF8 资源 record（非图片）。
     * 检查前 4 字节是否匹配已知资源 magic：
     * - FLIS (0x464C4953): 字体列表
     * - FCIS (0x46434953): 字体压缩索引
     * - FDST (0x46445354): 字体数据流
     * - RESC (0x52455343): 资源记录
     * - BOUND (0x424F554E): 边界标记
     * - DATP (0x44415450): 数据填充
     * - AUDI (0x41554449): 音频
     * - VIDE (0x56494445): 视频
     */
    private fun isResourceRecord(
        raf: RandomAccessFile,
        recordOffset: Long,
        buf: ByteArray
    ): Boolean {
        return try {
            raf.seek(recordOffset)
            val read = raf.read(buf, 0, 4)
            if (read < 4) return false

            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            val b2 = buf[2].toInt() and 0xFF
            val b3 = buf[3].toInt() and 0xFF
            val magic = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

            when (magic) {
                0x464C4953, // FLIS
                0x46434953, // FCIS
                0x46445354, // FDST
                0x52455343, // RESC
                0x424F554E, // BOUND
                0x44415450, // DATP
                0x41554449, // AUDI
                0x56494445, // VIDE
                -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 在 record 数据中检测图片 magic bytes。
     * 检查偏移 0,1,2,3,4,6,8 处，兼容对齐填充。
     *
     * @return 图片类型和填充偏移，不是图片返回 null
     */
    private fun detectImageInRecord(
        raf: RandomAccessFile,
        recordOffset: Long,
        buf: ByteArray
    ): ImageDetection? {
        return try {
            raf.seek(recordOffset)
            val read = raf.read(buf)
            if (read < 3) return null

            val probeOffsets = intArrayOf(0, 1, 2, 3, 4, 6, 8)
            for (padding in probeOffsets) {
                if (padding + 3 > read) break
                val type = detectImageTypeAt(buf, padding, read)
                if (type != null) return ImageDetection(type, padding)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 扩展图片检测：扫描 record 前 32 字节的所有偏移。
     * 用于 Phase 2 回退时，处理可能有较大填充或非标准头部的图片 record。
     */
    private fun detectImageInRecordExtended(
        raf: RandomAccessFile,
        recordOffset: Long,
        buf: ByteArray
    ): ImageDetection? {
        return try {
            raf.seek(recordOffset)
            val read = raf.read(buf)
            if (read < 3) return null

            for (padding in 0 until read - 2) {
                val type = detectImageTypeAt(buf, padding, read)
                if (type != null) return ImageDetection(type, padding)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在缓冲区指定偏移处检测图片类型。
     * @return "jpeg" / "png" / "gif" / "bmp" / "webp" / "tiff" / "jp2"，不是图片返回 null
     */
    private fun detectImageTypeAt(buf: ByteArray, offset: Int, limit: Int): String? {
        val remaining = limit - offset
        if (remaining < 2) return null

        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF

        // JPEG: FF D8 FF
        if (remaining >= 3 && b0 == 0xFF && b1 == 0xD8 &&
            (buf[offset + 2].toInt() and 0xFF) == 0xFF
        ) return "jpeg"

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (remaining >= 8 && b0 == 0x89 && b1 == 0x50 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x4E && (buf[offset + 3].toInt() and 0xFF) == 0x47 &&
            (buf[offset + 4].toInt() and 0xFF) == 0x0D && (buf[offset + 5].toInt() and 0xFF) == 0x0A &&
            (buf[offset + 6].toInt() and 0xFF) == 0x1A && (buf[offset + 7].toInt() and 0xFF) == 0x0A
        ) return "png"

        // GIF: 47 49 46 38 (GIF8)
        if (remaining >= 4 && b0 == 0x47 && b1 == 0x49 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x46 && (buf[offset + 3].toInt() and 0xFF) == 0x38
        ) return "gif"

        // BMP: 42 4D (BM)
        if (remaining >= 2 && b0 == 0x42 && b1 == 0x4D) return "bmp"

        // WebP: 52 49 46 46 xx xx xx xx 57 45 42 50 (RIFF....WEBP)
        if (remaining >= 12 && b0 == 0x52 && b1 == 0x49 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x46 && (buf[offset + 3].toInt() and 0xFF) == 0x46 &&
            (buf[offset + 8].toInt() and 0xFF) == 0x57 && (buf[offset + 9].toInt() and 0xFF) == 0x45 &&
            (buf[offset + 10].toInt() and 0xFF) == 0x42 && (buf[offset + 11].toInt() and 0xFF) == 0x50
        ) return "webp"

        // TIFF little-endian: 49 49 2A 00
        if (remaining >= 4 && b0 == 0x49 && b1 == 0x49 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x2A && (buf[offset + 3].toInt() and 0xFF) == 0x00
        ) return "tiff"

        // TIFF big-endian: 4D 4D 00 2A
        if (remaining >= 4 && b0 == 0x4D && b1 == 0x4D &&
            (buf[offset + 2].toInt() and 0xFF) == 0x00 && (buf[offset + 3].toInt() and 0xFF) == 0x2A
        ) return "tiff"

        // JPEG2000: 00 00 00 0C 6A 50 20 20
        if (remaining >= 8 && b0 == 0x00 && b1 == 0x00 &&
            (buf[offset + 2].toInt() and 0xFF) == 0x00 && (buf[offset + 3].toInt() and 0xFF) == 0x0C &&
            (buf[offset + 4].toInt() and 0xFF) == 0x6A && (buf[offset + 5].toInt() and 0xFF) == 0x50
        ) return "jp2"

        return null
    }

    // ── 图片加载 ─────────────────────────────────────────────────────────

    private suspend fun decodeImage(info: ImageRecordInfo, screenW: Int, screenH: Int) =
        withContext(Dispatchers.IO) {
            val bytes = readImageBytes(info) ?: return@withContext null
            BitmapDecoderUtils.decodeSampled(bytes, screenW, screenH, tag = TAG)
        }

    private fun openImageStream(info: ImageRecordInfo): java.io.InputStream? {
        val bytes = readImageBytes(info) ?: return null
        return ByteArrayInputStream(bytes)
    }

    private fun readImageBytes(info: ImageRecordInfo): ByteArray? {
        return try {
            synchronized(stateLock) {
                val raf = randomAccessFile ?: return null
                val readSize = minOf(info.size, MAX_ENTRY_BYTES).toInt()
                val bytes = ByteArray(readSize)
                raf.seek(info.offset)
                raf.readFully(bytes)
                bytes
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "读取 MOBI 图片字节失败: 超过大小上限", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "读取 MOBI 图片字节失败", e)
            null
        }
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    private fun openFile(context: Context, uri: Uri): File {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::exists)?.let { return it }
        }

        val temp = File(context.cacheDir, "mobi_temp_${System.currentTimeMillis()}.mobi")
        tempFile = temp
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open MOBI stream")
            temp
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            tempFile = null
            throw e
        }
    }
}
