package com.mangareader.parser.mobi

import java.io.ByteArrayOutputStream

/**
 * MOBI Huff/CDic（Huffman + 短语字典）解压器。
 *
 * 很多**小说/文本型** MOBI 使用 Huffman + CDIC 字典压缩（compression=17480），
 * 而漫画型 MOBI 的文本 record 极少、通常使用 PalmDOC/无压缩，因此漫画能读、
 * 小说不能读。本解码器补齐该压缩方式。
 *
 * 文件结构（HUFF 压缩时）：
 * - record 0：MOBI 头部
 * - HUFF record：以 "HUFF" 开头，含 Huffman 码表
 * - CDIC record(s)：以 "CDIC" 开头，含短语字典
 * - 之后为文本 record，**每条独立解码**
 *
 * 算法参考 calibre / KindleUnpack 的 huffcdic 实现。
 */
object HuffCdicDecoder {

    private const val HUFF_MAGIC = "HUFF"
    private const val CDIC_MAGIC = "CDIC"

    fun isHuffRecord(data: ByteArray, offset: Int = 0): Boolean = magicAt(data, offset, HUFF_MAGIC)

    fun isCdicRecord(data: ByteArray, offset: Int = 0): Boolean = magicAt(data, offset, CDIC_MAGIC)

    private fun magicAt(data: ByteArray, offset: Int, magic: String): Boolean {
        if (offset < 0 || offset + magic.length > data.size) return false
        for (i in magic.indices) {
            if ((data[offset + i].toInt() and 0xFF) != magic[i].code) return false
        }
        return true
    }

    /**
     * 单个 HUFF 文件的解码状态。
     * 使用顺序：先 [loadHuff]，再依次 [loadCdic]，然后对每条文本 record 调用 [unpack]。
     */
    class Reader {

        private val dict1Codelen = IntArray(256)
        private val dict1Term = BooleanArray(256)
        private val dict1Maxcode = LongArray(256)
        private val mincode = LongArray(33)
        private val maxcode = LongArray(33)
        private val dictionary = ArrayList<Phrase>()

        private class Phrase(val raw: ByteArray, val terminal: Boolean) {
            var expanded: ByteArray? = null
            var expanding: Boolean = false
        }

        /** 解析 HUFF record 中的 Huffman 码表。 */
        fun loadHuff(huff: ByteArray) {
            require(magicAt(huff, 0, HUFF_MAGIC)) { "Invalid HUFF header" }
            val off1 = readUInt32(huff, 8).toInt()
            val off2 = readUInt32(huff, 12).toInt()

            for (i in 0 until 256) {
                val v = readUInt32(huff, off1 + i * 4)
                val codelen = (v and 0x1F).toInt()
                if (codelen == 0 || codelen > 31) {
                    dict1Codelen[i] = 0
                    dict1Term[i] = false
                    dict1Maxcode[i] = 0
                    continue
                }
                val term = (v and 0x80) != 0L
                val rawMax = v ushr 8
                dict1Codelen[i] = codelen
                dict1Term[i] = term
                dict1Maxcode[i] = ((rawMax + 1) shl (32 - codelen)) - 1
            }

            for (codelen in 0..32) {
                if (codelen == 0) {
                    mincode[0] = 0
                    maxcode[0] = 0
                    continue
                }
                val minRaw = readUInt32(huff, off2 + (codelen - 1) * 2 * 4)
                val maxRaw = readUInt32(huff, off2 + ((codelen - 1) * 2 + 1) * 4)
                mincode[codelen] = minRaw shl (32 - codelen)
                maxcode[codelen] = ((maxRaw + 1) shl (32 - codelen)) - 1
            }
        }

        /** 解析 CDIC record 中的短语字典，按顺序追加到字典表。 */
        fun loadCdic(cdic: ByteArray) {
            require(magicAt(cdic, 0, CDIC_MAGIC)) { "Invalid CDIC header" }
            val phrases = readUInt32(cdic, 8).toInt()
            val bits = readUInt32(cdic, 12).toInt()
            val cap = if (bits in 0..20) (1 shl bits) else phrases
            val n = minOf(cap, phrases - dictionary.size).coerceAtLeast(0)

            for (i in 0 until n) {
                val off = readUInt16(cdic, 16 + i * 2).toInt()
                val blen = readUInt16(cdic, 16 + off).toInt()
                val len = blen and 0x7FFF
                val start = 18 + off
                val slice = if (start >= 0 && start + len <= cdic.size) {
                    cdic.copyOfRange(start, start + len)
                } else {
                    ByteArray(0)
                }
                dictionary.add(Phrase(slice, (blen and 0x8000) != 0))
            }
        }

        /**
         * 解压一条文本 record 的字节流。
         * 每条 record 是自包含的比特流，必须单独调用。
         */
        fun unpack(data: ByteArray): ByteArray {
            // 末尾补 8 字节 0，保证读取 64 位窗口时越界安全
            val padded = data.copyOf(data.size + 8)
            var bitsLeft = data.size.toLong() * 8
            var pos = 0
            var x = readUInt64(padded, pos)
            var n = 32
            val out = ByteArrayOutputStream(data.size * 3 + 64)

            while (true) {
                if (n <= 0) {
                    pos += 4
                    if (pos + 8 > padded.size) break
                    x = readUInt64(padded, pos)
                    n += 32
                }

                val code = (x ushr n) and 0xFFFFFFFFL
                val idx = ((code ushr 24).toInt()) and 0xFF
                var codelen = dict1Codelen[idx]
                if (codelen == 0) break

                var mc = dict1Maxcode[idx]
                if (!dict1Term[idx]) {
                    while (codelen < 33 && code < mincode[codelen]) codelen++
                    if (codelen >= 33) break
                    mc = maxcode[codelen]
                }

                n -= codelen
                bitsLeft -= codelen
                if (bitsLeft < 0) break

                val r = ((mc - code) ushr (32 - codelen)).toInt()
                if (r < 0 || r >= dictionary.size) break

                val phrase = dictionary[r]
                val slice = if (phrase.terminal) phrase.raw else expand(phrase)
                out.write(slice, 0, slice.size)
            }

            return out.toByteArray()
        }

        private fun expand(phrase: Phrase): ByteArray {
            phrase.expanded?.let { return it }
            if (phrase.expanding) return ByteArray(0)
            phrase.expanding = true
            val e = unpack(phrase.raw)
            phrase.expanding = false
            phrase.expanded = e
            return e
        }
    }

    // ── 读取工具（大端序） ────────────────────────────────────────────────

    private fun readUInt16(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 2 > b.size) return 0
        return (((b[off].toLong() and 0xFF) shl 8) or (b[off + 1].toLong() and 0xFF))
    }

    private fun readUInt32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) return 0
        return ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)
    }

    private fun readUInt64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }
}