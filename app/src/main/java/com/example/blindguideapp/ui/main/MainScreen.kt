package com.example.blindguideapp.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.location.Location
import android.location.LocationManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.example.blindguideapp.YoloDetector
import com.example.blindguideapp.data.DeviceIdentityManager
import com.example.blindguideapp.data.DeviceProfile
import com.example.blindguideapp.navigation.CompassManager
import com.example.blindguideapp.navigation.FusedLocationTracker
import com.example.blindguideapp.navigation.GeoPoint
import com.example.blindguideapp.navigation.GuidanceVibrator
import com.example.blindguideapp.navigation.TurnByTurnGuide
import com.example.blindguideapp.ui.family.FamilyModeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class AlertLog(val id: Long, val message: String, val timestamp: String)

/** 尚未成功講完的導航語音內容，供警報打斷後補講、並追蹤已被打斷幾次。 */
private data class PendingNavigationSpeech(val text: String, val interruptedCount: Int)

/** 導航語音連續被打斷達到這個次數，就改用強制插播，確保最終一定講得出來。 */
private const val NAVIGATION_SPEECH_FORCE_INTERRUPT_THRESHOLD = 2

/** 盲人模式（既有的相機/導航 UI）／家屬模式（新增）。 */
private enum class AppMode { BLIND, FAMILY }

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var appMode by remember { mutableStateOf(AppMode.BLIND) }
    var serverUrl by remember { mutableStateOf("https://believable-emotion-production-5e75.up.railway.app") }

    // 裝置配對碼身分（見 data/DeviceIdentity.kt），家屬模式跟盲人模式共用同一份，
    // 這樣家屬模式一打開就能立刻看到自己這台裝置的配對碼與導航紀錄。
    val deviceIdentityManager = remember { DeviceIdentityManager(context) }
    var deviceProfile by remember { mutableStateOf(deviceIdentityManager.cachedProfile()) }
    val identityCoroutineScope = rememberCoroutineScope()

    LaunchedEffect(serverUrl) {
        if (deviceProfile == null) {
            deviceProfile = deviceIdentityManager.ensureRegistered(serverUrl)
        }
    }

    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // 狀態變數
    var hasPermissions by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { grantedMap ->
            hasPermissions = grantedMap.values.all { it }
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasPermissions) {
            launcher.launch(permissions)
        }
    }

    if (hasPermissions) {
        when (appMode) {
            AppMode.BLIND -> CameraDetectionLayout(
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                deviceProfile = deviceProfile,
                onSwitchToFamilyMode = { appMode = AppMode.FAMILY },
                modifier = modifier
            )

            AppMode.FAMILY -> FamilyModeScreen(
                serverUrl = serverUrl,
                deviceProfile = deviceProfile,
                onRegenerateCode = {
                    identityCoroutineScope.launch {
                        val newCode = deviceIdentityManager.regenerateCode(serverUrl)
                        if (newCode != null) {
                            deviceProfile = deviceProfile?.copy(pairingCode = newCode)
                        }
                    }
                },
                onRetryRegistration = {
                    identityCoroutineScope.launch {
                        if (deviceProfile == null) {
                            deviceProfile = deviceIdentityManager.ensureRegistered(serverUrl)
                        }
                    }
                },
                onSwitchToBlindMode = { appMode = AppMode.BLIND },
                modifier = modifier
            )
        }
    } else {
        PermissionDeniedScreen(onRequestPermission = { launcher.launch(permissions) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraDetectionLayout(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    deviceProfile: DeviceProfile?,
    onSwitchToFamilyMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val lastAlertFinishedTime = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val lastAlertStartTime = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // 初始化 TTS 語音引擎
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsInitialized by remember { mutableStateOf(false) }

    // 記錄「導航轉彎提示」目前正在播放/排隊的內容（utteranceId -> 內容 + 已被打斷次數）。
    // 相機危險警報有時候會用「立刻打斷」的方式插播，如果剛好打斷到導航語音，
    // 靠這份記錄在警報講完後把被打斷的那句導航提示補講一次，避免使用者漏聽轉彎資訊。
    // 在路口這種警報密集的地方，同一句話可能被連續打斷好幾次，所以還要記錄
    // 「已經被打斷幾次」，超過門檻就讓導航語音改用強制插播，確保最終一定講得完。
    val navigationUtteranceRegistry =
        remember { java.util.concurrent.ConcurrentHashMap<String, PendingNavigationSpeech>() }

    // 導航路徑語音正在講的時候是 true；期間相機警報一律不打斷它，改成排隊等它
    // 講完再放，確保路徑指示一定能完整講完。
    val isNavigationSpeaking = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // 開啟 App 時的歡迎與操作說明語音正在播放中；播放期間相機障礙物警報暫停播報，
    // 避免剛開 App 就被障礙物警報（尤其是 QUEUE_FLUSH 緊急警報）直接截斷中斷。
    val isIntroSpeaking = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    LaunchedEffect(ttsInitialized) {
        if (ttsInitialized) {
            isIntroSpeaking.set(true)
            val result = tts?.speak(
                "歡迎使用vigo。本 App 會透過相機，為您辨識障礙物，並用語音提醒您方向。請點擊螢幕右上角並說出目的地後，開始為您導航。",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome_intro"
            )
            if (result != TextToSpeech.SUCCESS) {
                isIntroSpeaking.set(false)
            }
        }
    }

    DisposableEffect(Unit) {
        lateinit var ttsEngine: TextToSpeech
        ttsEngine = TextToSpeech(context) { status ->
            // 這個 callback 才是「語音引擎真正綁定完成」的時間點，
            // 一定要等這裡才能設定語言/語速/音訊屬性，太早設定會失敗
            // （之前就是因為在這之前就設定，導致「設成中文」這個動作沒生效，
            // 語音引擎停留在預設語言，講中文內容時完全沒有聲音）。
            if (status == TextToSpeech.SUCCESS) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                ttsEngine.setAudioAttributes(audioAttributes)
                ttsEngine.setSpeechRate(1.3f)
                ttsEngine.language = Locale.CHINESE
                ttsInitialized = true
            }
        }

        ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == "welcome_intro") {
                    isIntroSpeaking.set(true)
                } else if (utteranceId != null && utteranceId.startsWith("turn_guide_")) {
                    isNavigationSpeaking.set(true)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "welcome_intro") {
                    isIntroSpeaking.set(false)
                    lastAlertFinishedTime.set(System.currentTimeMillis() + 500L)
                } else if (utteranceId != null) {
                    navigationUtteranceRegistry.remove(utteranceId)
                    if (utteranceId.startsWith("turn_guide_")) isNavigationSpeaking.set(false)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == "welcome_intro") {
                    isIntroSpeaking.set(false)
                } else if (utteranceId != null) {
                    navigationUtteranceRegistry.remove(utteranceId)
                    if (utteranceId.startsWith("turn_guide_")) isNavigationSpeaking.set(false)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == "welcome_intro") {
                    isIntroSpeaking.set(false)
                }
                // 導航語音被警報插播打斷（interrupted = true）：把同一句話重新排回去播放，
                // 不會就這樣消失不見。如果連續被打斷好幾次（路口警報密集的情況），
                // 就改用強制插播，確保使用者最終一定聽得到轉彎指示。
                if (!interrupted || utteranceId == null) return
                val pending = navigationUtteranceRegistry.remove(utteranceId) ?: return
                val interruptedCount = pending.interruptedCount + 1
                val retryId = "turn_guide_retry_${System.currentTimeMillis()}"
                navigationUtteranceRegistry[retryId] = PendingNavigationSpeech(pending.text, interruptedCount)
                val queueMode =
                    if (interruptedCount >= NAVIGATION_SPEECH_FORCE_INTERRUPT_THRESHOLD) {
                        TextToSpeech.QUEUE_FLUSH
                    } else {
                        TextToSpeech.QUEUE_ADD
                    }
                ttsEngine.speak(pending.text, queueMode, null, retryId)
            }
        })

        tts = ttsEngine

        onDispose {
            ttsEngine.stop()
            ttsEngine.shutdown()
        }
    }

    // 初始化 YOLO 辨識器與危險追蹤器
    val detector = remember { YoloDetector(context, "yolo26s_float32.tflite") }
    DisposableEffect(detector) {
        onDispose {
            detector.close()
        }
    }
    val tracker = remember { HazardTracker() }

    // ===== 視障三階段轉彎導航模組 =====
    // 嵌入既有的 Location/Routes 處理流程之下，不改動既有相機/警示 UI。
    val guidanceVibrator = remember { GuidanceVibrator(context) }
    val fusedLocationTracker = remember { FusedLocationTracker(context) }
    var compassAzimuth by remember { mutableStateOf(0f) }
    val compassManager = remember {
        CompassManager(context) { azimuth -> compassAzimuth = azimuth }
    }
    var activeGuide by remember { mutableStateOf<TurnByTurnGuide?>(null) }

    DisposableEffect(Unit) {
        compassManager.start()
        onDispose {
            compassManager.stop()
            fusedLocationTracker.stop()
        }
    }

    // 畫面狀態參數
    var detections by remember { mutableStateOf<List<YoloDetector.Detection>>(emptyList()) }
    var isFlashlightOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val alertLogs = remember { mutableStateListOf<AlertLog>() }
    var lastSpokenClassId by remember { mutableStateOf(-1) }
    var lastSpokenPriority by remember { mutableStateOf(0f) }
    var isListening by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("尚未收到語音指令") }
    var navigationResult by remember { mutableStateOf<DirectionsResponse?>(null) }
    var currentLatLng by remember { mutableStateOf<com.google.android.gms.maps.model.LatLng?>(null) }
    var navCurrentStepIndex by remember { mutableStateOf(0) }
    var navDistanceToTurn by remember { mutableStateOf(-1.0) }
    var showNavigationTab by remember { mutableStateOf(false) }
    // 目前這趟導航在後端的 navigation_id，供相機警報寫回 /api/environment-logs
    // 以及開始/結束時通知 /api/navigation/:id/start、/finish 使用（見下方 LaunchedEffect）。
    var currentNavigationId by remember { mutableStateOf<Int?>(null) }
    val coroutineScope = rememberCoroutineScope()

val speechRecognizer = remember {
    SpeechRecognizer.createSpeechRecognizer(context)
}

val speechIntent = remember {
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
}
DisposableEffect(Unit) {

    speechRecognizer.setRecognitionListener(
        object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                recognizedText = "正在聆聽..."
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                recognizedText = "辨識失敗 (錯誤碼: $error)"
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizedText = text ?: "沒有內容"
                if (!text.isNullOrBlank()) {
                    coroutineScope.launch {
                        val (lat, lng) = getCurrentLocation(context)
                        handleVoiceCommand(context, text, serverUrl, lat, lng, deviceProfile?.userId, tts) { result ->
                            navigationResult = result
                            if (result != null || text.contains("哪裡") || text.contains("在哪")) {
                                showNavigationTab = true
                            }
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {

                val text =
                    partialResults
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()

                if (!text.isNullOrBlank()) {
                    recognizedText = text
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    )

    onDispose {
        speechRecognizer.destroy()
    }
}

    // 導航結果一旦更新（使用者說出目的地並取得路線，或結束導航），
    // 就啟動/停止三階段轉彎提示。既有的 navigationResult 狀態與 UI 完全不受影響。
    LaunchedEffect(navigationResult) {
        fusedLocationTracker.stop()
        activeGuide = null
        currentLatLng = null
        navCurrentStepIndex = 0
        navDistanceToTurn = -1.0

        val result = navigationResult
        val navigationId = result?.navigation_id
        val userId = deviceProfile?.userId

        // 使用者說出新目的地、開始下一趟導航之前，先把「上一趟還在進行中、
        // 卻沒有人按過結束導航」的舊紀錄自動收尾（標記為 cancelled）。
        // 沒有這一段的話，只要中途換講別的目的地，舊的那筆紀錄會永遠卡在
        // 「進行中」、沒有結束時間，家屬模式看到的資料會一直不完整、
        // 時長也會持續往上跳動（顯示成好像還在走，但其實早就不是這趟了）。
        val previousNavigationId = currentNavigationId
        if (previousNavigationId != null &&
            previousNavigationId != navigationId &&
            userId != null
        ) {
            finishNavigationSession(serverUrl, previousNavigationId, userId, "cancelled")
        }

        currentNavigationId = navigationId

        // 通知後端這趟導航「開始了」，讓家屬模式看到的 started_at/status 正確
        // （見 backend/server.js -> PATCH /api/navigation/:id/start）。
        if (result?.success == true && navigationId != null && userId != null) {
            startNavigationSession(serverUrl, navigationId, userId)
        }

        val steps = result?.steps
        if (result?.success != true || steps.isNullOrEmpty()) return@LaunchedEffect

        val guideSteps = steps.mapNotNull { step ->
            val start = step.start_location
            val end = step.end_location
            if (start == null || end == null) {
                null
            } else {
                TurnByTurnGuide.GuideStep(
                    order = step.step_order,
                    instruction = step.instruction,
                    start = GeoPoint(start.lat, start.lng),
                    end = GeoPoint(end.lat, end.lng),
                    maneuver = step.maneuver
                )
            }
        }
        if (guideSteps.isEmpty()) return@LaunchedEffect

        val guide = TurnByTurnGuide(
            steps = guideSteps,
            speak = { text, flush ->
                val utteranceId = "turn_guide_${System.currentTimeMillis()}"
                // 記錄下來，如果這句話被相機警報中途打斷，才有辦法在警報講完後補講一次。
                navigationUtteranceRegistry[utteranceId] = PendingNavigationSpeech(text, interruptedCount = 0)
                tts?.speak(
                    text,
                    if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId
                )
            },
            vibrateShort = { guidanceVibrator.shortDoubleBuzz() },
            onCompleted = {
                tts?.speak(
                    "已到達目的地",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "navigation_arrived"
                )
                // 抵達之後除了通知後端這趟結束了，手機端的定位追蹤也要一起停掉。
                // 原本這裡只有呼叫 /finish，定位追蹤（fusedLocationTracker）沒有跟著
                // 停止，導致抵達後只要使用者沒有馬上講新目的地，手機還是會繼續
                // 每 1.5 秒定位、每 20 秒回報座標，而且會回報到「這筆已經標記完成」
                // 的舊 navigation_id 上，把不相關的座標混進這趟已結束導航的路徑跟
                // 停留點資料裡（實測發現抵達後幾分鐘、甚至幾十分鐘後還有座標混進來，
                // 其中還有一筆座標整個跳到一百多公里外，明顯是不相關的雜訊）。
                fusedLocationTracker.stop()
                if (navigationId != null && userId != null) {
                    coroutineScope.launch {
                        finishNavigationSession(serverUrl, navigationId, userId, "completed")
                    }
                }
            }
        )
        activeGuide = guide

        // 每隔一段時間（而不是每 1.5 秒都送）把座標回報給後端，
        // 讓家屬模式「查看詳情」能事後算出「在同一個地方停留超過 5 分鐘」
        // 的停留點，不用即時判斷、不用額外狀態。
        var lastLocationReportMs = 0L
        fusedLocationTracker.start(intervalMs = 1500L) { location ->
            currentLatLng = com.google.android.gms.maps.model.LatLng(location.latitude, location.longitude)
            guide.onLocation(GeoPoint(location.latitude, location.longitude))
            navCurrentStepIndex = guide.currentStepIndex
            navDistanceToTurn = guide.lastDistanceToTurn

            if (navigationId != null && userId != null) {
                val now = System.currentTimeMillis()
                if (now - lastLocationReportMs >= LOCATION_REPORT_INTERVAL_MS) {
                    lastLocationReportMs = now
                    coroutineScope.launch {
                        reportNavigationLocation(serverUrl, navigationId, userId, location.latitude, location.longitude)
                    }
                }
            }
        }
    }

    // 手機朝向每次更新都同步餵給正在進行的轉彎導航，供其重新計算「前後左右」
    // 與確認使用者是否已完成轉身。
    LaunchedEffect(compassAzimuth) {
        activeGuide?.onAzimuth(compassAzimuth)
    }

    // 狀態指示燈的呼吸燈動畫
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // 偵測到危險時觸發語音與紀錄（優先級與搶佔邏輯）
    LaunchedEffect(detections) {
        if (!ttsInitialized || isIntroSpeaking.get()) return@LaunchedEffect

        // 追蹤偵測物件並取得面積變化率
        val trackedResults = tracker.update(detections)
        if (trackedResults.isEmpty()) return@LaunchedEffect

        // 為每個追蹤的威脅計算綜合優先級分數
        val prioritizedThreats = trackedResults.map { (det, rate) ->
            val proximity = det.proximity
            val rateFactor = if (rate > 0f) rate / 15000f else 0f
            val priority = proximity * (1f + rateFactor)
            Triple(det, rate, priority)
        }

        // 尋找最高優先級的威脅
        val highestPriorityThreat = prioritizedThreats.maxByOrNull { it.third } ?: return@LaunchedEffect
        val (det, rate, priority) = highestPriorityThreat

        val currentTime = System.currentTimeMillis()
        val lockedUntil = lastAlertFinishedTime.get()
        val lastSpeakTime = lastAlertStartTime.get()

        // 緊急威脅：快速接近（變化率 >= 20000）或高優先級（優先級 >= 2.5）
        val isUrgent = rate >= 20000f || priority >= 2.5f

        // 冷卻搶佔規則：若是新類別或同類別優先級顯著增加（>0.5），緊急威脅會立即略過 3 秒冷卻時間。
        // 同時確保距離上次播報開始至少經過最小間隔（如 1.0 秒），避免不同類別間連續跳字結巴。
        val minSpeakDurationMs = 1000L
        val hasSpokenLongEnough = currentTime - lastSpeakTime >= minSpeakDurationMs
        val shouldPreempt = isUrgent && hasSpokenLongEnough && (det.classId != lastSpokenClassId || priority >= lastSpokenPriority + 0.5f)

        if (currentTime >= lockedUntil || shouldPreempt) {
            val name = det.labelTw
            val alertMsg = "${det.direction}有 $name"

            // 縮短緊急搶佔警報的冷卻時間以保持高度即時反應
            // 根據 1.3 倍語速調整估算時間
            val estimatedSpeechDurationMs = alertMsg.length * 250L + 300L
            val cooldownMs = if (isUrgent) 1000L else 3000L
            lastAlertFinishedTime.set(currentTime + estimatedSpeechDurationMs + cooldownMs)
            lastAlertStartTime.set(currentTime) // 記錄語音播報開始時間
            
            lastSpokenClassId = det.classId
            lastSpokenPriority = priority

            // 導航路徑語音正在講的時候，不管警報是不是緊急等級，一律不打斷它，
            // 排隊等它講完再放。其餘（緊急與否、冷卻時間、一般警報要不要排隊）
            // 維持原本邏輯不變。
            if (isNavigationSpeaking.get()) {
    tts?.speak(
        alertMsg,
        TextToSpeech.QUEUE_ADD,
        null,
        "queued_alert_$currentTime"
    )
} else if (isUrgent) {
    // 使用 QUEUE_FLUSH 立即插播搶佔
    tts?.speak(
        alertMsg,
        TextToSpeech.QUEUE_FLUSH,
        null,
        "urgent_alert_$currentTime"
    )
} else if (tts?.isSpeaking != true) {
    tts?.speak(
        alertMsg,
        TextToSpeech.QUEUE_ADD,
        null,
        "normal_alert_$currentTime"
    )
}

            // 新增日誌條目
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (alertLogs.size > 20) {
                alertLogs.removeAt(alertLogs.size - 1)
            }
            val logMessage = if (isUrgent) {
                "緊急警告: ${det.direction}有「$name」"
            } else {
                "危險警告: ${det.direction}有「$name」"
            }
            alertLogs.add(0, AlertLog(System.currentTimeMillis(), logMessage, timeStamp))

            // 只有在導航進行中才把警報寫回後端，讓家屬模式能算出這趟導航的
            // 「N 次警報」並在詳情地圖上標出位置（見 backend/server.js 的
            // /api/environment-logs，navigation_id 為選填欄位）。
            val navigationId = currentNavigationId
            val userId = deviceProfile?.userId
            if (navigationId != null && userId != null) {
                val (alertLat, alertLng) = getCurrentLocation(context)
                coroutineScope.launch {
                    reportEnvironmentAlert(
                        serverUrl = serverUrl,
                        userId = userId,
                        navigationId = navigationId,
                        objectName = name,
                        description = alertMsg,
                        latitude = alertLat,
                        longitude = alertLng
                    )
                }
            }
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 偵測目前螢幕方向
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLandscape) {
            // 橫向佈局：相機正方形置中，左右兩側為控制面板
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                // 左側面板：歷史警告日誌與導航
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .zIndex(1f)
                        .background(Color(0xFF121212))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showNavigationTab = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!showNavigationTab) Color(0xFFFFD54F) else Color(0x22FFFFFF),
                                contentColor = if (!showNavigationTab) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("警告日誌", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showNavigationTab = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showNavigationTab) Color(0xFF4DD0E1) else Color(0x22FFFFFF),
                                contentColor = if (showNavigationTab) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("導航指引", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = onSwitchToFamilyMode,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "切換到家屬模式" }
                        ) {
                            Text("👪", fontSize = 16.sp)
                        }
                    }

                    HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 1.dp)

                    if (!showNavigationTab) {
                        if (alertLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "尚無危險警告記錄，環境安全",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 13.sp
                               )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(alertLogs, key = { log -> log.id }) { log ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = log.message,
                                            color = Color(0xFFFF8A80),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = log.timestamp,
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val result = navigationResult
                        if (result == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "按住語音按鈕說話，詢問「我現在在哪裡」或說出目的地（例如「捷運淡水站」）以開始導航。",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "起點: ${result.start_address?.substringAfter("台灣") ?: ""}",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "終點: ${result.end_address?.substringAfter("台灣") ?: ""}",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "全程: ${result.distance ?: ""} / ${result.duration ?: ""}",
                                        color = Color(0xFF4DD0E1),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "離下個轉彎點: ${if (navDistanceToTurn < 0) "計算中" else String.format(java.util.Locale.US, "%.1f 公尺", navDistanceToTurn)} (第 ${navCurrentStepIndex + 1}/${result.steps?.size ?: 0} 步)",
                                        color = Color(0xFFFFD54F),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Button(
                                        onClick = {
                                            val navigationId = currentNavigationId
                                            val userId = deviceProfile?.userId
                                            if (navigationId != null && userId != null) {
                                                coroutineScope.launch {
                                                    finishNavigationSession(serverUrl, navigationId, userId, "cancelled")
                                                }
                                            }
                                            navigationResult = null
                                            showNavigationTab = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(28.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("結束導航", fontSize = 10.sp, color = Color.White)
                                    }
                                }

                                HorizontalDivider(color = Color(0x11FFFFFF), thickness = 1.dp)

                                val steps = result.steps ?: emptyList()
                                NavigationMap(
                                    currentLatLng = currentLatLng,
                                    steps = steps,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .padding(vertical = 4.dp)
                                )

                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(steps) { step ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x11FFFFFF))
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF4DD0E1)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = step.step_order.toString(),
                                                    color = Color.Black,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = step.instruction,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "${step.distance} (${step.duration})",
                                                    color = Color.Gray,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 中央區塊：相機預覽與畫布（鎖定 1:1 正方形）
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clipToBounds()
                        .border(2.dp, Color.White.copy(alpha = 0.3f))
                        .background(Color.Black)
                ) {
                    // 具備 Camera2 優化設定的 CameraX PreviewView
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                // 使用 Camera2 自動對焦與動態模糊抑制設定 Preview
                                val previewBuilder = Preview.Builder()
                                val previewExtender = Camera2Interop.Extender(previewBuilder)
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                                )
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_ACTION
                                )
                                val preview = previewBuilder.build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // 設定目標解析度為 640x640 的 ImageAnalysis
                                val resolutionSelector = ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(640, 640),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()

                                val imageAnalysisBuilder = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setResolutionSelector(resolutionSelector)

                                val analysisExtender = Camera2Interop.Extender(imageAnalysisBuilder)
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                                )
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_ACTION
                                )
                                val imageAnalysis = imageAnalysisBuilder.build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val bitmap = imageProxy.toBitmap()
                                    if (bitmap != null) {
                                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                        val results = detector.detect(bitmap, rotationDegrees)
                                        coroutineScope.launch {
                                            detections = results
                                        }
                                    }
                                    imageProxy.close()
                                }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                                try {
                                    cameraProvider.unbindAll()
                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("CameraLayout", "Use case binding failed", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 繪製辨識外框的覆蓋畫布
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        detections.forEach { det ->
                            // 來自模型的座標已正規化為 0..1
                            val left = det.x1 * canvasW
                            val top = det.y1 * canvasH
                            val right = det.x2 * canvasW
                            val bottom = det.y2 * canvasH

                            val strokeColor = if (det.isDanger) {
                                Color(0xFFE57373) // 危險警告亮紅色
                            } else {
                                Color(0x8081C784) // 安全半透明綠色
                            }

                            val strokeWidth = if (det.isDanger) 6f else 3f

                            // 繪製邊界框
                            drawRoundRect(
                                color = strokeColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }
                }

                // 右側面板：控制項與危險警報
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF121212))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HoldToTalkButton(
    isListening = isListening,
    onToggle = {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
        } else {
            recognizedText = "正在聆聽..."
            try {
                speechRecognizer.startListening(speechIntent)
                isListening = true
            } catch (e: SecurityException) {
                recognizedText = "缺乏錄音權限，請開啟設定"
                isListening = false
            } catch (e: Exception) {
                recognizedText = "語音辨識啟動失敗"
                isListening = false
            }
        }
    }
)
Text(
    text = "語音內容：$recognizedText",
    color = Color(0xFFFFD54F),
    fontSize = 18.sp,
    fontWeight = FontWeight.Bold
)
Spacer(modifier = Modifier.height(16.dp))
                    // 狀態膠囊指示標籤
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xAA1E1E1E))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF81C784).copy(alpha = alphaAnim))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "相機掃描中",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 手電筒開關
                    IconButton(
                        onClick = {
                            val currentCamera = camera
                            if (currentCamera != null && currentCamera.cameraInfo.hasFlashUnit()) {
                                isFlashlightOn = !isFlashlightOn
                                currentCamera.cameraControl.enableTorch(isFlashlightOn)
                                tts?.speak(
    if (isFlashlightOn) "手電筒已開啟" else "手電筒已關閉",
    TextToSpeech.QUEUE_ADD,
    null,
    "flashlight_${System.currentTimeMillis()}"
)
                            }
                        },
                        modifier = Modifier
                            .semantics {
                                contentDescription =
                                    if (isFlashlightOn)
                                        "手電筒已開啟，點兩下關閉"
                                    else
                                        "手電筒已關閉，點兩下開啟"
                                role = Role.Button
                            }
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xAA1E1E1E))
                            .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    ) {
                        Text(
                            text = if (isFlashlightOn) "🔦" else "💡",
                            fontSize = 24.sp
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 若偵測到危險物件則顯示危險指示器
                    val dangerousItems = detections.filter { it.isDanger }
                    val closestDangerItem = dangerousItems.maxByOrNull { it.proximity }
                    if (closestDangerItem != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xDDFF8A80))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️",
                                fontSize = 22.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "危險！前方過近",
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${closestDangerItem.labelTw} (${String.format("%.1f", closestDangerItem.distanceMeters)}公尺)",
                                    color = Color(0xFFB71C1C),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        // 安全狀態指示卡片
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x2281C784))
                                .border(1.dp, Color(0x4481C784), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✅",
                                fontSize = 22.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "環境安全",
                                    color = Color(0xFF81C784),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "無即時碰撞危險",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 直向佈局：上方相機正方形，下方控制台與警報
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                // 1. 相機預覽區（1:1 正方形）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clipToBounds()
                        .background(Color.Black)
                ) {
                    // 具備 Camera2 優化設定的 CameraX PreviewView
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                // 使用 Camera2 自動對焦與動態模糊抑制設定 Preview
                                val previewBuilder = Preview.Builder()
                                val previewExtender = Camera2Interop.Extender(previewBuilder)
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                                )
                                previewExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_ACTION
                                )
                                val preview = previewBuilder.build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // 設定目標解析度為 640x640 的 ImageAnalysis
                                val resolutionSelector = ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(640, 640),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()

                                val imageAnalysisBuilder = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setResolutionSelector(resolutionSelector)

                                val analysisExtender = Camera2Interop.Extender(imageAnalysisBuilder)
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                                )
                                analysisExtender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_ACTION
                                )
                                val imageAnalysis = imageAnalysisBuilder.build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val bitmap = imageProxy.toBitmap()
                                    if (bitmap != null) {
                                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                        val results = detector.detect(bitmap, rotationDegrees)
                                        coroutineScope.launch {
                                            detections = results
                                        }
                                    }
                                    imageProxy.close()
                                }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                                try {
                                    cameraProvider.unbindAll()
                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("CameraLayout", "Use case binding failed", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 2. 繪製辨識外框的覆蓋畫布
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        detections.forEach { det ->
                            // 來自模型的座標已正規化為 0..1
                            val left = det.x1 * canvasW
                            val top = det.y1 * canvasH
                            val right = det.x2 * canvasW
                            val bottom = det.y2 * canvasH

                            val strokeColor = if (det.isDanger) {
                                Color(0xFFE57373) // 危險警告亮紅色
                            } else {
                                Color(0x8081C784) // 安全半透明綠色
                            }

                            val strokeWidth = if (det.isDanger) 6f else 3f

                            // 繪製邊界框
                            drawRoundRect(
                                color = strokeColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }

                    // 覆蓋於相機預覽上方的頂部列
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 狀態膠囊指示標籤
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xAA1E1E1E))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF81C784).copy(alpha = alphaAnim))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "相機掃描中",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 手電筒開關
                        IconButton(
                            onClick = {
                                val currentCamera = camera
                                if (currentCamera != null && currentCamera.cameraInfo.hasFlashUnit()) {
                                    isFlashlightOn = !isFlashlightOn
                                    currentCamera.cameraControl.enableTorch(isFlashlightOn)
                                    tts?.speak(
    if (isFlashlightOn) "手電筒已開啟" else "手電筒已關閉",
    TextToSpeech.QUEUE_ADD,
    null,
    "flashlight_${System.currentTimeMillis()}"
)
                                }
                            },
                            modifier = Modifier
                                .semantics {
                                    contentDescription =
                                        if (isFlashlightOn)
                                            "手電筒已開啟，點兩下關閉"
                                        else
                                            "手電筒已關閉，點兩下開啟"
                                    role = Role.Button
                                }
                                .size(45.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA1E1E1E))
                                .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        ) {
                            Text(
                                text = if (isFlashlightOn) "🔦" else "💡",
                                fontSize = 20.sp
                            )
                        }
                    }
                }

                // 下方控制台與警報
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HoldToTalkButton(
    isListening = isListening,
    onToggle = {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
        } else {
            recognizedText = "正在聆聽..."
            try {
                speechRecognizer.startListening(speechIntent)
                isListening = true
            } catch (e: SecurityException) {
                recognizedText = "缺乏錄音權限，請開啟設定"
                isListening = false
            } catch (e: Exception) {
                recognizedText = "語音辨識啟動失敗"
                isListening = false
            }
        }
    }
)
    Text(
    text = "語音內容：$recognizedText",
    color = Color(0xFFFFD54F),
    fontSize = 18.sp,
    fontWeight = FontWeight.Bold
)
                    // 若偵測到危險物件則顯示危險指示器
                    val dangerousItems = detections.filter { it.isDanger }
                    val closestDangerItem = dangerousItems.maxByOrNull { it.proximity }
                    if (closestDangerItem != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xDDFF8A80))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "危險！前方物體過近",
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "偵測到最接近: ${closestDangerItem.labelTw} (${String.format("%.1f", closestDangerItem.distanceMeters)}公尺)",
                                    color = Color(0xFFB71C1C),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // 控制台 / 日誌面板（毛玻璃風格）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xBC1E1E1E))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showNavigationTab = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!showNavigationTab) Color(0xFFFFD54F) else Color(0x22FFFFFF),
                                    contentColor = if (!showNavigationTab) Color.Black else Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("警告日誌", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { showNavigationTab = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (showNavigationTab) Color(0xFF4DD0E1) else Color(0x22FFFFFF),
                                    contentColor = if (showNavigationTab) Color.Black else Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("導航指引", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = onSwitchToFamilyMode,
                                modifier = Modifier
                                    .size(28.dp)
                                    .semantics { contentDescription = "切換到家屬模式" }
                            ) {
                                Text("👪", fontSize = 14.sp)
                            }
                        }

                        HorizontalDivider(
                            color = Color(0x1AFFFFFF),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        if (!showNavigationTab) {
                            if (alertLogs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "尚無危險警告記錄，環境安全",
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(alertLogs, key = { it.id }) { log ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = log.message,
                                                color = Color(0xFFFF8A80),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = log.timestamp,
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val result = navigationResult
                            if (result == null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "按住語音按鈕說話，詢問「我現在在哪裡」或說出目的地以開始導航。",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "全程: ${result.distance ?: ""} / ${result.duration ?: ""}",
                                            color = Color(0xFF4DD0E1),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "離下個轉彎點: ${if (navDistanceToTurn < 0) "計算中" else String.format(java.util.Locale.US, "%.1f 公尺", navDistanceToTurn)} (第 ${navCurrentStepIndex + 1}/${result.steps?.size ?: 0} 步)",
                                            color = Color(0xFFFFD54F),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Button(
                                            onClick = {
                                                val navigationId = currentNavigationId
                                                val userId = deviceProfile?.userId
                                                if (navigationId != null && userId != null) {
                                                    coroutineScope.launch {
                                                        finishNavigationSession(serverUrl, navigationId, userId, "cancelled")
                                                    }
                                                }
                                                navigationResult = null
                                                showNavigationTab = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(24.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("結束", fontSize = 9.sp, color = Color.White)
                                        }
                                    }

                                    HorizontalDivider(color = Color(0x11FFFFFF), thickness = 1.dp)

                                    val steps = result.steps ?: emptyList()
                                    NavigationMap(
                                        currentLatLng = currentLatLng,
                                        steps = steps,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .padding(vertical = 2.dp)
                                    )

                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(vertical = 2.dp)
                                    ) {
                                        items(steps) { step ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0x11FFFFFF))
                                                    .padding(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF4DD0E1)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = step.step_order.toString(),
                                                        color = Color.Black,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = step.instruction,
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = "${step.distance} (${step.duration})",
                                                        color = Color.Gray,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationMap(
    currentLatLng: com.google.android.gms.maps.model.LatLng?,
    steps: List<DirectionStep>,
    modifier: Modifier = Modifier
) {
    val pathLatLngs = remember(steps) {
        val points = mutableListOf<com.google.android.gms.maps.model.LatLng>()
        steps.forEach { step ->
            step.start_location?.let { points.add(com.google.android.gms.maps.model.LatLng(it.lat, it.lng)) }
            step.end_location?.let { points.add(com.google.android.gms.maps.model.LatLng(it.lat, it.lng)) }
        }
        points
    }

    val cameraPositionState = com.google.maps.android.compose.rememberCameraPositionState()
    var isMapLoaded by remember { mutableStateOf(false) }

    val startPoint = pathLatLngs.firstOrNull()
    val endPoint = pathLatLngs.lastOrNull()

    // 若有目前位置則自動置中於目前位置，否則置中於路線起點
    LaunchedEffect(isMapLoaded, currentLatLng, startPoint) {
        if (!isMapLoaded) return@LaunchedEffect
        val target = currentLatLng ?: startPoint
        target?.let {
            cameraPositionState.position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(it, 16f)
        }
    }

    // 初始自動縮放以容納所有座標範圍
    LaunchedEffect(isMapLoaded, pathLatLngs) {
        if (!isMapLoaded || pathLatLngs.isEmpty()) return@LaunchedEffect
        val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        pathLatLngs.forEach { boundsBuilder.include(it) }
        currentLatLng?.let { boundsBuilder.include(it) }
        runCatching {
            cameraPositionState.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64))
        }
    }

    com.google.maps.android.compose.GoogleMap(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        cameraPositionState = cameraPositionState,
        onMapLoaded = { isMapLoaded = true }
    ) {
        if (pathLatLngs.size > 1) {
            com.google.maps.android.compose.Polyline(
                points = pathLatLngs,
                color = Color(0xFF4DD0E1),
                width = 8f
            )
        }

        startPoint?.let {
            com.google.maps.android.compose.Marker(
                state = com.google.maps.android.compose.MarkerState(position = it),
                title = "起點"
            )
        }

        endPoint?.let {
            com.google.maps.android.compose.Marker(
                state = com.google.maps.android.compose.MarkerState(position = it),
                title = "終點"
            )
        }

        currentLatLng?.let {
            com.google.maps.android.compose.Marker(
                state = com.google.maps.android.compose.MarkerState(position = it),
                title = "目前位置",
                icon = if (isMapLoaded) {
                    com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE)
                } else {
                    null
                }
            )
        }
    }
}

@Composable
fun HoldToTalkButton(
    isListening: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isListening) Color(0xFF4DD0E1)
                else Color(0xFF1E1E1E)
            )
            .border(
                width = 2.dp,
                color = if (isListening) Color(0xFF4DD0E1) else Color(0xFFFFD54F),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable {
                onToggle()
            }
            .semantics {
                contentDescription =
                    if (isListening)
                        "正在聆聽，點兩下停止語音輸入"
                    else
                        "點兩下開始語音輸入"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isListening) "⏹ 停止聆聽" else "🎙 開始說話",
            color = if (isListening) Color.Black else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "⚠️",
                fontSize = 54.sp
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "需要相機與麥克風權限",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "導盲警報系統需要使用您手機的鏡頭來辨識前方物體，並需要麥克風來進行語音目的地輸入。請允許此應用程式的相關權限。",
                color = Color.LightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A))
            ) {
                Text(text = "授予必要權限", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 將 Bitmap 旋轉指定角度。
 */
fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Serializable
data class ReverseGeocodeRequest(val latitude: Double, val longitude: Double)

@Serializable
data class ReverseGeocodeResponse(
    val success: Boolean,
    val address: String? = null,
    val message: String? = null
)

@Serializable
data class DirectionsRequest(
   val start: String,
    val destination: String,
    // 選填：帶上裝置配對碼身分的 user_id，後端才會建立 navigation_records，
    // 讓家屬模式的導航紀錄列表看得到這趟導航（見 data/DeviceIdentity.kt）。
    val user_id: Int? = null
)

@Serializable
data class LatLngDto(val lat: Double, val lng: Double)

@Serializable
data class DirectionStep(
    val step_order: Int,
    val instruction: String,
    val distance: String,
    val duration: String,
    // 下列三個欄位為三階段轉彎提示模組新增，供 TurnByTurnGuide 計算距離/方位使用。
    // 皆為選填，保留 kotlinx.serialization 對舊版後端回應的向下相容性。
    val start_location: LatLngDto? = null,
    val end_location: LatLngDto? = null,
    val maneuver: String? = null
)

@Serializable
data class DirectionsResponse(
    val success: Boolean,
    val start_address: String? = null,
    val end_address: String? = null,
    val distance: String? = null,
    val duration: String? = null,
    val steps: List<DirectionStep>? = null,
    val message: String? = null,
    // 家屬模式新增：後端建立的 navigation_records 主鍵，只有帶了 user_id
    // 才會有值。用來呼叫 /start、/finish，以及讓警報跟這趟導航關聯起來。
    val navigation_id: Int? = null
)

private val json = Json { ignoreUnknownKeys = true }
private val client = OkHttpClient()

/**
 * 取得使用者目前的座標，用來當作規劃路線的起點。
 *
 * 原本這裡是用 LocationManager.getLastKnownLocation() 抓「上一次任何 App
 * 留下來的定位快取」——這個快取可能是很久以前、甚至是精準度很差的基地台/
 * Wi-Fi 定位，跟使用者實際站的位置可能差到幾十甚至上百公尺。因為整條路線
 * 的每一個轉彎點座標，都是後端拿這個起點去問 Google Directions API 算出來
 * 的，只要起點是錯的，後面整條路線（包含轉彎點）都會跟著一起偏移——這正好
 * 可以解釋「明明人已經走到路口了，App 卻一直顯示還很遠、完全不出聲」的狀況。
 *
 * 改成最多等 6 秒收集連續幾次定位更新，取「精準度數字最小（越準）」的一次，
 * 而不是只叫一次就直接採用。原因是 GPS 剛開始鎖定衛星訊號時，第一次讀數的
 * 精準度往往比較差（實測看過 15~36 公尺的誤差），通常要再等一兩次更新，
 * 精準度才會明顯進步；只拿第一次的結果，很容易讓整條路線的起點就偏移了
 * 好幾十公尺。完全要不到定位時（例如剛開權限、GPS 訊號還沒鎖定）才退回
 * 用舊的快取座標當備援，確保至少不會直接回傳 (0,0) 讓路線整個算錯地方。
 */
@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context): Pair<Double, Double> {
    val hasFineLocation =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val hasCoarseLocation =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    if (!hasFineLocation && !hasCoarseLocation) {
        return Pair(0.0, 0.0)
    }

    // bestLocation 特意宣告在 withTimeoutOrNull 外面：就算 6 秒逾時、協程被
    // 取消，這期間收集到的「目前最準的一次」還是留得住，逾時也不會整個沒有
    // 座標可用，只是精準度可能沒那麼理想。
    var bestLocation: Location? = null

    withTimeoutOrNull(6000L) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val client = LocationServices.getFusedLocationProviderClient(context)

            val request = LocationRequest.Builder(1000L)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdates(6)
                .build()

            lateinit var callback: LocationCallback
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val current = bestLocation
                    if (current == null || location.accuracy < current.accuracy) {
                        bestLocation = location
                    }
                    // 精準度已經夠好（15 公尺內），不用等滿 6 次或 6 秒，馬上採用。
                    if (location.accuracy <= 15f && continuation.isActive) {
                        client.removeLocationUpdates(this)
                        continuation.resume(Unit)
                    }
                }
            }

            continuation.invokeOnCancellation { client.removeLocationUpdates(callback) }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
    }

    val freshLocation = bestLocation

    if (freshLocation != null) {
        return Pair(freshLocation.latitude, freshLocation.longitude)
    }

    // 備援：即時定位要不到的時候，才退回用舊的快取座標，避免完全沒有座標可用。
    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = locationManager.getProviders(true)
    var cachedLocation: Location? = null
    for (provider in providers) {
        val location = locationManager.getLastKnownLocation(provider) ?: continue
        if (cachedLocation == null || location.time > cachedLocation.time) {
            cachedLocation = location
        }
    }

    return if (cachedLocation != null) {
        Pair(cachedLocation.latitude, cachedLocation.longitude)
    } else {
        Pair(0.0, 0.0)
    }
}

@Serializable
data class ApiErrorDetail(val code: String? = null, val message: String? = null, val retryable: Boolean? = null)

@Serializable
data class ApiErrorEnvelope(val success: Boolean = false, val error: ApiErrorDetail? = null)

/**
 * 後端失敗時（HTTP 4xx/5xx）會回傳 { success:false, error:{ code, message, retryable } }，
 * 這裡把裡面真正有意義的中文訊息取出來，而不是只念出無意義的 HTTP 狀態碼數字，
 * 對視障使用者才聽得懂發生了什麼事、該怎麼辦。
 */
fun extractApiErrorMessage(bodyString: String?, httpCode: Int): String {
    if (bodyString != null) {
        val parsedMessage = runCatching {
            json.decodeFromString(ApiErrorEnvelope.serializer(), bodyString).error?.message
        }.getOrNull()
        if (!parsedMessage.isNullOrBlank()) return parsedMessage
    }
    return "伺服器錯誤 (代碼 $httpCode)"
}

suspend fun requestReverseGeocode(serverUrl: String, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val reqData = ReverseGeocodeRequest(lat, lng)
    val requestBody = json.encodeToString(ReverseGeocodeRequest.serializer(), reqData).toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$serverUrl/api/locations/reverse-geocode")
        .post(requestBody)
        .build()
    try {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) return@withContext extractApiErrorMessage(bodyString, response.code)
            if (bodyString == null) return@withContext "回應為空"
            val res = json.decodeFromString(ReverseGeocodeResponse.serializer(), bodyString)
            if (res.success) {
                res.address ?: "未取得地址"
            } else {
                res.message ?: "反地理編碼失敗"
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        "連線失敗: ${e.localizedMessage}"
    }
}

suspend fun requestDirections(
    serverUrl: String,
    lat: Double,
    lng: Double,
    destination: String,
    userId: Int? = null
): DirectionsResponse = withContext(Dispatchers.IO) {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val reqData = DirectionsRequest(start = "$lat,$lng", destination = destination, user_id = userId)
    val requestBody = json.encodeToString(DirectionsRequest.serializer(), reqData).toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$serverUrl/api/navigation/directions")
        .post(requestBody)
        .build()
    try {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext DirectionsResponse(false, message = extractApiErrorMessage(bodyString, response.code))
            }
            if (bodyString == null) return@withContext DirectionsResponse(false, message = "回應為空")
            json.decodeFromString(DirectionsResponse.serializer(), bodyString)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        DirectionsResponse(false, message = "連線失敗: ${e.localizedMessage}")
    }
}

/* =========================================================
   家屬模式新增：導航開始/結束通知、相機警報回報
   對應 backend/server.js 既有的 PATCH /api/navigation/:id/start、
   /finish，以及擴充後可帶 navigation_id 的 POST /api/environment-logs。
========================================================= */

@Serializable
private data class StartNavigationRequest(val user_id: Int)

@Serializable
private data class FinishNavigationRequest(val user_id: Int, val status: String)

@Serializable
private data class EnvironmentLogRequest(
    val user_id: Int,
    val object_name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val navigation_id: Int
)

@Serializable
private data class LocationPingRequest(val user_id: Int, val latitude: Double, val longitude: Double)

// 導航進行中回報座標的間隔。設太短會一直打後端，設太長會讓停留點判斷不準；
// 20 秒可以在 5 分鐘的停留門檻內取得約 15 個樣本，足夠判斷。
private const val LOCATION_REPORT_INTERVAL_MS = 20_000L

suspend fun startNavigationSession(serverUrl: String, navigationId: Int, userId: Int) =
    withContext(Dispatchers.IO) {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.encodeToString(StartNavigationRequest.serializer(), StartNavigationRequest(userId))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$serverUrl/api/navigation/$navigationId/start")
            .patch(body)
            .build()
        runCatching { client.newCall(request).execute().close() }.onFailure { it.printStackTrace() }
    }

suspend fun finishNavigationSession(serverUrl: String, navigationId: Int, userId: Int, status: String) =
    withContext(Dispatchers.IO) {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.encodeToString(
            FinishNavigationRequest.serializer(),
            FinishNavigationRequest(userId, status)
        ).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$serverUrl/api/navigation/$navigationId/finish")
            .patch(body)
            .build()
        runCatching { client.newCall(request).execute().close() }.onFailure { it.printStackTrace() }
    }

/**
 * 導航進行中定期回報目前座標，供家屬模式「查看詳情」事後算出停留點
 * （見 backend/family_pairing.js 的 /api/navigation/:id/location-ping）。
 */
suspend fun reportNavigationLocation(
    serverUrl: String,
    navigationId: Int,
    userId: Int,
    latitude: Double,
    longitude: Double
) = withContext(Dispatchers.IO) {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val body = json.encodeToString(
        LocationPingRequest.serializer(),
        LocationPingRequest(userId, latitude, longitude)
    ).toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$serverUrl/api/navigation/$navigationId/location-ping")
        .post(body)
        .build()
    runCatching { client.newCall(request).execute().close() }.onFailure { it.printStackTrace() }
}

suspend fun reportEnvironmentAlert(
    serverUrl: String,
    userId: Int,
    navigationId: Int,
    objectName: String,
    description: String,
    latitude: Double,
    longitude: Double
) = withContext(Dispatchers.IO) {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val body = json.encodeToString(
        EnvironmentLogRequest.serializer(),
        EnvironmentLogRequest(userId, objectName, description, latitude, longitude, navigationId)
    ).toRequestBody(jsonMediaType)
    val request = Request.Builder()
        .url("$serverUrl/api/environment-logs")
        .post(body)
        .build()
    runCatching { client.newCall(request).execute().close() }.onFailure { it.printStackTrace() }
}

suspend fun handleVoiceCommand(
    context: Context,
    text: String,
    serverUrl: String,
    lat: Double,
    lng: Double,
    userId: Int?,
    tts: TextToSpeech?,
    onDirectionsResult: (DirectionsResponse?) -> Unit
) {
    if (text.contains("哪裡") || text.contains("在哪")) {
        tts?.speak("正在查詢您目前的位置...", TextToSpeech.QUEUE_FLUSH, null, "reverse_geocode_start")
        val address = requestReverseGeocode(serverUrl, lat, lng)
        tts?.speak("您目前的位置是：$address", TextToSpeech.QUEUE_ADD, null, "reverse_geocode_result")
        onDirectionsResult(null)
    } else {
        tts?.speak("正在規劃前往 $text 的路線...", TextToSpeech.QUEUE_FLUSH, null, "directions_start")
        val response = requestDirections(serverUrl, lat, lng, text, userId)
        if (response.success) {
            val distanceStr = response.distance ?: ""
            val durationStr = response.duration ?: ""
            val summary = "規劃成功。全程約 ${distanceStr}，需要 ${durationStr}，開始導航後會依照您的位置提醒轉彎。"
            tts?.speak(summary, TextToSpeech.QUEUE_ADD, null, "directions_success")

            // 注意：這裡不再把所有步驟一次念完。
            // 逐步的轉彎提示改由 TurnByTurnGuide（三階段轉彎提示模組）
            // 在使用者實際走到定點時才播報，避免規劃完路線就把整趟路念過一遍。
            onDirectionsResult(response)
        } else {
            val errorMsg = "導航規劃失敗，原因為 ${response.message ?: "未知錯誤"}"
            tts?.speak(errorMsg, TextToSpeech.QUEUE_FLUSH, null, "directions_fail")
            onDirectionsResult(response)
        }
    }
}
