package com.example.blindguideapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * App 目前沒有登入機制，因此用「裝置配對碼」代表身分：
 *   - 每台裝置第一次啟動時，在本機產生一組 UUID 當作 device_id，
 *     並呼叫 POST /api/devices/register 換取一組 user_id + 6 碼配對碼
 *     （見 backend/family_pairing.js）。
 *   - user_id 之後直接沿用既有的 /api/navigation/directions、
 *     /api/environment-logs 等 API，不需要修改任何既有呼叫的資料結構。
 *   - pairing_code 顯示在家屬模式畫面，供查詢自己這台裝置的導航紀錄。
 */
data class DeviceProfile(
    val userId: Int,
    val pairingCode: String,
    val displayName: String
)

class DeviceIdentityManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }

    fun cachedProfile(): DeviceProfile? {
        val userId = prefs.getInt(KEY_USER_ID, -1)
        val pairingCode = prefs.getString(KEY_PAIRING_CODE, null)
        if (userId <= 0 || pairingCode.isNullOrBlank()) return null
        return DeviceProfile(userId, pairingCode, prefs.getString(KEY_DISPLAY_NAME, "") ?: "")
    }

    private fun saveProfile(profile: DeviceProfile) {
        prefs.edit()
            .putInt(KEY_USER_ID, profile.userId)
            .putString(KEY_PAIRING_CODE, profile.pairingCode)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .apply()
    }

    /** 若尚未註冊過，向後端註冊一次並快取結果；已註冊過則直接回傳快取。 */
    suspend fun ensureRegistered(serverUrl: String): DeviceProfile? = withContext(Dispatchers.IO) {
        cachedProfile()?.let { return@withContext it }

        val bodyJson = json.encodeToString(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(device_id = deviceId)
        )
        val request = Request.Builder()
            .url("$serverUrl/api/devices/register")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: return@use null
                val result = json.decodeFromString(RegisterDeviceResponse.serializer(), bodyString)
                if (!response.isSuccessful || !result.success ||
                    result.user_id == null || result.pairing_code == null
                ) {
                    return@use null
                }
                DeviceProfile(result.user_id, result.pairing_code, result.display_name ?: "").also {
                    saveProfile(it)
                }
            }
        }.onFailure { it.printStackTrace() }.getOrNull()
    }

    suspend fun regenerateCode(serverUrl: String): String? = withContext(Dispatchers.IO) {
        val profile = cachedProfile() ?: return@withContext null
        val request = Request.Builder()
            .url("$serverUrl/api/devices/${profile.userId}/regenerate-code")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: return@use null
                val result = json.decodeFromString(RegenerateCodeResponse.serializer(), bodyString)
                if (!response.isSuccessful || !result.success || result.pairing_code == null) {
                    return@use null
                }
                prefs.edit().putString(KEY_PAIRING_CODE, result.pairing_code).apply()
                result.pairing_code
            }
        }.onFailure { it.printStackTrace() }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "blind_helper_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DISPLAY_NAME = "display_name"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class RegisterDeviceRequest(val device_id: String)

@Serializable
data class RegisterDeviceResponse(
    val success: Boolean,
    val user_id: Int? = null,
    val pairing_code: String? = null,
    val display_name: String? = null
)

@Serializable
data class RegenerateCodeResponse(
    val success: Boolean,
    val pairing_code: String? = null
)
