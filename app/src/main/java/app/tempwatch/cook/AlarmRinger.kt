package app.tempwatch.cook

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.tempwatch.data.AlarmSound

/**
 * The "it's done" alarm when Chroma Noise doesn't ring it: the chosen phone sound (or the
 * phone's default alarm for "Soft chimes", which only Chroma Noise has), looping, on the alarm volume (so it isn't
 * silenced like a notification would be), plus vibration. Stops when you tap Stop alarm, end
 * the cook, or after 10 minutes.
 */
object AlarmRinger {
    private const val MAX_RING_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val timeout = Runnable { stop() }

    val isRinging: Boolean get() = ringtone != null || vibrator != null

    fun start(context: Context, sound: AlarmSound) {
        stop()
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }
        if (vib != null) {
            runCatching { vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 1500), 0)) }
            vibrator = vib
        }

        // Vibrate only, unless the phone can't vibrate: the alarm must never be silent by accident.
        if (!sound.silent || vib == null) {
            val chosen = sound.uri?.takeIf { !sound.silent }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val uri = chosen
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }.getOrNull()
        }
        main.postDelayed(timeout, MAX_RING_MS)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }
}
