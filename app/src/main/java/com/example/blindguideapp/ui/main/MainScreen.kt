package com.example.blindguideapp.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.speech.tts.TextToSpeech
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
import kotlinx.coroutines.launch
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

    // States
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

    // Initialize TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(ttsInitialized) {
        if (ttsInitialized) {
            tts?.speak(
                "歡迎使用vigo。本 App 會透過相機，為您辨識障礙物，並用語音提醒您方向。請點擊螢幕右上角並說出目的地後，開始為您導航。",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome_intro"
            )
        }
    }

    DisposableEffect(Unit) {
        val ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
            }
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        ttsEngine.setAudioAttributes(audioAttributes)
        ttsEngine.setSpeechRate(1.3f)
        ttsEngine.language = Locale.CHINESE
        tts = ttsEngine

        onDispose {
            ttsEngine.stop()
            ttsEngine.shutdown()
        }
    }

    // Initialize YOLO Detector and Hazard Tracker
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

    // State parameters
    var detections by remember { mutableStateOf<List<YoloDetector.Detection>>(emptyList()) }
    var isFlashlightOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val alertLogs = remember { mutableStateListOf<AlertLog>() }
    var lastSpokenClassId by remember { mutableStateOf(-1) }
    var lastSpokenPriority by remember { mutableStateOf(0f) }
    var isListening by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("尚未收到語音指令") }
    var navigationResult by remember { mutableStateOf<DirectionsResponse?>(null) }
    var showNavigationTab by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
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
                    val (lat, lng) = getCurrentLocation(context)
                    coroutineScope.launch {
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

        val result = navigationResult
        val navigationId = result?.navigation_id
        val userId = deviceProfile?.userId
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
                tts?.speak(
                    text,
                    if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "turn_guide_${System.currentTimeMillis()}"
                )
            },
            vibrateShort = { guidanceVibrator.shortDoubleBuzz() },
            onCompleted = {
                if (navigationId != null && userId != null) {
                    coroutineScope.launch {
                        finishNavigationSession(serverUrl, navigationId, userId, "completed")
                    }
                }
            }
        )
        activeGuide = guide
        fusedLocationTracker.start(intervalMs = 1500L) { location ->
            guide.onLocation(GeoPoint(location.latitude, location.longitude))
        }
    }

    // 手機朝向每次更新都同步餵給正在進行的轉彎導航，供其重新計算「前後左右」
    // 與確認使用者是否已完成轉身。
    LaunchedEffect(compassAzimuth) {
        activeGuide?.onAzimuth(compassAzimuth)
    }

    // Pulse animation for status indicator
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

    // Trigger TTS and logs when danger is detected (Priority & Preemption Logic)
    LaunchedEffect(detections) {
        if (!ttsInitialized) return@LaunchedEffect

        // Track the detections and obtain their rates of area change
        val trackedResults = tracker.update(detections)
        if (trackedResults.isEmpty()) return@LaunchedEffect

        // Calculate a composite priority score for each tracked threat
        val prioritizedThreats = trackedResults.map { (det, rate) ->
            val proximity = det.proximity
            val rateFactor = if (rate > 0f) rate / 15000f else 0f
            val priority = proximity * (1f + rateFactor)
            Triple(det, rate, priority)
        }

        // Find the highest priority threat
        val highestPriorityThreat = prioritizedThreats.maxByOrNull { it.third } ?: return@LaunchedEffect
        val (det, rate, priority) = highestPriorityThreat

        val currentTime = System.currentTimeMillis()
        val lockedUntil = lastAlertFinishedTime.get()
        val lastSpeakTime = lastAlertStartTime.get()

        // Urgent threat: rapid approach (rate >= 20000) or high priority (priority >= 2.5)
        val isUrgent = rate >= 20000f || priority >= 2.5f

        // Cooldown Preemption Rule: Urgent hazards immediately bypass the 3s cooldown
        // if they represent a new class OR a significant increase in priority (>0.5) for the same class.
        // Also enforce a minimum interval (e.g. 1.0s) since last alert started, to prevent stutters between classes.
        val minSpeakDurationMs = 1000L
        val hasSpokenLongEnough = currentTime - lastSpeakTime >= minSpeakDurationMs
        val shouldPreempt = isUrgent && hasSpokenLongEnough && (det.classId != lastSpokenClassId || priority >= lastSpokenPriority + 0.5f)

        if (currentTime >= lockedUntil || shouldPreempt) {
            val name = det.labelTw
            val alertMsg = "${det.direction}有 $name"

            // Shorten cooldown for urgent preemptive alerts to remain highly responsive
            // Scaled for 1.3f speech rate
            val estimatedSpeechDurationMs = alertMsg.length * 250L + 300L
            val cooldownMs = if (isUrgent) 1000L else 3000L
            lastAlertFinishedTime.set(currentTime + estimatedSpeechDurationMs + cooldownMs)
            lastAlertStartTime.set(currentTime) // Record the start time of the speech
            
            lastSpokenClassId = det.classId
            lastSpokenPriority = priority

            // Use QUEUE_FLUSH to preempt immediately
            if (isUrgent) {
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

            // Log entry
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (alertLogs.size > 20) {
                alertLogs.removeAt(alertLogs.size - 1)
            }
            val logMessage = if (isUrgent) {
    "緊急警告: ${det.direction}有「$name」快速靠近"
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

    // Detect current screen orientation
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (showSettingsDialog) {
            var tempUrl by remember { mutableStateOf(serverUrl) }
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("設定伺服器網址") },
                text = {
                    Column {
                        Text("請輸入後端 API 伺服器網址：", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = tempUrl,
                            onValueChange = { tempUrl = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onServerUrlChange(tempUrl)
                        showSettingsDialog = false
                    }) {
                        Text("確定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        if (isLandscape) {
            // Landscape Layout: Camera square centered with black masks on left and right sides
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                // Left Panel: History alert logs & Navigation
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
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("⚙️", fontSize = 16.sp)
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

                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    val steps = result.steps ?: emptyList()
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

                // Center Box: Camera Preview & Canvas (Locked at 1:1 Square)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clipToBounds()
                        .border(2.dp, Color.White.copy(alpha = 0.3f))
                        .background(Color.Black)
                ) {
                    // CameraX PreviewView with optimized Camera2 settings
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                // Configure Preview with Camera2 autofocus & motion blur reduction controls
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

                                // Configure ImageAnalysis with target 640x640 resolution
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

                    // Overlay Canvas for Bounding Boxes
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        detections.forEach { det ->
                            // Coordinates from model are normalized 0..1
                            val left = det.x1 * canvasW
                            val top = det.y1 * canvasH
                            val right = det.x2 * canvasW
                            val bottom = det.y2 * canvasH

                            val strokeColor = if (det.isDanger) {
                                Color(0xFFE57373) // Bright warnings red
                            } else {
                                Color(0x8081C784) // Subtle green for safe
                            }

                            val strokeWidth = if (det.isDanger) 6f else 3f

                            // Draw bounding box
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

                // Right Panel: Controls & Danger Alert
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
                    // Status Pill
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

                    // Flashlight Toggle
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

                    // Danger indicator if any danger items detected
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
                        // Safe state indicator card
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
            // Portrait Layout: Top Camera Square, Bottom Console & Alerts
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                // 1. Camera Preview Area (Square 1:1)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clipToBounds()
                        .background(Color.Black)
                ) {
                    // CameraX PreviewView with optimized Camera2 settings
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                // Configure Preview with Camera2 autofocus & motion blur reduction controls
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

                                // Configure ImageAnalysis with target 640x640 resolution
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

                    // 2. Overlay Canvas for Bounding Boxes
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        detections.forEach { det ->
                            // Coordinates from model are normalized 0..1
                            val left = det.x1 * canvasW
                            val top = det.y1 * canvasH
                            val right = det.x2 * canvasW
                            val bottom = det.y2 * canvasH

                            val strokeColor = if (det.isDanger) {
                                Color(0xFFE57373) // Bright warnings red
                            } else {
                                Color(0x8081C784) // Subtle green for safe
                            }

                            val strokeWidth = if (det.isDanger) 6f else 3f

                            // Draw bounding box
                            drawRoundRect(
                                color = strokeColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }

                    // Top Bar overlaid on camera preview
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status Pill
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

                        // Flashlight Toggle
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

                // Bottom Console & Alerts
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
                    // Danger indicator if any danger items detected
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

                    // Console / Log Panel (Glassmorphism style)
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
                                onClick = { showSettingsDialog = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("⚙️", fontSize = 14.sp)
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

                                    LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(vertical = 2.dp)
                                    ) {
                                        val steps = result.steps ?: emptyList()
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
 * Rotates a bitmap by the specified degrees.
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

@SuppressLint("MissingPermission")
fun getCurrentLocation(context: Context): Pair<Double, Double> {
    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

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

    val providers = locationManager.getProviders(true)

    var bestLocation: Location? = null

    for (provider in providers) {
        val location =
            locationManager.getLastKnownLocation(provider) ?: continue

        if (
            bestLocation == null ||
            location.time > bestLocation.time
        ) {
            bestLocation = location
        }
    }

    return if (bestLocation != null) {
        Pair(
            bestLocation.latitude,
            bestLocation.longitude
        )
    } else {
        Pair(0.0, 0.0)
    }
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
            if (!response.isSuccessful) return@withContext "伺服器錯誤: ${response.code}"
            val bodyString = response.body?.string() ?: return@withContext "回應為空"
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
            if (!response.isSuccessful) return@withContext DirectionsResponse(false, message = "伺服器錯誤: ${response.code}")
            val bodyString = response.body?.string() ?: return@withContext DirectionsResponse(false, message = "回應為空")
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
