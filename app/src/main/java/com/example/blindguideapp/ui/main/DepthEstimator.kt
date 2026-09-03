package com.example.blindguideapp.ui.main

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class DepthEstimator(private val context: Context, private val modelPath: String) {
    private var interpreter: Interpreter? = null
    private val inputWidth = 256
    private val inputHeight = 256

    // ImageNet 正規化常數
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val fileDescriptor = context.assets.openFd(modelPath)
            val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            interpreter = Interpreter(modelBuffer, options)
            android.util.Log.d("DepthEstimator", "MiDaS model loaded: $modelPath")
        } catch (e: Exception) {
            android.util.Log.e("DepthEstimator", "Failed to load MiDaS model: ${e.message}", e)
        }
    }

    /**
     * 估算 256x256 大小的相對逆深度圖。
     * 回傳大小為 256 * 256 的一維 FloatArray。
     */
    fun estimateDepth(bitmap: Bitmap): FloatArray {
        val interp = interpreter ?: return FloatArray(inputWidth * inputHeight)

        // 1. 將 Bitmap 縮放至 256x256
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // 2. 前處理：正規化並寫入 ByteBuffer
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        
        val intValues = IntArray(inputWidth * inputHeight)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        inputBuffer.rewind()
        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            // 使用 ImageNet 的平均值與標準差進行正規化
            inputBuffer.putFloat((r - mean[0]) / std[0])
            inputBuffer.putFloat((g - mean[1]) / std[1])
            inputBuffer.putFloat((b - mean[2]) / std[2])
        }

        // 3. 輸出緩衝區：符合 [1, 256, 256, 1] Tensor 的 Float[][][] 陣列
        val outputMap = Array(1) { Array(inputWidth) { FloatArray(inputHeight) } }

        // 4. 執行推論
        try {
            interp.run(inputBuffer, outputMap)
        } catch (e: Exception) {
            android.util.Log.e("DepthEstimator", "Failed to run depth inference", e)
        }

        // 5. 將二維深度圖展平成大小為 256*256 的一維 FloatArray
        val depthMap = FloatArray(inputWidth * inputHeight)
        var index = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                depthMap[index++] = outputMap[0][y][x]
            }
        }

        return depthMap
    }

    /**
     * 在 256x256 深度圖上取樣 3x3 區域，
     * 並將相對逆深度轉換為公尺單位的物理距離。
     */
    fun getPhysicalDistance(depthMap: FloatArray, cx: Float, cy: Float): Float {
        // cx, cy 為正規化座標（0..1）
        val dx = (cx * inputWidth).toInt().coerceIn(0, inputWidth - 1)
        val dy = (cy * inputHeight).toInt().coerceIn(0, inputHeight - 1)

        val samples = mutableListOf<Float>()
        for (i in -1..1) {
            for (j in -1..1) {
                val sampleX = dx + i
                val sampleY = dy + j
                if (sampleX in 0 until inputWidth && sampleY in 0 until inputHeight) {
                    val index = sampleY * inputWidth + sampleX
                    val depthVal = depthMap[index]
                    if (depthVal > 0f) {
                        samples.add(depthVal)
                    }
                }
            }
        }

        if (samples.isEmpty()) return 0f
        samples.sort()
        val medianDepth = samples[samples.size / 2]

        // MiDaS small v2.1 的經驗校準：
        // 相對逆深度大約與 1 / 距離 成正比。
        // 計算公式：distance = scale / (medianDepth + epsilon)
        // 在標準 MiDaS 輸出下，scale 設為 6.5f 到 8.0f 可獲得良好校準。
        val scale = 6.5f
        return scale / maxOf(medianDepth, 0.01f)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
