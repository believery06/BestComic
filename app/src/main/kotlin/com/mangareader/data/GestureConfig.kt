package com.mangareader.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全手势自定义系统
 * 屏幕划分多个点击区域，每个区域可映射不同动作
 * 支持单击/双击/长按/双指等多种手势
 */
@Serializable
data class GestureConfig(
    val tapLeft: GestureAction = GestureAction.PREV_PAGE,
    val tapRight: GestureAction = GestureAction.NEXT_PAGE,
    val tapTop: GestureAction = GestureAction.NOTHING,
    val tapBottom: GestureAction = GestureAction.NOTHING,
    val tapCenter: GestureAction = GestureAction.TOGGLE_MENU,
    val doubleTap: GestureAction = GestureAction.ZOOM_TOGGLE,
    val longPress: GestureAction = GestureAction.MAGNIFIER,
    val swipeLeft: GestureAction = GestureAction.NEXT_PAGE,
    val swipeRight: GestureAction = GestureAction.PREV_PAGE,
    val swipeUp: GestureAction = GestureAction.NOTHING,
    val swipeDown: GestureAction = GestureAction.NOTHING,
    val twoFingerTap: GestureAction = GestureAction.NOTHING,
    val pinchIn: GestureAction = GestureAction.NOTHING,
    val pinchOut: GestureAction = GestureAction.NOTHING,
    /** 预设名称 */
    val presetName: String = "自定义",
    /** 左右翻页区域占比（0.1-0.45） */
    val tapZoneRatio: Float = 0.25f,
    /** 顶部菜单区域占比 */
    val topZoneRatio: Float = 0.12f,
    /** 底部菜单区域占比 */
    val bottomZoneRatio: Float = 0.12f
) {
    enum class GestureAction {
        NOTHING,        // 无操作
        PREV_PAGE,      // 上一页
        NEXT_PAGE,      // 下一页
        TOGGLE_MENU,    // 切换菜单
        ZOOM_TOGGLE,    // 切换缩放
        MAGNIFIER,      // 放大镜
        AUTO_CROP_TOGGLE, // 切换裁边
        BRIGHTNESS_UP,  // 亮度+
        BRIGHTNESS_DOWN,// 亮度-
        NEXT_CHAPTER,   // 下一章
        PREV_CHAPTER,   // 上一章
        ROTATE,         // 旋转
        SLIDESHOW_TOGGLE, // 幻灯片
        PANEL_VIEW,     // 分镜模式
        BOOKMARK,       // 书签
        BACK,           // 返回
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** 预设：日漫模式（左=下一页，右=上一页） */
        val PRESET_MANGA = GestureConfig(
            tapLeft = GestureAction.NEXT_PAGE,
            tapRight = GestureAction.PREV_PAGE,
            tapCenter = GestureAction.TOGGLE_MENU,
            doubleTap = GestureAction.ZOOM_TOGGLE,
            longPress = GestureAction.MAGNIFIER,
            swipeLeft = GestureAction.NEXT_PAGE,
            swipeRight = GestureAction.PREV_PAGE,
            presetName = "日漫模式"
        )

        /** 预设：国漫模式（左=上一页，右=下一页） */
        val PRESET_COMIC = GestureConfig(
            tapLeft = GestureAction.PREV_PAGE,
            tapRight = GestureAction.NEXT_PAGE,
            tapCenter = GestureAction.TOGGLE_MENU,
            doubleTap = GestureAction.ZOOM_TOGGLE,
            longPress = GestureAction.MAGNIFIER,
            swipeLeft = GestureAction.NEXT_PAGE,
            swipeRight = GestureAction.PREV_PAGE,
            presetName = "国漫模式"
        )

        /** 预设：简洁模式（仅滑动翻页） */
        val PRESET_SIMPLE = GestureConfig(
            tapLeft = GestureAction.NOTHING,
            tapRight = GestureAction.NOTHING,
            tapCenter = GestureAction.TOGGLE_MENU,
            doubleTap = GestureAction.ZOOM_TOGGLE,
            longPress = GestureAction.MAGNIFIER,
            swipeLeft = GestureAction.NEXT_PAGE,
            swipeRight = GestureAction.PREV_PAGE,
            presetName = "简洁模式"
        )

        val PRESETS = mapOf(
            "manga" to PRESET_MANGA,
            "comic" to PRESET_COMIC,
            "simple" to PRESET_SIMPLE
        )
    }
}

/**
 * 手势配置管理器
 */
class GestureConfigManager(private val context: Context) {

    fun getConfig(): GestureConfig {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("gesture_config", null) ?: return GestureConfig.PRESET_COMIC
        return try {
            GestureConfig.json.decodeFromString(json)
        } catch (_: Exception) {
            GestureConfig.PRESET_COMIC
        }
    }

    fun saveConfig(config: GestureConfig) {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("gesture_config", GestureConfig.json.encodeToString(config)).apply()
    }

    fun applyPreset(presetKey: String): GestureConfig {
        val config = GestureConfig.PRESETS[presetKey] ?: GestureConfig.PRESET_COMIC
        saveConfig(config)
        return config
    }
}

/** 计算点击区域 */
fun GestureConfig.getTapZone(
    x: Float, width: Float, y: Float, height: Float, tapZoneSize: TapZoneSize
): TapZone {
    // 根据设置动态调整点击区域比例
    val (ratioX, ratioY) = when (tapZoneSize) {
        TapZoneSize.SMALL -> 0.15f to 0.08f // 菜单区域很大
        TapZoneSize.MEDIUM -> 0.25f to 0.12f // 默认
        TapZoneSize.LARGE -> 0.35f to 0.16f // 翻页区域很大
    }
    
    val leftBound = width * ratioX
    val rightBound = width - leftBound
    val topBound = height * ratioY
    val bottomBound = height - topBound

    return when {
        y < topBound -> TapZone.TOP
        y > bottomBound -> TapZone.BOTTOM
        x < leftBound -> TapZone.LEFT
        x > rightBound -> TapZone.RIGHT
        else -> TapZone.CENTER
    }
}

enum class TapZone {
    LEFT, RIGHT, CENTER, TOP, BOTTOM
}