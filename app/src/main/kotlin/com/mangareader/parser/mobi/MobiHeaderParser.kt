package com.mangareader.parser.mobi

import android.util.Log
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * MOBI 头部解析器。
 *
 * 解析 record 0 中的 PalmDOC 头部 + MOBI 头部 + EXTH 头部，
 * 提取解析文本内容所需的全部元数据。
 *
 * MOBI 头部布局（在 record 0 中的偏移）：
 * - 0..15: PalmDOC 头部
 * - 16..19: "MOBI" magic
 * - 20..23: MOBI header length
 * - 24..27: type (2 = MOBI book)
 * - 28..31: codepage (1252=CP1252, 65001=UTF-8)
 * - 36..39: version
 * - 108..111: firstImageIndex
 * - ...
 * - 如果 EXTH flag (偏移 128, bit 6) 设置，MOBI header 后跟 EXTH header
 *
 * KF8 (AZW3) 合并文件中，EXTH type 121 指向 KF8 头部 record。
 */
class MobiHeaderParser {

    companion object {
        private const val TAG = "MobiHeaderParser"

        // PalmDOC 头部偏移
        private const val PALMDOC_COMPRESSION = 0      // 2 bytes
        private const val PALMDOC_TEXT_LENGTH = 4      // 4 bytes
        private const val PALMDOC_RECORD_COUNT = 8     // 2 bytes
        private const val PALMDOC_RECORD_SIZE = 10     // 2 bytes
        private const val PALMDOC_ENCRYPTION = 12      // 2 bytes

        // MOBI 头部偏移（相对于 record 0 起始）
        private const val MOBI_MAGIC_OFFSET = 16       // 4 bytes "MOBI"
        private const val MOBI_HEADER_LENGTH = 20      // 4 bytes
        private const val MOBI_TYPE = 24               // 4 bytes
        private const val MOBI_CODEPAGE = 28           // 4 bytes
        private const val MOBI_VERSION = 36            // 4 bytes
        private const val MOBI_FIRST_IMAGE_INDEX = 108 // 4 bytes
        private const val MOBI_FULL_NAME_OFFSET = 84   // 4 bytes (offset within record 0)
        private const val MOBI_FULL_NAME_LENGTH = 88   // 4 bytes
        private const val MOBI_EXTH_FLAGS = 128        // 4 bytes
        private const val MOBI_LOCALE = 92             // 4 bytes

        // EXTH
        private const val EXTH_FLAG_BIT = 0x40
        private val EXTH_MAGIC = "EXTH".toByteArray()

        // EXTH record types
        const val EXTH_AUTHOR = 100
        const val EXTH_PUBLISHER = 101
        const val EXTH_DESCRIPTION = 103
        const val EXTH_ISBN = 104
        const val EXTH_SUBJECT = 105
        const val EXTH_PUBLISHING_DATE = 106
        const val EXTH_REVIEW = 107
        const val EXTH_CONTRIBUTOR = 108
        const val EXTH_RIGHTS = 109
        const val EXTH_TYPE = 111
        const val EXTH_SOURCE = 112
        const val EXTH_ASIN = 113
        const val EXTH_VERSION = 114
        const val EXTH_KF8_BOUNDARY = 121
        const val EXTH_COVER_OFFSET = 201
        const val EXTH_THUMB_OFFSET = 202
        const val EXTH_HAS_FAKE_COVER = 203
        const val EXTH_CREATOR_SOFTWARE = 204
        const val EXTH_CREATOR_MAJOR = 205
        const val EXTH_CREATOR_MINOR = 206
        const val EXTH_CREATOR_BUILD = 207
        const val EXTH_FONT_SIGNATURE = 300
        const val EXTH_CLIPPING_LIMIT = 401
        const val EXTH_PUBLISHER_LIMIT = 402
        const val EXTH_TTS_FLAG = 404
        const val EXTH_CDE_TYPE = 501
        const val EXTH_LAST_UPDATE = 502
        const val EXTH_UPDATED_TITLE = 503
        const val EXTH_LANGUAGE = 524

        // 加密类型
        const val ENCRYPTION_NONE = 0
        const val ENCRYPTION_OLD = 1
        const val ENCRYPTION_MOBI = 2
    }

    /** 解析后的 MOBI 头部数据。 */
    data class MobiHeader(
        val compression: Int = PalmDocDecoder.COMPRESSION_NONE,
        val textLength: Int = 0,
        val textRecordCount: Int = 0,
        val textRecordSize: Int = 0,
        val encryption: Int = ENCRYPTION_NONE,
        val codepage: Int = 1252,
        val version: Int = 0,
        val firstImageIndex: Int = -1,
        val fullName: String = "",
        val locale: String = "",
        val charset: Charset = Charsets.UTF_8,
        val exthRecords: Map<Int, ByteArray> = emptyMap(),
        val kf8Boundary: Int = -1,
        val coverIndex: Int = -1,
        val author: String = "",
        val publisher: String = "",
        val description: String = "",
        val subject: String = "",
        val language: String = ""
    )

    /**
     * 从 record 0 解析 MOBI 头部。
     *
     * @param raf 随机访问文件
     * @param record0Offset record 0 的文件偏移
     * @return 解析后的头部数据
     */
    fun parse(raf: RandomAccessFile, record0Offset: Long): MobiHeader {
        // 读取 record 0 的前 256 字节（覆盖所有关键字段）
        val headerSize = 256
        val buf = ByteArray(headerSize)
        raf.seek(record0Offset)
        raf.readFully(buf)

        // PalmDOC 头部
        val compression = readUInt16(buf, PALMDOC_COMPRESSION)
        val textLength = readInt32(buf, PALMDOC_TEXT_LENGTH)
        val textRecordCount = readUInt16(buf, PALMDOC_RECORD_COUNT)
        val textRecordSize = readUInt16(buf, PALMDOC_RECORD_SIZE)
        val encryption = readUInt16(buf, PALMDOC_ENCRYPTION)

        // MOBI 头部
        val mobiMagic = "MOBI".toByteArray()
        if (!matchesMagic(buf, MOBI_MAGIC_OFFSET, mobiMagic)) {
            return MobiHeader() // 不是有效的 MOBI 文件
        }

        val mobiHeaderLen = readInt32(buf, MOBI_HEADER_LENGTH)
        val codepage = readInt32(buf, MOBI_CODEPAGE)
        val version = readInt32(buf, MOBI_VERSION)
        val firstImageIndex = readInt32(buf, MOBI_FIRST_IMAGE_INDEX)
        val fullNameOffset = readInt32(buf, MOBI_FULL_NAME_OFFSET)
        val fullNameLength = readInt32(buf, MOBI_FULL_NAME_LENGTH)
        val exthFlags = readInt32(buf, MOBI_EXTH_FLAGS)
        val localeInt = readInt32(buf, MOBI_LOCALE)

        // 字符集
        val charset = when (codepage) {
            65001 -> Charsets.UTF_8
            1252 -> Charset.forName("windows-1252")
            932 -> Charset.forName("Shift_JIS")
            936 -> Charset.forName("GBK")
            949 -> Charset.forName("EUC-KR")
            950 -> Charset.forName("Big5")
            else -> Charsets.UTF_8
        }

        // Locale (低 10 位 = 语言, 高 10 位 = 国家)
        val langCode = localeInt and 0x3FF
        val countryCode = (localeInt shr 10) and 0x3FF
        val locale = if (langCode > 0) "lang=$langCode,country=$countryCode" else ""

        // 完整书名
        var fullName = ""
        if (fullNameOffset > 0 && fullNameLength > 0) {
            try {
                val nameBytes = ByteArray(fullNameLength)
                raf.seek(record0Offset + fullNameOffset)
                raf.readFully(nameBytes)
                fullName = String(nameBytes, charset).trimEnd('\u0000')
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read full name", e)
            }
        }

        // EXTH 头部
        var exthRecords = emptyMap<Int, ByteArray>()
        var kf8Boundary = -1
        var coverIndex = -1
        var author = ""
        var publisher = ""
        var description = ""
        var subject = ""
        var language = ""

        if (exthFlags and EXTH_FLAG_BIT != 0) {
            val exthOffset = record0Offset + 16 + mobiHeaderLen
            val exthData = parseExth(raf, exthOffset, charset)
            exthRecords = exthData

            // 提取关键 EXTH 字段
            kf8Boundary = exthData[EXTH_KF8_BOUNDARY]?.let { bytes ->
                if (bytes.size >= 4) readInt32(bytes, 0) else -1
            } ?: -1

            coverIndex = exthData[EXTH_COVER_OFFSET]?.let { bytes ->
                if (bytes.size >= 4) readInt32(bytes, 0) else -1
            } ?: -1

            author = exthData[EXTH_AUTHOR]?.let { String(it, charset).trim() } ?: ""
            publisher = exthData[EXTH_PUBLISHER]?.let { String(it, charset).trim() } ?: ""
            description = exthData[EXTH_DESCRIPTION]?.let { String(it, charset).trim() } ?: ""
            subject = exthData[EXTH_SUBJECT]?.let { String(it, charset).trim() } ?: ""
            language = exthData[EXTH_LANGUAGE]?.let { String(it, charset).trim() } ?: ""
        }

        return MobiHeader(
            compression = compression,
            textLength = textLength,
            textRecordCount = textRecordCount,
            textRecordSize = textRecordSize,
            encryption = encryption,
            codepage = codepage,
            version = version,
            firstImageIndex = firstImageIndex,
            fullName = fullName,
            locale = locale,
            charset = charset,
            exthRecords = exthRecords,
            kf8Boundary = kf8Boundary,
            coverIndex = coverIndex,
            author = author,
            publisher = publisher,
            description = description,
            subject = subject,
            language = language
        )
    }

    /**
     * 解析 EXTH 头部。
     * EXTH 头部格式：
     * - 4 bytes: "EXTH" magic
     * - 4 bytes: header length (包含 magic)
     * - 4 bytes: record count
     * - N * record: type(4) + length(4) + data(length-8)
     */
    private fun parseExth(
        raf: RandomAccessFile,
        exthOffset: Long,
        @Suppress("UNUSED_PARAMETER") charset: Charset
    ): Map<Int, ByteArray> {
        val records = mutableMapOf<Int, ByteArray>()

        try {
            val exthHeader = ByteArray(12)
            raf.seek(exthOffset)
            raf.readFully(exthHeader)

            if (!matchesMagic(exthHeader, 0, EXTH_MAGIC)) return emptyMap()

            val exthLength = readInt32(exthHeader, 4)
            val recordCount = readInt32(exthHeader, 8)

            if (recordCount <= 0 || recordCount > 1000 || exthLength <= 12) return emptyMap()

            // 读取整个 EXTH 区域
            val exthData = ByteArray(exthLength - 12)
            raf.seek(exthOffset + 12)
            raf.readFully(exthData)

            var pos = 0
            for (i in 0 until recordCount) {
                if (pos + 8 > exthData.size) break

                val type = readInt32(exthData, pos)
                val length = readInt32(exthData, pos + 4)
                if (length < 8 || pos + length > exthData.size) break

                val data = exthData.copyOfRange(pos + 8, pos + length)
                records[type] = data

                pos += length
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse EXTH header", e)
        }

        return records
    }

    /**
     * 从 KF8 头部 record 解析 KF8 特定的头部数据。
     * KF8 头部结构与 MOBI 6 相同，只是位于不同的 record。
     */
    fun parseKf8Header(raf: RandomAccessFile, kf8RecordOffset: Long): MobiHeader {
        return parse(raf, kf8RecordOffset)
    }

    // ── 工具方法 ────────────────────────────────────────────────────────

    private fun readUInt16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readInt32(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }

    private fun matchesMagic(buf: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset + magic.size > buf.size) return false
        for (i in magic.indices) {
            if (buf[offset + i] != magic[i]) return false
        }
        return true
    }
}
