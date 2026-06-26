package com.example.blindguideapp.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.speech.tts.TextToSpeech
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class AlertLog(val id: Long, val message: String, val timestamp: String)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // States
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraDetectionLayout(modifier)
    } else {
        PermissionDeniedScreen(onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraDetectionLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val lastAlertFinishedTime = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Initialize TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsInitialized by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
            }
        }
        ttsEngine.language = Locale.CHINESE
        tts = ttsEngine

        onDispose {
            ttsEngine.stop()
            ttsEngine.shutdown()
        }
    }

    // Initialize YOLO Detector and Hazard Tracker
    val detector = remember { YoloDetector(context, "yolo26s_float32.tflite") }
    val tracker = remember { HazardTracker() }

    // State parameters
    var detections by remember { mutableStateOf<List<YoloDetector.Detection>>(emptyList()) }
    var isFlashlightOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val alertLogs = remember { mutableStateListOf<AlertLog>() }
    var lastSpokenClassId by remember { mutableStateOf(-1) }
    var lastSpokenPriority by remember { mutableStateOf(0f) }

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

        // Urgent threat: rapid approach (rate >= 20000) or high priority (priority >= 2.5)
        val isUrgent = rate >= 20000f || priority >= 2.5f

        // Cooldown Preemption Rule: Urgent hazards immediately bypass the 3s cooldown
        // if they represent a new class OR a significant increase in priority (>0.5) for the same class.
        val shouldPreempt = isUrgent && (det.classId != lastSpokenClassId || priority >= lastSpokenPriority + 0.5f)

        if (currentTime >= lockedUntil || shouldPreempt) {
            val name = det.labelTw
            val alertMsg = if (isUrgent) {
                "緊急！前方有 $name 快速靠近"
            } else {
                "注意，前方有 $name"
            }

            // Shorten cooldown for urgent preemptive alerts to remain highly responsive
            val estimatedSpeechDurationMs = alertMsg.length * 350L + 500L
            val cooldownMs = if (isUrgent) 1000L else 3000L
            lastAlertFinishedTime.set(currentTime + estimatedSpeechDurationMs + cooldownMs)
            
            lastSpokenClassId = det.classId
            lastSpokenPriority = priority

            // Use QUEUE_FLUSH to preempt immediately
            tts?.speak(alertMsg, TextToSpeech.QUEUE_FLUSH, null, "alert_${currentTime}")

            // Log entry
            val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (alertLogs.size > 20) {
                alertLogs.removeAt(alertLogs.size - 1)
            }
            val logMessage = if (isUrgent) {
                "🔴 緊急警告: $name 快速靠近 (優先度: ${String.format("%.1f", priority)})"
            } else {
                "⚠️ 危險警告: 前方有 $name (優先度: ${String.format("%.1f", priority)})"
            }
            alertLogs.add(0, AlertLog(System.currentTimeMillis(), logMessage, timeStamp))
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. CameraX PreviewView with optimized Camera2 settings
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
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
                            detections = results
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

                // Optional label drawing on canvas
                // (Note: For high-end design, we can also overlay Compose text, but drawing directly on canvas is efficient)
            }
        }

        // 3. Premium Glassmorphism UI Controls (Status & Flashlight)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .safeDrawingPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
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

            // Bottom Console Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                text = "偵測到最接近: " + closestDangerItem.labelTw,
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
                        .height(160.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xBC1E1E1E))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "歷史警告日誌",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Divider(color = Color(0x1AFFFFFF), thickness = 1.dp)

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
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = log.message,
                                        color = Color(0xFFFF8A80),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = log.timestamp,
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 11.sp
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
                text = "需要相機存取權限",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "導盲警報系統需要使用您手機的內建鏡頭來辨識前方的物體，請允許此應用程式的相機權限。",
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
                Text(text = "授權相機權限", color = Color.White, fontWeight = FontWeight.Bold)
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
