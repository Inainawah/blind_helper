package com.example.blindguideapp.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 觸覺提示封裝。轉彎觸發點（2~3 公尺）時呼叫 [shortDoubleBuzz]，
 * 讓視障使用者即使聽漏語音，仍能透過震動確認「現在要轉彎了」。
 */
class GuidanceVibrator(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 短震兩下：震動 120ms、停頓 80ms、再震動 120ms。 */
    fun shortDoubleBuzz() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 120, 80, 120)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 120, 80, 120), -1)
        }
    }
}
