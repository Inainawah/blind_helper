package com.example.blindguideapp

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.common.ops.NormalizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(private val context: Context, private val modelPath: String) {
    private var interpreter: Interpreter? = null

    private val inputWidth = 640
    private val inputHeight = 640

    //任務四優化：自動辨識模型檔名有沒有包含 int8
    private val isQuantized = modelPath.contains("int8", ignoreCase = true)
    private val numBytesPerChannel = if (isQuantized) 1 else 4 // INT8 模型用 1 byte，Float32 模型用 4 bytes

    // Class names mapping (same as python CLASS_NAME_TW)
    val classNamesTw = mapOf(
        0 to "人", 1 to "腳踏車", 2 to "汽車", 3 to "機車",
        5 to "公車", 7 to "卡車", 9 to "紅綠燈",
        10 to "消防栓", 11 to "停止標誌", 12 to "停車收費錶", 13 to "長椅",
        15 to "貓", 16 to "狗",
        24 to "背包", 25 to "雨傘", 26 to "手提包", 28 to "行李箱",
        32 to "球類", 34 to "棒球棍", 35 to "棒球手套", 36 to "滑板",
        38 to "網球拍", 39 to "瓶子", 40 to "高腳杯", 41 to "杯子",
        56 to "椅子", 57 to "沙發", 58 to "盆栽", 60 to "餐桌",
        72 to "冰箱", 75 to "花瓶", 80 to "桌子"
    )

    // English labels for configuration lookup
    val classNamesEn = mapOf(
        0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
        5 to "bus", 7 to "truck", 9 to "traffic light",
        10 to "fire hydrant", 11 to "stop sign", 12 to "parking meter", 13 to "bench",
        15 to "cat", 16 to "dog",
        24 to "backpack", 25 to "umbrella", 26 to "handbag", 28 to "suitcase",
        32 to "sports ball", 34 to "baseball bat", 35 to "baseball glove", 36 to "skateboard",
        38 to "tennis racket", 39 to "bottle", 40 to "wine glass", 41 to "cup",
        56 to "chair", 57 to "couch", 58 to "potted plant", 60 to "dining table",
        72 to "refrigerator", 75 to "vase", 80 to "table"
    )

    // Area thresholds for 2-meter warnings (box area on 640x640 resolution)
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
        "baseball bat" to 3000f,
        "baseball glove" to 5000f,
        "skateboard" to 8000f,
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
        val distanceMeters: Float = 0f
    )

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            // Load TFLite model using pure Android APIs
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

    // 🛠️ 新增：正方形置中裁剪小工具，避免不同手機鏡頭比例導致物件拉伸變形
    private fun cropCenterSquare(srcBmp: Bitmap): Bitmap {
        val width = srcBmp.width
        val height = srcBmp.height
        
        // 以短邊為基準裁出正方形，防止超出邊界
        val squareSize = if (width < height) width else height
        
        val xOffset = (width - squareSize) / 2
        val yOffset = (height - squareSize) / 2
        
        // 切出正方形的中間區塊並回傳
        return Bitmap.createBitmap(srcBmp, xOffset, yOffset, squareSize, squareSize)
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        val interp = interpreter ?: return emptyList()

        // 🎯 關鍵優化：先將相機原始圖片裁切成正中間的正方形
        val squareBitmap = cropCenterSquare(bitmap)

        // 1. Efficient preprocessing using TFLite Support Library
        val tensorImage = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
        tensorImage.load(squareBitmap)

        // Convert clockwise rotation to counter-clockwise for Rot90Op
        val k = (360 - rotationDegrees) % 360 / 90

        val imageProcessor = ImageProcessor.Builder()
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

        // Output shape is [1, 300, 6]
        val outputBuffer = Array(1) { Array(300) { FloatArray(6) } }

        // 2. Inference
        interp.run(byteBuffer, outputBuffer)

        // 3. Postprocess
        val detections = mutableListOf<Detection>()
        val rawOutputs = outputBuffer[0]

        for (i in 0 until 300) {
            val box = rawOutputs[i]
            val confidence = box[4]
            if (confidence < 0.45f) continue // Filter confidence <= 45%

            val classId = box[5].toInt()
            val labelEn = classNamesEn[classId] ?: "unknown"
            val labelTw = classNamesTw[classId] ?: labelEn

            var rx1 = box[0]
            var ry1 = box[1]
            var rx2 = box[2]
            var ry2 = box[3]

            // If coordinates are in absolute pixel space (0-640), normalize them to 0-1
            if (rx1 > 1.1f || rx2 > 1.1f || ry1 > 1.1f || ry2 > 1.1f) {
                rx1 /= 640f
                ry1 /= 640f
                rx2 /= 640f
                ry2 /= 640f
            }

            // Calculate pixel area in 640x640 space
            val w = (rx2 - rx1) * 640f
            val h = (ry2 - ry1) * 640f
            
            // Aspect ratio filter to reduce false positives
            if (w <= 0f || h <= 0f) continue
            val aspectRatio = w / h
            val limit = aspectLimitMap[labelEn]
            if (limit != null) {
                if (limit.min != null && aspectRatio < limit.min) continue
                if (limit.max != null && aspectRatio > limit.max) continue
            }

            val area = w * h
            // 1. 檢查這個物件有沒有在我們的設定清單裡，如果沒有，直接跳過不偵測！
            val threshold = areaThresholds2M[labelEn]
            if (threshold == null) {
                continue // 清單外的不必要物品（如長頸鹿、微波爐），直接無視，不加入偵測清單
            }

            // 2. 有在清單內，才計算有沒有達到 2 公尺的危險距離
            val isDanger = area >= threshold
            val proximity = area / threshold
            // Estimate default distance from area ratio: at proximity=1.0, distance is 2.0 meters
            val distanceMeters = 2.0f / (proximity.coerceAtLeast(0.01f))

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
                    distanceMeters = distanceMeters
                )
            )
        }

        return detections
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}