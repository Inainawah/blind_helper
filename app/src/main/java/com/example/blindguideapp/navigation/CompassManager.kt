package com.example.blindguideapp.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * 監聽 Sensor.TYPE_ROTATION_VECTOR，換算出手機/使用者身體目前朝向的方位角
 * （θ_phone，0°=正北，順時針，範圍 0~360）。
 *
 * 使用旋轉向量感測器而非單純磁力計，可同時融合陀螺儀資料，在使用者行走、
 * 手部擺動時仍維持較穩定的朝向讀數。
 */
class CompassManager(
    context: Context,
    private val onAzimuthChanged: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val display =
        context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    // 低通濾波，避免手持晃動造成語音方向頻繁抖動。
    private var smoothedAzimuth: Float? = null
    private val smoothingFactor = 0.15f

    fun start() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 依螢幕旋轉方向修正矩陣，確保直向/橫向持機時方位角仍正確
        // （此 App 已鎖定橫向 landscape，但保留修正以支援未來旋轉）。
        val (axisX, axisY) = remapAxesForRotation()
        val remappedMatrix = FloatArray(9)
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

        SensorManager.getOrientation(remappedMatrix, orientationValues)
        val azimuthRadians = orientationValues[0]
        val azimuthDegrees = normalizeAngle(Math.toDegrees(azimuthRadians.toDouble()).toFloat())

        val previous = smoothedAzimuth
        val smoothed = if (previous == null) {
            azimuthDegrees
        } else {
            // 對圓周角度做低通濾波：先算出最短夾角差，再套用平滑係數。
            val diff = normalizeAngleDiff(azimuthDegrees - previous)
            normalizeAngle(previous + diff * smoothingFactor)
        }
        smoothedAzimuth = smoothed
        onAzimuthChanged(smoothed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun remapAxesForRotation(): Pair<Int, Int> {
        val rotation = display?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}
