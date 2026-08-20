package com.example.blindguideapp.navigation

import kotlin.math.abs

/**
 * 視障導航三階段轉彎提示核心邏輯。
 *
 * 每個 [GuideStep] 對應 Routes/Directions API 的一個 step。以「目前位置」到
 * step.end（下一個轉折點）的距離為準，觸發：
 *   1. 預告階段（15 公尺）：語音預告
 *   2. 觸覺準備階段（5 公尺）：語音 + 提示使用導盲杖確認地面特徵
 *   3. 轉彎觸發點（2~3 公尺）：短震動兩下 + 「現在 [方向]」
 *
 * 方向詞（前後左右）在每個階段「即時」依據當下手機朝向重新計算，而不是只算
 * 一次，因為使用者行走中身體朝向會持續改變，這正是本模組相對於單純播報
 * Google 原始「向南/向北」文字的核心價值。
 *
 * 完成轉彎的判定採雙保險：
 *   - 主要機制：手機朝向(θ_phone) 與下一段路線方位角(θ_route) 夾角進入容許
 *     範圍內並維持穩定，判定使用者已完成轉身，才進入下一個 step。
 *   - 備援機制：若室內、鬧區等環境導致磁力計不穩，GPS 顯示使用者已相當接近
 *     下一段路線終點時，也會直接前進到下一個 step，避免卡關。
 *
 * 此類別不持有 Android Context，方便單元測試；實際的語音/震動輸出透過建構
 * 子注入的 callback 執行，呼叫端（MainScreen）只需把既有 TTS 與 Vibrator 傳進來。
 */
class TurnByTurnGuide(
    private val steps: List<GuideStep>,
    private val speak: (text: String, flush: Boolean) -> Unit,
    private val vibrateShort: () -> Unit,
    private val onStepAdvanced: (stepIndex: Int) -> Unit = {},
    private val onCompleted: () -> Unit = {}
) {

    data class GuideStep(
        val order: Int,
        val instruction: String,
        val start: GeoPoint,
        val end: GeoPoint,
        val maneuver: String? = null
    )

    private enum class Stage { NONE, ANNOUNCED_PRE, ANNOUNCED_PREPARE, TRIGGERED }

    private var currentIndex = 0
    private var stage = Stage.NONE
    private var lastAzimuth = 0f

    private var awaitingTurnConfirmation = false
    private var turnConfirmStableSinceMs = 0L
    private var targetBearingAfterTurn = 0f

    var isFinished = false
        private set

    /** 由 SensorManager 每次朝向更新時呼叫。 */
    fun onAzimuth(azimuthDegrees: Float) {
        lastAzimuth = azimuthDegrees
        if (awaitingTurnConfirmation) checkTurnConfirmation()
    }

    /** 由 FusedLocationProviderClient 每次定位更新時呼叫。 */
    fun onLocation(current: GeoPoint) {
        if (isFinished || currentIndex >= steps.size) return

        val step = steps[currentIndex]
        val distanceToTurn = distanceMeters(current, step.end)
        val isLastStep = currentIndex == steps.size - 1

        if (isLastStep) {
            handleFinalApproach(distanceToTurn)
            return
        }

        val nextStep = steps[currentIndex + 1]
        val nextBearing = bearingDegrees(step.end, nextStep.end)
        val deltaToPhone = angleDiffTo(lastAzimuth, nextBearing)
        val phrase = DirectionTranslator.relativeDirectionPhrase(deltaToPhone)

        when {
            distanceToTurn <= TRIGGER_MAX_M && stage != Stage.TRIGGERED -> {
                stage = Stage.TRIGGERED
                vibrateShort()
                speak("現在$phrase", true)
                targetBearingAfterTurn = nextBearing
                awaitingTurnConfirmation = true
                turnConfirmStableSinceMs = 0L
            }

            distanceToTurn <= PREPARE_M && stage == Stage.ANNOUNCED_PRE -> {
                stage = Stage.ANNOUNCED_PREPARE
                speak("5 公尺後$phrase，請用導盲杖確認$phrase 轉角或導盲磚", false)
            }

            distanceToTurn <= PRE_M && stage == Stage.NONE -> {
                stage = Stage.ANNOUNCED_PRE
                speak("前方 15 公尺處準備$phrase", false)
            }
        }

        // 備援機制：磁力計判斷失效時，靠近下一段終點也直接前進。
        if (stage == Stage.TRIGGERED) {
            val distancePastTurn = distanceMeters(current, nextStep.end)
            if (distancePastTurn <= FALLBACK_ADVANCE_M) {
                advanceStep()
            }
        }
    }

    private fun checkTurnConfirmation() {
        val diff = abs(angleDiffTo(lastAzimuth, targetBearingAfterTurn))
        val now = System.currentTimeMillis()

        if (diff <= TURN_CONFIRM_TOLERANCE_DEG) {
            if (turnConfirmStableSinceMs == 0L) turnConfirmStableSinceMs = now
            if (now - turnConfirmStableSinceMs >= TURN_CONFIRM_STABLE_MS) {
                advanceStep()
            }
        } else {
            turnConfirmStableSinceMs = 0L
        }
    }

    private fun advanceStep() {
        awaitingTurnConfirmation = false
        turnConfirmStableSinceMs = 0L
        currentIndex++
        stage = Stage.NONE

        if (currentIndex >= steps.size) {
            isFinished = true
            onCompleted()
        } else {
            onStepAdvanced(currentIndex)
        }
    }

    private fun handleFinalApproach(distanceToDestination: Double) {
        when {
            distanceToDestination <= TRIGGER_MAX_M && stage != Stage.TRIGGERED -> {
                stage = Stage.TRIGGERED
                vibrateShort()
                speak("您已抵達目的地附近，導航結束", true)
                isFinished = true
                onCompleted()
            }

            distanceToDestination <= PREPARE_M && stage == Stage.ANNOUNCED_PRE -> {
                stage = Stage.ANNOUNCED_PREPARE
                speak("即將抵達目的地，請放慢腳步", false)
            }

            distanceToDestination <= PRE_M && stage == Stage.NONE -> {
                stage = Stage.ANNOUNCED_PRE
                speak("前方 15 公尺處即為目的地", false)
            }
        }
    }

    companion object {
        const val PRE_M = 15.0
        const val PREPARE_M = 5.0
        const val TRIGGER_MAX_M = 3.0
        const val FALLBACK_ADVANCE_M = 2.0
        const val TURN_CONFIRM_TOLERANCE_DEG = 30f
        const val TURN_CONFIRM_STABLE_MS = 800L
    }
}
