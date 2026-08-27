package com.example.blindguideapp.ui.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * 對應 backend/family_pairing.js 的兩支查詢 API：
 *   GET /api/family/navigation-history
 *   GET /api/family/navigation-history/:navigation_id
 */

@Serializable
data class NavigationRecordDto(
    val navigation_id: Int,
    val start_address: String? = null,
    val end_address: String? = null,
    val distance_meters: Int? = null,
    val duration_seconds: Int? = null,
    val alert_count: Int = 0,
    val status: String? = null,
    val started_at: String? = null,
    val ended_at: String? = null,
    val created_at: String? = null
)

@Serializable
data class NavigationHistoryResponse(
    val success: Boolean,
    val pairing_code: String? = null,
    val records: List<NavigationRecordDto>? = null,
    val message: String? = null
)

@Serializable
data class LatLngDto2(val lat: Double, val lng: Double)

@Serializable
data class NavigationDetailDto(
    val navigation_id: Int,
    val start_address: String? = null,
    val end_address: String? = null,
    val distance_meters: Int? = null,
    val duration_seconds: Int? = null,
    val status: String? = null,
    val started_at: String? = null,
    val ended_at: String? = null
)

@Serializable
data class AlertDto(
    val detection_id: Int,
    val object_name: String,
    val confidence: Double? = null,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val occurred_at: String? = null
)

@Serializable
data class StayPointDto(
    val stay_point_id: Int,
    val latitude: Double,
    val longitude: Double,
    val arrived_at: String? = null,
    val left_at: String? = null,
    val duration_seconds: Int
)

@Serializable
data class NavigationDetailResponse(
    val success: Boolean,
    val navigation: NavigationDetailDto? = null,
    val path: List<LatLngDto2>? = null,
    val alerts: List<AlertDto>? = null,
    val stay_points: List<StayPointDto>? = null,
    val message: String? = null
)

private val familyJson = Json { ignoreUnknownKeys = true }
private val familyClient = OkHttpClient()

@Serializable
private data class FamilyApiErrorDetail(val code: String? = null, val message: String? = null)

@Serializable
private data class FamilyApiErrorEnvelope(val success: Boolean = false, val error: FamilyApiErrorDetail? = null)

/**
 * 後端失敗時回傳的是 { success:false, error:{ code, message } }，訊息包在 error 裡面，
 * 不是最外層的 message 欄位。之前這裡漏了解析這一層，導致不管後端真正的錯誤是什麼，
 * 畫面永遠只會顯示同一句沒有資訊量的「讀取失敗」，看不出真正卡在哪裡。
 */
private fun extractFamilyApiErrorMessage(bodyString: String?, httpCode: Int): String {
    if (bodyString != null) {
        val parsedMessage = runCatching {
            familyJson.decodeFromString(FamilyApiErrorEnvelope.serializer(), bodyString).error?.message
        }.getOrNull()
        if (!parsedMessage.isNullOrBlank()) return parsedMessage
    }
    return "伺服器錯誤 (代碼 $httpCode)"
}

suspend fun fetchNavigationHistory(
    serverUrl: String,
    pairingCode: String
): NavigationHistoryResponse = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$serverUrl/api/family/navigation-history?pairing_code=$pairingCode")
        .get()
        .build()

    try {
        familyClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext NavigationHistoryResponse(
                    false,
                    message = extractFamilyApiErrorMessage(bodyString, response.code)
                )
            }
            if (bodyString == null) return@withContext NavigationHistoryResponse(false, message = "回應為空")
            familyJson.decodeFromString(NavigationHistoryResponse.serializer(), bodyString)
        }
    } catch (e: Exception) {
        NavigationHistoryResponse(false, message = "連線失敗: ${e.localizedMessage}")
    }
}

suspend fun fetchNavigationDetail(
    serverUrl: String,
    navigationId: Int,
    pairingCode: String
): NavigationDetailResponse = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$serverUrl/api/family/navigation-history/$navigationId?pairing_code=$pairingCode")
        .get()
        .build()

    try {
        familyClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext NavigationDetailResponse(
                    false,
                    message = extractFamilyApiErrorMessage(bodyString, response.code)
                )
            }
            if (bodyString == null) return@withContext NavigationDetailResponse(false, message = "回應為空")
            familyJson.decodeFromString(NavigationDetailResponse.serializer(), bodyString)
        }
    } catch (e: Exception) {
        NavigationDetailResponse(false, message = "連線失敗: ${e.localizedMessage}")
    }
}
