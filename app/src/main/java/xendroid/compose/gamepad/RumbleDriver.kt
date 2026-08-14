package xendroid.compose.gamepad

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice

/**
 * Plays the motor speeds the guest asked for on the pad that owns them.
 *
 * The guest sets a level and leaves it, so effects are issued as a repeating
 * waveform and cancelled on zero rather than re-sent per poll. Main thread only.
 */
class RumbleDriver(context: Context) {

    private val appContext = context.applicationContext
    private val systemVibrator: Vibrator? = resolveSystemVibrator()
    private val lastAmplitude = HashMap<Int, Int>()

    /**
     * @param state left/right pairs indexed by device slot, as returned by native
     * @param vibratorFor device slot -> vibrator, null for slots with no motor
     */
    fun apply(state: IntArray, vibratorFor: (Int) -> Vibrator?) {
        for (slot in 0 until state.size / 2) {
            val left = state[slot * 2]
            val right = state[slot * 2 + 1]
            // One motor: take the stronger of the two, mapped onto 1..255.
            val strongest = maxOf(left, right)
            val amplitude = if (strongest <= 0) 0 else (strongest * 255 / 65535).coerceIn(1, 255)
            if (lastAmplitude[slot] == amplitude) continue
            lastAmplitude[slot] = amplitude
            val vibrator = vibratorFor(slot)
            Log.i(TAG, "slot=$slot guest=$left/$right amplitude=$amplitude " +
                "vibrator=${vibrator != null} amplitudeControl=${vibrator?.hasAmplitudeControl()}")
            if (vibrator == null) continue
            runCatching {
                if (amplitude == 0) {
                    vibrator.cancel()
                } else {
                    play(vibrator, amplitude)
                }
            }
        }
    }

    // Tagged as game usage so the system touch-feedback setting does not filter it.
    private fun play(vibrator: Vibrator, amplitude: Int) {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, PULSE_MS),
            intArrayOf(0, amplitude),
            1,  // repeat the on-phase until cancelled
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, gameAudioAttributes)
        }
    }

    private val gameAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun stopAll(vibratorFor: (Int) -> Vibrator?) {
        lastAmplitude.keys.toList().forEach { slot ->
            runCatching { vibratorFor(slot)?.cancel() }
        }
        lastAmplitude.clear()
    }

    /** Falls back to the console's own motor for the on-screen overlay, and when
     *  [fallbackToSystem] for a motorless pad: a handheld's built-in controls
     *  rumble through the system vibrator. */
    fun vibratorForDevice(deviceId: Int?, fallbackToSystem: Boolean = false): Vibrator? {
        if (deviceId == null) return systemVibrator
        val device = InputDevice.getDevice(deviceId) ?: return null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            device.vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
            ?: systemVibrator.takeIf { fallbackToSystem }
    }

    private fun resolveSystemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }

    private companion object {
        const val PULSE_MS = 60_000L
        const val TAG = "XenDroidRumble"
    }
}
