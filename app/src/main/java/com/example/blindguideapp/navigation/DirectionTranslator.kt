package com.example.blindguideapp.navigation

/**
 * 將 Google 路線資訊中的「東南西北」語彙，轉換為視障者更容易理解的
 * 「前後左右」相對方向，並清理原始文字中的方位詞。
 *
 * 核心邏輯：
 *   Δθ = θ_route(路線方向) − θ_phone(手機/身體朝向)，正規化到 (-180, 180]
 *   Δθ 越接近 0，代表使用者身體已經正對路線方向（應直行）；
 *   Δθ 為正代表路線在使用者右側，為負代表在左側。
 */
object DirectionTranslator {

    /** 6 個分區的邊界角度（度），對稱設計，涵蓋 -180~180 全範圍。 */
    private const val STRAIGHT_BAND = 20f
    private const val FRONT_DIAGONAL_BAND = 70f
    private const val TURN_BAND = 135f

    /**
     * 依照 Δθ = θ_route − θ_phone，回傳「前後左右」的中文提示詞。
     */
    fun relativeDirectionPhrase(deltaDegrees: Float): String {
        val delta = normalizeAngleDiff(deltaDegrees)
        val abs = kotlin.math.abs(delta)

        return when {
            abs <= STRAIGHT_BAND -> "向前直行"
            abs > TURN_BAND -> "請迴轉"
            delta > 0f && abs <= FRONT_DIAGONAL_BAND -> "向右前方走"
            delta > 0f -> "向右轉"
            abs <= FRONT_DIAGONAL_BAND -> "向左前方走"
            else -> "向左轉"
        }
    }

    /**
     * 移除 Google Directions/Routes API 文字中原始的「向南／向北偏東」等方位詞，
     * 避免視障使用者聽到與身體感受無關的絕對地理方位。
     */
    fun stripCompassWords(text: String): String {
        if (text.isBlank()) return text

        // 涵蓋「向北」「朝東南」「北偏西」「东北」(簡體保險) 等常見組合。
        val compassPattern = Regex(
            "(往|向|朝)?(東北|東南|西北|西南|東|南|西|北)(偏(東北|東南|西北|西南|東|南|西|北))?(方向)?"
        )

        return compassPattern.replace(text, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
