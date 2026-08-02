package com.mangareader.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 光线传感器自动亮度管理器。
 *
 * 根据环境光照强度（lux）计算建议的屏幕亮度倍率（0.5f ~ 2.0f）。
 * 暗光环境降低亮度，强光环境提高亮度，避免刺眼或看不清。
 */
class BrightnessSensorManager(context: Context) {

    // 使用 Application Context 获取系统服务，避免持有 Activity 上下文导致泄漏。
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** 设备是否具备光线传感器。 */
    val isAvailable: Boolean get() = lightSensor != null

    private val _brightness = mutableFloatStateOf(1f)
    val brightness: State<Float> = _brightness

    private var lastBrightness: Float = 1f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val lux = event?.values?.firstOrNull() ?: return
            val target = luxToBrightness(lux)
            // 低通滤波 + 阈值过滤：避免光线微弱抖动导致 Compose 频繁重组。
            val smoothed = lastBrightness * 0.7f + target * 0.3f
            if (kotlin.math.abs(smoothed - lastBrightness) < 0.03f) return
            lastBrightness = smoothed
            _brightness.floatValue = smoothed
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(): Boolean {
        val sensor = lightSensor ?: run {
            Log.w(TAG, "设备没有光线传感器，自动亮度功能不可用")
            return false
        }
        val sm = sensorManager ?: return false
        // SENSOR_DELAY_NORMAL 约 200ms 采样一次，比 UI 级别更省电，配合滤波足够平滑。
        return sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
    }

    companion object {
        private const val TAG = "BrightnessSensorManager"

        /**
         * 将环境光照强度映射为亮度倍率。
         * 0 lux -> 0.5f, 100 lux -> 0.8f, 1000 lux -> 1.0f, 10000 lux -> 1.5f, 50000 lux -> 2.0f
         */
        fun luxToBrightness(lux: Float): Float {
            if (lux <= 0f) return 0.5f
            val logLux = kotlin.math.log10(lux.coerceAtLeast(1f))
            // log10(1)=0 -> 0.5, log10(1000)=3 -> 1.0, log10(10000)=4 -> 1.35, log10(50000)~4.7 -> 1.7
            val value = 0.5f + logLux * 0.25f
            return value.coerceIn(0.5f, 2.0f)
        }
    }
}

@Composable
fun rememberBrightnessSensorManager(enabled: Boolean): State<Float> {
    val context = LocalContext.current
    val manager = remember { BrightnessSensorManager(context) }
    DisposableEffect(enabled) {
        if (enabled) manager.start() else manager.stop()
        onDispose { manager.stop() }
    }
    return manager.brightness
}
