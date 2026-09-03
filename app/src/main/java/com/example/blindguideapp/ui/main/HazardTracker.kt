package com.example.blindguideapp.ui.main

import com.example.blindguideapp.YoloDetector
import kotlin.math.pow
import kotlin.math.sqrt

class TrackedObject(
    val classId: Int,
    val labelEn: String,
    val labelTw: String
) {
    var lastX: Float = 0f
    var lastY: Float = 0f
    var lastArea: Float = 0f
    var lastUpdated: Long = System.currentTimeMillis()
    
    // 歷史紀錄儲存 Pair(時間戳記毫秒, 像素面積)
    val history = mutableListOf<Pair<Long, Float>>()
    
    fun update(x: Float, y: Float, area: Float, timestamp: Long) {
        lastX = x
        lastY = y
        lastArea = area
        lastUpdated = timestamp
        history.add(Pair(timestamp, area))
        // 僅保留過去 1.0 秒內的歷史紀錄以計算短期變化率
        history.removeAll { it.first < timestamp - 1000L }
    }
    
    /**
     * 計算面積變化率（像素/秒）。
     * 正值表示物件正在變大（靠近）。
     */
    fun getAreaChangeRate(): Float {
        if (history.size < 2) return 0f
        val first = history.first()
        val last = history.last()
        val dt = (last.first - first.first) / 1000f // 單位為秒
        if (dt <= 0.05f) return 0f // 避免除以極小數值或零
        return (last.second - first.second) / dt
    }
}

class HazardTracker {
    val trackedObjects = mutableListOf<TrackedObject>()
    
    /**
     * 使用新影格的偵測結果更新追蹤物件。
     * 回傳偵測物件及其計算後的面積變化率（像素/秒）配對清單。
     */
    fun update(detections: List<YoloDetector.Detection>): List<Pair<YoloDetector.Detection, Float>> {
        val currentTime = System.currentTimeMillis()
        val results = mutableListOf<Pair<YoloDetector.Detection, Float>>()
        
        // 僅追蹤具有危險性的物件
        val dangerDetections = detections.filter { it.isDanger }
        
        val matchedTracked = mutableSetOf<TrackedObject>()
        
        for (det in dangerDetections) {
            val cx = (det.x1 + det.x2) / 2f
            val cy = (det.y1 + det.y2) / 2f
            val w = det.x2 - det.x1
            val h = det.y2 - det.y1
            val area = w * h * 640f * 640f // 640x640 空間下的面積
            
            // 尋找同類別中最接近的追蹤物件
            var bestTrack: TrackedObject? = null
            var minDistance = 0.3f // 最大正規化質心距離門檻值
            
            for (track in trackedObjects) {
                if (track in matchedTracked) continue
                if (track.classId != det.classId) continue
                
                val dist = sqrt((cx - track.lastX).pow(2) + (cy - track.lastY).pow(2))
                if (dist < minDistance) {
                    minDistance = dist
                    bestTrack = track
                }
            }
            
            val track = if (bestTrack != null) {
                bestTrack
            } else {
                val newTrack = TrackedObject(det.classId, det.labelEn, det.labelTw)
                trackedObjects.add(newTrack)
                newTrack
            }
            
            track.update(cx, cy, area, currentTime)
            matchedTracked.add(track)
            
            val rate = track.getAreaChangeRate()
            results.add(Pair(det, rate))
        }
        
        // 移除超過 1.5 秒未更新的追蹤物件
        trackedObjects.removeAll { currentTime - it.lastUpdated > 1500L }
        
        return results
    }
}
