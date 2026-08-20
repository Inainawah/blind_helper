package com.example.blindguideapp.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 輕量座標型別，避免此套件依賴 android.location.Location。
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

private const val EARTH_RADIUS_M = 6371000.0

/**
 * Haversine 公式計算兩點間距離（公尺）。
 */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = Math.toRadians(b.latitude - a.latitude)
    val deltaLng = Math.toRadians(b.longitude - a.longitude)

    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLng / 2) * sin(deltaLng / 2)
    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return EARTH_RADIUS_M * c
}

/**
 * 計算從 a 走到 b 的初始方位角（0°=正北，順時針，範圍 0~360）。
 * 這就是 Routes API steps 中，路線本身的 "θ_route"。
 */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Float {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLng = Math.toRadians(b.longitude - a.longitude)

    val y = sin(deltaLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)
    val bearing = Math.toDegrees(atan2(y, x))
    return normalizeAngle(bearing.toFloat())
}

/**
 * 將任意角度正規化到 [0, 360) 區間。
 */
fun normalizeAngle(degrees: Float): Float {
    var result = degrees % 360f
    if (result < 0f) result += 360f
    return result
}

/**
 * 計算兩角度的最小夾角差，結果落在 (-180, 180]。
 * 正值代表 target 在 reference 的順時針方向（右側），負值代表逆時針（左側）。
 */
fun normalizeAngleDiff(diffDegrees: Float): Float {
    var result = diffDegrees % 360f
    if (result <= -180f) result += 360f
    if (result > 180f) result -= 360f
    return result
}

/**
 * 計算「從目前朝向(reference) 轉到目標方位(target)」需要轉幾度。
 */
fun angleDiffTo(reference: Float, target: Float): Float =
    normalizeAngleDiff(target - reference)
