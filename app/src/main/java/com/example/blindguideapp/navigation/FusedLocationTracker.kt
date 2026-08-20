package com.example.blindguideapp.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 封裝 FusedLocationProviderClient，每 1~2 秒回報一次高精度 GPS 座標，
 * 供 [TurnByTurnGuide] 計算與下一個轉折點的距離。
 *
 * 呼叫端需自行確認已取得 ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION 權限
 * （既有 MainScreen 已於進入相機畫面前完成此檢查）。
 */
class FusedLocationTracker(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 1500L, onLocation: (Location) -> Unit) {
        stop()

        val request = LocationRequest.Builder(intervalMs)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocation)
            }
        }
        callback = newCallback

        client.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
