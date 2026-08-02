package com.mangareader.parser.mobi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PalmDocDecoderTest {

    @Test
    fun `decompress literal bytes`() {
        // 0x01 followed by 1 literal byte
        val input = byteArrayOf(0x01, 0x41) // 1 literal byte 'A'
        val result = PalmDocDecoder.decompress(input)
        assertArrayEquals(byteArrayOf(0x41), result)
    }

    @Test
    fun `decompress multiple literal bytes`() {
        // 0x03 followed by 3 literal bytes
        val input = byteArrayOf(0x03, 0x41, 0x42, 0x43) // "ABC"
        val result = PalmDocDecoder.decompress(input)
        assertArrayEquals("ABC".toByteArray(), result)
    }

    @Test
    fun `decompress direct literal`() {
        // 0x09..0x7F are direct literal bytes
        val input = byteArrayOf(0x41, 0x42, 0x43) // "ABC" directly
        val result = PalmDocDecoder.decompress(input)
        assertArrayEquals("ABC".toByteArray(), result)
    }

    @Test
    fun `decompress null byte`() {
        val input = byteArrayOf(0x00)
        val result = PalmDocDecoder.decompress(input)
        assertArrayEquals(byteArrayOf(0x00), result)
    }

    @Test
    fun `decompress space plus char`() {
        // 0xC0 + 0x80 = space + 0x00 (but 0x00 is handled separately)
        // 0xC1 = space + 0x01, etc.
        // Let's test 0xC0 ^ 0x80 = 0x40 -> space + '@'
        val input = byteArrayOf(0xC0.toByte())
        val result = PalmDocDecoder.decompress(input)
        // c=0xC0 >= 0xC0: space + (0xC0 xor 0x80) = space + 0x40 = " @"
        assertArrayEquals(" @".toByteArray(), result)
    }

    @Test
    fun `decompress LZ77 match`() {
        // First: output "AB" as literals
        // Then: LZ77 match with distance=2, length=3 -> copies "AB" starting from 2 back
        // Input: 0x02 'A' 'B'  (2 literal bytes "AB")
        // Then: 0x80 0x10 -> distance = (0x8010 >> 3) & 0x7FF = 2, length = (0x10 & 7) + 3 = 3
        val input = byteArrayOf(
            0x02, 0x41, 0x42,  // "AB" literals
            0x80.toByte(), 0x10  // LZ77: distance=2, length=3
        )
        val result = PalmDocDecoder.decompress(input)
        // Output: "AB" + copy from distance 2, length 3 = "ABA"
        assertArrayEquals("ABABA".toByteArray(), result)
    }

    @Test
    fun `decompress empty input`() {
        val input = byteArrayOf()
        val result = PalmDocDecoder.decompress(input)
        assertEquals(0, result.size)
    }

    @Test
    fun `decompress mixed content`() {
        // Simulate a realistic PalmDOC stream
        // "Hello" as literals + LZ77 back-reference
        val input = byteArrayOf(
            0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F,  // "Hello" literals
            0x20,  // literal space
            0x80.toByte(), 0x28  // LZ77: distance = (0x8028>>3)&0x7FF = 5, length = (0x28&7)+3 = 3
        )
        val result = PalmDocDecoder.decompress(input)
        // Output: "Hello " (6 bytes, pos=6)
        // LZ77: distance=5 -> copy from pos 6-5=1, length=3 -> "ell"
        // Final: "Hello ell"
        assertArrayEquals("Hello ell".toByteArray(), result)
    }
}
