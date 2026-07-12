package com.mangareader.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AI智能分镜检测器
 * 使用边缘检测 + 连通域分析识别漫画格子分镜
 */
object PanelDetector {

    /** 最小分镜面积占比（过滤噪点） */
    private const val MIN_PANEL_AREA_RATIO = 0.008f
    /** 最大分镜面积占比（过滤整页识别） */
    private const val MAX_PANEL_AREA_RATIO = 0.92f
    /** 边缘检测阈值 */
    private const val EDGE_THRESHOLD = 60
    /** 采样步长（性能优化，每隔N像素采样） */
    private const val SAMPLE_STEP = 3

    data class Panel(
        val bounds: Rect,
        val index: Int
    )

    /**
     * 检测漫画页面中的所有分镜
     * @param bitmap 漫画页面位图
     * @param rtl 是否从右到左阅读（日漫模式）
     * @return 排序后的分镜列表
     */
    suspend fun detectPanels(bitmap: Bitmap, rtl: Boolean = false): List<Panel> =
        withContext(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 100 || h < 100) return@withContext emptyList()

            // 缩小采样以提高性能
            val scale = min(400f / max(w, h), 1f)
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, true)

            val pixels = IntArray(sw * sh)
            scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)

            // 1. 边缘检测（Sobel简化版）
            val edges = BooleanArray(sw * sh)
            for (y in 1 until sh - 1 step SAMPLE_STEP) {
                for (x in 1 until sw - 1 step SAMPLE_STEP) {
                    val idx = y * sw + x
                    val gx = grayDiff(pixels[idx + 1], pixels[idx - 1])
                    val gy = grayDiff(pixels[idx + sw], pixels[idx - sw])
                    edges[idx] = (gx * gx + gy * gy) > EDGE_THRESHOLD * EDGE_THRESHOLD
                }
            }

            // 对边缘图做膨胀，连接断开的边缘
            val dilated = BooleanArray(sw * sh)
            for (y in 1 until sh - 1) {
                for (x in 1 until sw - 1) {
                    val idx = y * sw + x
                    if (edges[idx]) {
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val nidx = (y + dy) * sw + (x + dx)
                                if (nidx in dilated.indices) dilated[nidx] = true
                            }
                        }
                    }
                }
            }

            // 2. 扫描水平/垂直投影找分界线
            val hProj = IntArray(sh)
            val vProj = IntArray(sw)
            for (y in 0 until sh) {
                var count = 0
                for (x in 0 until sw) {
                    if (dilated[y * sw + x]) count++
                }
                hProj[y] = count
            }
            for (x in 0 until sw) {
                var count = 0
                for (y in 0 until sh) {
                    if (dilated[y * sw + x]) count++
                }
                vProj[x] = count
            }

            // 找水平分界线（行间隙）
            val hGaps = findGaps(hProj, sh, sw)
            // 找垂直分界线（列间隙）
            val vGaps = findGaps(vProj, sw, sh)

            // 3. 生成分镜区域
            val panels = generatePanels(hGaps, vGaps, sw, sh, w, h, scale)

            // 4. 按阅读顺序排序
            val result = sortPanels(panels, rtl)
            // 释放缩略图，避免内存泄漏
            if (scaled !== bitmap && !scaled.isRecycled) {
                runCatching { scaled.recycle() }
            }
            result
        }

    private fun grayDiff(a: Int, b: Int): Int {
        val ga = ((Color.red(a) + Color.green(a) + Color.blue(a)) / 3)
        val gb = ((Color.red(b) + Color.green(b) + Color.blue(b)) / 3)
        return ga - gb
    }

    private fun findGaps(proj: IntArray, length: Int, otherDim: Int): List<Int> {
        val gaps = mutableListOf<Int>()
        val threshold = (otherDim * 0.15f).toInt()
        var inGap = false
        var gapStart = 0
        for (i in 0 until length) {
            if (proj[i] < threshold && !inGap) {
                inGap = true
                gapStart = i
            } else if (proj[i] >= threshold && inGap) {
                inGap = false
                if (i - gapStart >= 2) {
                    gaps.add((gapStart + i) / 2)
                }
            }
        }
        return gaps
    }

    private fun generatePanels(
        hGaps: List<Int>,
        vGaps: List<Int>,
        sw: Int, sh: Int, w: Int, h: Int, scale: Float
    ): List<Panel> {
        val rows = listOf(0) + hGaps + listOf(sh)
        val cols = listOf(0) + vGaps + listOf(sw)

        val panels = mutableListOf<Panel>()
        val minArea = sw * sh * MIN_PANEL_AREA_RATIO
        val maxArea = sw * sh * MAX_PANEL_AREA_RATIO

        for (ri in 0 until rows.size - 1) {
            for (ci in 0 until cols.size - 1) {
                val left = (cols[ci] / scale).toInt().coerceIn(0, w)
                val top = (rows[ri] / scale).toInt().coerceIn(0, h)
                val right = (cols[ci + 1] / scale).toInt().coerceIn(0, w)
                val bottom = (rows[ri + 1] / scale).toInt().coerceIn(0, h)

                val area = (right - left) * (bottom - top)
                if (area < minArea || area > maxArea) continue

                panels.add(Panel(
                    bounds = Rect(left, top, right, bottom),
                    index = panels.size
                ))
            }
        }
        return panels
    }

    /**
     * 按阅读顺序排序分镜
     * 日漫(RTL)：从右到左、从上到下
     * 国漫(LTR)：从左到右、从上到下
     */
    private fun sortPanels(panels: List<Panel>, rtl: Boolean): List<Panel> {
        // 先按行分组（y坐标）
        val rowGroups = mutableListOf<MutableList<Panel>>()
        val sortedByY = panels.sortedBy { it.bounds.top }

        for (panel in sortedByY) {
            var placed = false
            for (group in rowGroups) {
                val avgTop = group.map { it.bounds.top }.average().toInt()
                if (abs(panel.bounds.top - avgTop) < (panel.bounds.height() * 0.3f)) {
                    group.add(panel)
                    placed = true
                    break
                }
            }
            if (!placed) {
                rowGroups.add(mutableListOf(panel))
            }
        }

        // 每行内按x排序
        val sorted = mutableListOf<Panel>()
        for (group in rowGroups) {
            if (rtl) {
                group.sortByDescending { it.bounds.left }
            } else {
                group.sortBy { it.bounds.left }
            }
            sorted.addAll(group)
        }

        return sorted.mapIndexed { i, p -> p.copy(index = i) }
    }
}