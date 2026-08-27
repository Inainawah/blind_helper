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
 * 完成轉彎、進入下一個 step 的判定採三層保險，實測發現只靠前兩層在真實走路
 * 情境下太容易永遠卡在同一個 step（導致「只講第一段、後面都不講」）：
 *   1. 主要機制：手機朝向(θ_phone) 與下一段路線方位角(θ_route) 夾角進入容許
 *      範圍內並維持穩定，判定使用者已完成轉身。
 *   2. 備援機制：GPS 顯示使用者已經比「觸發轉彎提示當下」的位置，離那個轉彎
 *      點更遠了一段距離，代表已經走過這個轉角、正在往前走，不需要精準落在
 *      下一段終點座標附近（手機 GPS 精準度常常有好幾公尺誤差，要求精準座標
 *      反而最容易卡住）。
 *   3. 最終保險：不管前兩者有沒有成立，觸發轉彎提示後過了一段時間仍未進入
 *      下一個 step，就直接強制前進，確保導航永遠不會卡死在同一段不動。
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
    private var hasAzimuthReading = false
    private var hasAnnouncedStartingDirection = false

    private var awaitingTurnConfirmation = false
    private var turnConfirmStableSinceMs = 0L
    private var targetBearingAfterTurn = 0f

    // 進入 TRIGGERED 階段當下的距離與時間，供「有沒有走遠一點」「有沒有等太久」判斷用。
    private var distanceAtTriggerM = 0.0
    private var triggeredAtMs = 0L

    var isFinished = false
        private set

    /** 由 SensorManager 每次朝向更新時呼叫。 */
    fun onAzimuth(azimuthDegrees: Float) {
        lastAzimuth = azimuthDegrees
        hasAzimuthReading = true
        if (awaitingTurnConfirmation) checkTurnConfirmation()
    }

    /** 由 FusedLocationProviderClient 每次定位更新時呼叫。 */
    fun onLocation(current: GeoPoint) {
        if (isFinished || currentIndex >= steps.size) return

        // 使用者剛開始導航、還沒走近任何轉彎點時，原本完全不會有語音提示，
        // 等於使用者站在原地不知道第一步要往哪走。這裡在定位跟指南針都
        // 準備好的第一時間，就先講一次「現在該往哪個方向走」。
        if (!hasAnnouncedStartingDirection && hasAzimuthReading) {
            announceStartingDirection()
        }

        val step = steps[currentIndex]
        val distanceToTurn = distanceMeters(current, step.end)
        val isLastStep = currentIndex == steps.size - 1

        if (isLastStep) {
            handleFinalApproach(current, distanceToTurn)
            return
        }

        val nextStep = steps[currentIndex + 1]
        val nextBearing = bearingDegrees(step.end, nextStep.end)
        val deltaToPhone = angleDiffTo(lastAzimuth, nextBearing)
        val phrase = DirectionTranslator.relativeDirectionPhrase(deltaToPhone)

        when {
            distanceToTurn <= TRIGGER_MAX_M && stage != Stage.TRIGGERED -> {
                stage = Stage.TRIGGERED
                distanceAtTriggerM = distanceToTurn
                triggeredAtMs = System.currentTimeMillis()
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

        if (stage == Stage.TRIGGERED) {
            // 備援機制二：已經比觸發當下的位置離這個轉角更遠了，代表走過去了，
            // 不需要精準落在下一段終點座標（GPS 常有數公尺誤差，要求精準最容易卡住）。
            val hasMovedPastTurn = distanceToTurn - distanceAtTriggerM >= MOVED_PAST_TURN_M

            // 備援機制三：不管前面兩層有沒有判定成功，等太久就直接強制前進，
            // 確保導航不會永遠卡在同一段講不出下一句。
            val hasWaitedTooLong =
                System.currentTimeMillis() - triggeredAtMs >= FORCE_ADVANCE_TIMEOUT_MS

            if (hasMovedPastTurn || hasWaitedTooLong) {
                advanceStep()
            }
        }
    }

    /** 導航剛開始、使用者還在原地時，立刻講一次起始方向，不用等接近第一個轉彎點。 */
    private fun announceStartingDirection() {
        hasAnnouncedStartingDirection = true
        val step = steps[currentIndex]
        val routeBearing = bearingDegrees(step.start, step.end)
        val delta = angleDiffTo(lastAzimuth, routeBearing)
        val phrase = DirectionTranslator.relativeDirectionPhrase(delta)
        speak("請先$phrase，開始這趟導航", false)
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

    private fun handleFinalApproach(current: GeoPoint, distanceToDestination: Double) {
        val step = steps[currentIndex]
        // 針對「目的地在哪一邊」，用使用者當下位置直接指向終點座標的方位角，
        // 而不是整段路線的方位角——越接近目的地，這個即時方位才是真正有意義的方向。
        val bearingToDestination = bearingDegrees(current, step.end)
        val deltaToPhone = angleDiffTo(lastAzimuth, bearingToDestination)
        val phrase = DirectionTranslator.relativeDirectionPhrase(deltaToPhone)

        when {
            distanceToDestination <= TRIGGER_MAX_M && stage != Stage.TRIGGERED -> {
                stage = Stage.TRIGGERED
                vibrateShort()
                speak("您已抵達目的地附近，目的地在您的$phrase，導航結束", true)
                isFinished = true
                onCompleted()
            }

            distanceToDestination <= PREPARE_M && stage == Stage.ANNOUNCED_PRE -> {
                stage = Stage.ANNOUNCED_PREPARE
                speak("即將抵達目的地，目的地在您的$phrase，請放慢腳步", false)
            }

            distanceToDestination <= PRE_M && stage == Stage.NONE -> {
                stage = Stage.ANNOUNCED_PRE
                speak("前方 15 公尺處即為目的地，在您的$phrase", false)
            }
        }
    }

    companion object {
        const val PRE_M = 15.0
        const val PREPARE_M = 5.0
        const val TRIGGER_MAX_M = 3.0

        // 走過轉角判定用的「相對移動距離」，故意設得比單純的絕對座標門檻寬鬆，
        // 才不會被手機 GPS 常見的數公尺誤差卡住。
        const val MOVED_PAST_TURN_M = 4.0

        // 觸發轉彎提示後，最多等這麼久還沒判定完成轉彎，就直接強制前進下一段。
        const val FORCE_ADVANCE_TIMEOUT_MS = 20_000L

        const val TURN_CONFIRM_TOLERANCE_DEG = 30f
        const val TURN_CONFIRM_STABLE_MS = 800L
    }
}
