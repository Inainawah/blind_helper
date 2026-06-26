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
        0 to "人", 1 to "腳踏車", 2 to "汽車", 3 to "機車", 4 to "飛機",
        5 to "公車", 6 to "火車", 7 to "卡車", 8 to "船", 9 to "紅綠燈",
        10 to "消防栓", 11 to "停止標誌", 12 to "停車收費錶", 13 to "長椅",
        14 to "鳥", 15 to "貓", 16 to "狗", 17 to "馬", 18 to "羊", 19 to "牛",
        20 to "大象", 21 to "熊", 22 to "斑馬", 23 to "長頸鹿", 24 to "背包",
        25 to "雨傘", 26 to "手提包", 27 to "領帶", 28 to "行李箱", 29 to "飛盤",
        30 to "滑雪板", 31 to "單板滑雪", 32 to "運動彩球", 33 to "風箏",
        34 to "棒球棍", 35 to "棒球手套", 36 to "滑板", 37 to "衝浪板",
        38 to "網球拍", 39 to "瓶子", 40 to "高腳杯", 41 to "杯子",
        42 to "叉子", 43 to "刀子", 44 to "湯匙", 45 to "碗", 46 to "香蕉",
        47 to "蘋果", 48 to "三明治", 49 to "橘子", 50 to "花椰菜",
        51 to "胡蘿蔔", 52 to "熱狗", 53 to "披薩", 54 to "甜甜圈", 55 to "蛋糕",
        56 to "椅子", 57 to "沙發", 58 to "盆栽", 59 to "床", 60 to "餐桌",
        61 to "馬桶", 62 to "電視", 63 to "筆記型電腦", 64 to "滑鼠", 65 to "遙控器",
        66 to "鍵盤", 67 to "手機", 68 to "微波爐", 69 to "烤箱",
        70 to "烤麵包機", 71 to "水槽", 72 to "冰箱", 73 to "書本",
        74 to "時鐘", 75 to "花瓶", 76 to "剪刀", 77 to "泰迪熊",
        78 to "吹風機", 79 to "牙刷", 80 to "桌子"
    )

    // English labels for configuration lookup
    val classNamesEn = mapOf(
        0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle", 4 to "airplane",
        5 to "bus", 6 to "train", 7 to "truck", 8 to "boat", 9 to "traffic light",
        10 to "fire hydrant", 11 to "stop sign", 12 to "parking meter", 13 to "bench",
        14 to "bird", 15 to "cat", 16 to "dog", 17 to "horse", 18 to "sheep", 19 to "cow",
        20 to "elephant", 21 to "bear", 22 to "zebra", 23 to "giraffe", 24 to "backpack",
        25 to "umbrella", 26 to "handbag", 27 to "tie", 28 to "suitcase", 29 to "frisbee",
        30 to "skis", 31 to "snowboard", 32 to "sports ball", 33 to "kite",
        34 to "baseball bat", 35 to "baseball glove", 36 to "skateboard", 37 to "surfboard",
        38 to "tennis racket", 39 to "bottle", 40 to "wine glass", 41 to "cup",
        42 to "fork", 43 to "knife", 44 to "spoon", 45 to "bowl", 46 to "banana",
        47 to "apple", 48 to "sandwich", 49 to "orange", 50 to "broccoli",
        51 to "carrot", 52 to "hot dog", 53 to "pizza", 54 to "donut", 55 to "cake",
        56 to "chair", 57 to "couch", 58 to "potted plant", 59 to "bed", 60 to "dining table",
        61 to "toilet", 62 to "tv", 63 to "laptop", 64 to "mouse", 65 to "remote",
        66 to "keyboard", 67 to "cell phone", 68 to "microwave", 69 to "oven",
        70 to "toaster", 71 to "sink", 72 to "refrigerator", 73 to "book",
        74 to "clock", 75 to "vase", 76 to "scissors", 77 to "teddy bear",
        78 to "hair drier", 79 to "toothbrush", 80 to "table"
    )

    // Area thresholds for 2-meter warnings (box area on 640x640 resolution)
    val areaThresholds2M = mapOf(
        "person" to 69000f, "umbrella" to 47000f, "chair" to 36000f, "table" to 9000f,
        "bottle" to 1300f, "backpack" to 12000f, "couch" to 267000f, "suitcase" to 60500f,
        // === 新加入的戶外致命與動態障礙物 ===
        "motorcycle" to 95000f,
        "bicycle" to 45000f,
        "car" to 310000f,
        "bus" to 450000f,
        "truck" to 420000f,
        "fire hydrant" to 8500f,
        "stop sign" to 15000f,
        "potted plant" to 18000f,
        "dog" to 22000f,
        "cat" to 8000f
    )


    val defaultArea2M = 3500f

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
        val proximity: Float
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

    fun detect(bitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        val interp = interpreter ?: return emptyList()

        // 1. Efficient preprocessing using TFLite Support Library
        val tensorImage = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
        tensorImage.load(bitmap)

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
            if (confidence < 0.30f) continue // Filter confidence <= 30%

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
            val threshold = areaThresholds2M[labelEn] ?: defaultArea2M
            val isDanger = area >= threshold
            val proximity = area / threshold

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
                    proximity = proximity
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