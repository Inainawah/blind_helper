package com.example.blindguideapp

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.common.ops.NormalizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(private val context: Context, private val modelPath: String) {
    private var interpreter: Interpreter? = null

    // 保護 interpreter：避免相機背景執行緒還在呼叫 detect() 的同時，
    // 另一條執行緒（例如切換到家屬模式時）呼叫 close() 把底層 native
    // 記憶體釋放掉，兩者同時發生會造成原生層級的記憶體存取錯誤而整個 App 閃退。
    private val interpreterLock = Any()

    private val inputWidth = 640
    private val inputHeight = 640

    //任務四優化：自動辨識模型檔名有沒有包含 int8
    private val isQuantized = modelPath.contains("int8", ignoreCase = true)
    private val numBytesPerChannel = if (isQuantized) 1 else 4 // INT8 模型用 1 byte，Float32 模型用 4 bytes

    // 類別名稱對照表（繁體中文）
    val classNamesTw = mapOf(
        0 to "人", 1 to "腳踏車", 2 to "汽車", 3 to "機車",
        5 to "公車", 7 to "卡車", 9 to "紅綠燈",
        10 to "消防栓", 11 to "停止標誌", 12 to "停車收費錶", 13 to "長椅",
        15 to "貓", 16 to "狗",
        24 to "背包", 25 to "雨傘", 26 to "手提包", 28 to "行李箱",
        32 to "球類", 35 to "棒球手套",
        38 to "網球拍", 39 to "瓶子", 40 to "高腳杯", 41 to "杯子",
        56 to "椅子", 57 to "沙發", 58 to "盆栽", 60 to "餐桌",
        72 to "冰箱", 75 to "花瓶", 80 to "桌子"
    )

    // 英文標籤對照表（用於配置查詢）
    val classNamesEn = mapOf(
        0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
        5 to "bus", 7 to "truck", 9 to "traffic light",
        10 to "fire hydrant", 11 to "stop sign", 12 to "parking meter", 13 to "bench",
        15 to "cat", 16 to "dog",
        24 to "backpack", 25 to "umbrella", 26 to "handbag", 28 to "suitcase",
        32 to "sports ball", 35 to "baseball glove",
        38 to "tennis racket", 39 to "bottle", 40 to "wine glass", 41 to "cup",
        56 to "chair", 57 to "couch", 58 to "potted plant", 60 to "dining table",
        72 to "refrigerator", 75 to "vase", 80 to "table"
    )

    // 2 公尺預警面積門檻值（基於 640x640 解析度）
    val areaThresholds2M = mapOf(
        "person" to 69000f,
        "bicycle" to 45000f,
        "car" to 310000f,
        "motorcycle" to 95000f,
        "bus" to 450000f,
        "truck" to 420000f,
        "traffic light" to 12000f,
        "fire hydrant" to 8500f,
        "stop sign" to 15000f,
        "parking meter" to 10000f,
        "bench" to 50000f,
        "cat" to 8000f,
        "dog" to 22000f,
        "backpack" to 12000f,
        "umbrella" to 47000f,
        "handbag" to 15000f,
        "suitcase" to 60500f,
        "sports ball" to 2500f,
        "baseball glove" to 5000f,
        "tennis racket" to 6000f,
        "bottle" to 1300f,
        "wine glass" to 1500f,
        "cup" to 1800f,
        "chair" to 36000f,
        "couch" to 267000f,
        "potted plant" to 18000f,
        "dining table" to 90000f,
        "refrigerator" to 150000f,
        "vase" to 4500f,
        "table" to 9000f
    )


    data class AspectRatioRange(val min: Float?, val max: Float?)

    val aspectLimitMap = mapOf(
        "person" to AspectRatioRange(min = 0.1f, max = 1.0f),
        "traffic light" to AspectRatioRange(min = 0.1f, max = 0.8f),
        "fire hydrant" to AspectRatioRange(min = 0.2f, max = 1.2f),
        "stop sign" to AspectRatioRange(min = 0.6f, max = 1.5f),
        "bench" to AspectRatioRange(min = 1.0f, max = null),
        "backpack" to AspectRatioRange(min = 0.3f, max = 2.0f),
        "umbrella" to AspectRatioRange(min = 0.3f, max = 3.0f),
        "handbag" to AspectRatioRange(min = 0.4f, max = 2.5f),
        "suitcase" to AspectRatioRange(min = 0.4f, max = 2.5f),
        "chair" to AspectRatioRange(min = 0.3f, max = 2.0f),
        "couch" to AspectRatioRange(min = 1.0f, max = null),
        "dining table" to AspectRatioRange(min = 0.8f, max = null),
        "pole" to AspectRatioRange(min = null, max = 0.4f),
        "manhole" to AspectRatioRange(min = 1.5f, max = null)
    )

    data class Detection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float,
        val classId: Int,
        val labelTw: String,
        val labelEn: String,
        val isDanger: Boolean,
        val proximity: Float,
        val distanceMeters: Float = 0f,
        val direction: String = "正前方",
        val isDangerous: Boolean = false,
        val priority: Float = 0.0f
    )

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            // 使用 Android 原生 API 載入 TFLite 模型
            val fileDescriptor = context.assets.openFd(modelPath)
            val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            interpreter = Interpreter(modelBuffer, options)
            android.util.Log.d("YoloDetector", "YOLO model loaded natively: $modelPath")
        } catch (e: Exception) {
            android.util.Log.e("YoloDetector", "Failed to load model natively: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> = synchronized(interpreterLock) {
        val interp = interpreter ?: return@synchronized emptyList()

        // 關鍵優化：計算正方形尺寸，後續由 ImageProcessor.ResizeWithCropOrPadOp 於 Native 進行置中裁剪，避免額外 Bitmap 記憶體分配
        val width = bitmap.width
        val height = bitmap.height
        val squareSize = if (width < height) width else height

        // 1. 使用 TFLite Support Library 進行高效前處理
        val tensorImage = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
        tensorImage.load(bitmap)

        // 將順時針旋轉角度轉換為 Rot90Op 所需的逆時針旋轉
        val k = (360 - rotationDegrees) % 360 / 90

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeWithCropOrPadOp(squareSize, squareSize))
            .add(ResizeOp(inputWidth, inputHeight, ResizeOp.ResizeMethod.BILINEAR))
            .apply {
                if (k > 0) {
                    add(Rot90Op(k))
                }
            }
            .add(if (isQuantized) NormalizeOp(128f, 1f) else NormalizeOp(0f, 255f))
            .build()

        val processedImage = imageProcessor.process(tensorImage)
        val byteBuffer = processedImage.buffer

        // 輸出維度為 [1, 300, 6]
        val outputBuffer = Array(1) { Array(300) { FloatArray(6) } }

        // 2. 執行模型推論
        interp.run(byteBuffer, outputBuffer)

        // 3. 後處理
        val detections = mutableListOf<Detection>()
        val rawOutputs = outputBuffer[0]

        for (i in 0 until 300) {
            val box = rawOutputs[i]
            val confidence = box[4]
            if (confidence < 0.45f) continue // 過濾信心度低於 45% 的預測

            val classId = box[5].toInt()
            val labelEn = classNamesEn[classId] ?: "unknown"
            val labelTw = classNamesTw[classId] ?: labelEn

            var rx1 = box[0]
            var ry1 = box[1]
            var rx2 = box[2]
            var ry2 = box[3]

            // 若座標為絕對像素空間（0-640），將其正規化至 0-1
            if (rx1 > 1.1f || rx2 > 1.1f || ry1 > 1.1f || ry2 > 1.1f) {
                rx1 /= 640f
                ry1 /= 640f
                rx2 /= 640f
                ry2 /= 640f
            }

            // 計算在 640x640 空間下的像素面積
            val w = (rx2 - rx1) * 640f
            val h = (ry2 - ry1) * 640f
            
            // 長寬比過濾以減少誤判
            if (w <= 0f || h <= 0f) continue
            val aspectRatio = w / h
            val limit = aspectLimitMap[labelEn]
            if (limit != null) {
                if (limit.min != null && aspectRatio < limit.min) continue
                if (limit.max != null && aspectRatio > limit.max) continue
            }

            // 1. 檢查這個物件有沒有在我們的設定清單裡，如果沒有，直接跳過不偵測！
            if (!areaThresholds2M.containsKey(labelEn)) {
                continue // 清單外的不必要物品（如長頸鹿、微波爐），直接無視，不加入偵測清單
            }

            val centerX = (rx1 + rx2) / 2f
            val direction = when {
                centerX < 0.33f -> "左前方"
                centerX > 0.67f -> "右前方"
                else -> "正前方"
            }

            // 2. 幾何距離與 2 公尺預警判定 (不再使用面積判定)
            val distanceToBottom = (1.0f - ry2).coerceAtLeast(0.01f)
            val isDangerous = distanceToBottom <= 0.45f
            val priority = if (isDangerous) {
                (1.0f - distanceToBottom) * 10f
            } else {
                0.0f
            }

            // 對應原本欄位，以維持外部串接正常
            val isDanger = isDangerous
            val proximity = 0.45f / distanceToBottom
            val distanceMeters = 2.0f / proximity

            detections.add(
                Detection(
                    x1 = rx1.coerceIn(0f, 1f),
                    y1 = ry1.coerceIn(0f, 1f),
                    x2 = rx2.coerceIn(0f, 1f),
                    y2 = ry2.coerceIn(0f, 1f),
                    confidence = confidence,
                    classId = classId,
                    labelTw = labelTw,
                    labelEn = labelEn,
                    isDanger = isDanger,
                    proximity = proximity,
                    distanceMeters = distanceMeters,
                    direction = direction,
                    isDangerous = isDangerous,
                    priority = priority
                )
            )
        }

        return detections
    }

    fun close() = synchronized(interpreterLock) {
        interpreter?.close()
        interpreter = null
    }
}