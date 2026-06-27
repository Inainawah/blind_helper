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

    // ImageNet normalization constants
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
     * Estimates relative inverse depth map of size 256x256.
     * Returns a flat FloatArray of size 256 * 256.
     */
    fun estimateDepth(bitmap: Bitmap): FloatArray {
        val interp = interpreter ?: return FloatArray(inputWidth * inputHeight)

        // 1. Resize bitmap to 256x256
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // 2. Preprocess: normalize and write to ByteBuffer
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

            // Normalize with ImageNet mean/std
            inputBuffer.putFloat((r - mean[0]) / std[0])
            inputBuffer.putFloat((g - mean[1]) / std[1])
            inputBuffer.putFloat((b - mean[2]) / std[2])
        }

        // 3. Output buffer: Float[][][] array matching [1, 256, 256, 1] tensor
        val outputMap = Array(1) { Array(inputWidth) { FloatArray(inputHeight) } }

        // 4. Inference
        try {
            interp.run(inputBuffer, outputMap)
        } catch (e: Exception) {
            android.util.Log.e("DepthEstimator", "Failed to run depth inference", e)
        }

        // 5. Flatten the 2D depth map into a flat FloatArray of size 256*256
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
     * Helper to sample a 3x3 region on the 256x256 depth map,
     * and convert the relative inverse depth to physical distance in meters.
     */
    fun getPhysicalDistance(depthMap: FloatArray, cx: Float, cy: Float): Float {
        // cx, cy are normalized coordinates (0..1)
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

        // Empirical calibration for MiDaS small v2.1:
        // Relative inverse depth is roughly proportional to 1 / distance.
        // We use: distance = scale / (medianDepth + epsilon)
        // With standard MiDaS outputs, a scale of 6.5f to 8.0f calibrates well.
        val scale = 6.5f
        return scale / maxOf(medianDepth, 0.01f)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
