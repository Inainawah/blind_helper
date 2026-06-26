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
    
    // History stores Pair(timestampMs, areaInPixels)
    val history = mutableListOf<Pair<Long, Float>>()
    
    fun update(x: Float, y: Float, area: Float, timestamp: Long) {
        lastX = x
        lastY = y
        lastArea = area
        lastUpdated = timestamp
        history.add(Pair(timestamp, area))
        // Keep history only for the last 1.0 second to calculate short-term rate of change
        history.removeAll { it.first < timestamp - 1000L }
    }
    
    /**
     * Calculates the rate of area change (pixels per second).
     * Positive value indicates the object is getting larger (closer).
     */
    fun getAreaChangeRate(): Float {
        if (history.size < 2) return 0f
        val first = history.first()
        val last = history.last()
        val dt = (last.first - first.first) / 1000f // in seconds
        if (dt <= 0.05f) return 0f // avoid division by very small number or zero
        return (last.second - first.second) / dt
    }
}

class HazardTracker {
    val trackedObjects = mutableListOf<TrackedObject>()
    
    /**
     * Updates tracked objects with new frame detections.
     * Returns a list of detections paired with their calculated area change rate (pixels per second).
     */
    fun update(detections: List<YoloDetector.Detection>): List<Pair<YoloDetector.Detection, Float>> {
        val currentTime = System.currentTimeMillis()
        val results = mutableListOf<Pair<YoloDetector.Detection, Float>>()
        
        // Only track dangerous objects
        val dangerDetections = detections.filter { it.isDanger }
        
        val matchedTracked = mutableSetOf<TrackedObject>()
        
        for (det in dangerDetections) {
            val cx = (det.x1 + det.x2) / 2f
            val cy = (det.y1 + det.y2) / 2f
            val w = det.x2 - det.x1
            val h = det.y2 - det.y1
            val area = w * h * 640f * 640f // Area in 640x640 space
            
            // Find the closest tracked object of same class
            var bestTrack: TrackedObject? = null
            var minDistance = 0.3f // maximum normalized centroid distance threshold
            
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
        
        // Remove tracked objects that haven't been updated for 1.5 seconds
        trackedObjects.removeAll { currentTime - it.lastUpdated > 1500L }
        
        return results
    }
}
