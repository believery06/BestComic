package com.mangareader.parser.mobi

/**
 * PalmDOC LZ77 解压器。
 *
 * MOBI 文本记录使用 PalmDOC 压缩（一种简化的 LZ77 变体）。
 * 压缩格式：
 * - 0x00: 字面字节 0x00
 * - 0x01..0x08: 后跟 N 个字面字节
 * - 0x09..0x7F: 字面字节
 * - 0x80..0xBF: 2 字节 LZ77 匹配（11-bit 距离 + 3-bit 长度）
 * - 0xC0..0xFF: 空格 + (c XOR 0x80)
 *
 * 参考：PalmDOC 格式规范
 */
object PalmDocDecoder {

    /** PalmDOC 压缩类型标识。 */
    const val COMPRESSION_NONE = 1
    const val COMPRESSION_PALMDOC = 2
    const val COMPRESSION_HUFF = 17480 // 0x4448，由 MobiParser 走 HuffCdicDecoder 分支处理

    /**
     * 解压 PalmDOC 压缩的数据。
     *
     * @param data 压缩的字节数组
     * @param expectedSize 期望的解压后大小（用于预分配）
     * @return 解压后的字节数组
     * @throws MobiDecompressException 数据损坏时抛出
     */
    fun decompress(data: ByteArray, expectedSize: Int = data.size * 3): ByteArray {
        var result = ByteArray(expectedSize.coerceAtLeast(4096))
        var pos = 0
        var i = 0

        fun grow(needed: Int) {
            if (pos + needed > result.size) {
                val newSize = maxOf(result.size * 2, pos + needed + 256)
                result = result.copyOf(newSize)
            }
        }

        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++

            when {
                c == 0x00 -> {
                    grow(1)
                    result[pos++] = 0
                }
                c in 1..8 -> {
                    grow(c)
                    for (j in 0 until c) {
                        if (i < data.size) {
                            result[pos++] = data[i]
                            i++
                        }
                    }
                }
                c < 0x80 -> {
                    grow(1)
                    result[pos++] = c.toByte()
                }
                c >= 0xC0 -> {
                    grow(2)
                    result[pos++] = ' '.code.toByte()
                    result[pos++] = (c xor 0x80).toByte()
                }
                else -> {
                    // 0x80..0xBF: LZ77 匹配
                    if (i >= data.size) break
                    val next = data[i].toInt() and 0xFF
                    i++

                    val distance = (((c shl 8) or next) shr 3) and 0x7FF
                    val length = (next and 0x07) + 3

                    if (distance == 0 || distance > pos) {
                        throw MobiDecompressException("Invalid back-reference: distance=$distance, pos=$pos")
                    }

                    grow(length)
                    val srcStart = pos - distance
                    for (j in 0 until length) {
                        result[pos] = result[srcStart + j]
                        pos++
                    }
                }
            }
        }

        return result.copyOf(pos)
    }

    /**
     * 解压 MOBI 文件的多个文本记录并拼接为完整的 HTML 字符串。
     *
     * @param records 要解压的 record 数据列表
     * @param compression 压缩类型（来自 MOBI 头部）
     * @param charset 字符集（来自 codepage）
     * @return 拼接后的 HTML 字符串
     */
    fun decompressTextRecords(
        records: List<ByteArray>,
        compression: Int,
        charset: java.nio.charset.Charset
    ): String {
        val sb = StringBuilder()
        for (record in records) {
            val bytes = when (compression) {
                COMPRESSION_PALMDOC -> decompress(record)
                COMPRESSION_NONE -> record
                else -> throw MobiDecompressException("Unsupported compression: $compression")
            }
            sb.append(String(bytes, charset))
        }
        return sb.toString()
    }
}

class MobiDecompressException(message: String) : Exception(message)
