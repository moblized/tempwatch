package app.tempwatch.data

import android.content.Context
import android.content.SharedPreferences
import app.tempwatch.cook.TempUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The done alarm's sound. [value]: a sound's URI (a phone sound or your own file), or one of
 * [MATCH], [CHIMES_VALUE], [SILENT]. Chroma Noise plays all of them; when TempWatch rings by
 * itself, "match" and "chimes" mean the phone's default alarm.
 */
data class AlarmSound(val value: String, val name: String) {
    val silent: Boolean get() = value == SILENT

    /** What to send Chroma Noise: null means its soft chimes. */
    val forChroma: String? get() = value.takeUnless { it == CHIMES_VALUE }

    /** A sound TempWatch can play itself (phone sound or your own file), else null. */
    val uri: String? get() = value.takeIf { "://" in it }

    /** Only Chroma Noise can play these. */
    val needsChroma: Boolean get() = value == MATCH || value == CHIMES_VALUE

    companion object {
        const val SILENT = "silent"
        const val MATCH = "match_alarm"
        private const val CHIMES_VALUE = "chimes"
        val MATCH_ALARM = AlarmSound(MATCH, "Same as my Chroma Noise alarm")
        val CHIMES = AlarmSound(CHIMES_VALUE, "Soft chimes")
        val VIBRATE_ONLY = AlarmSound(SILENT, "No sound (vibrate only)")

        fun fromStored(value: String?, name: String?): AlarmSound = when (value) {
            null, MATCH -> MATCH_ALARM
            CHIMES_VALUE -> CHIMES
            SILENT -> VIBRATE_ONLY
            else -> AlarmSound(value, name ?: "Phone sound")
        }
    }
}

/** Small settings store. Each value is also a StateFlow so screens update when it changes. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _unit = MutableStateFlow(TempUnit.F)
    val unit: StateFlow<TempUnit> = _unit

    private val _headsUp = MutableStateFlow(true)
    /** Send an "almost done" alert 5°F / 3°C before the target. */
    val headsUp: StateFlow<Boolean> = _headsUp

    private val _keepScreenOn = MutableStateFlow(true)
    /** Keep the screen awake while the app is open during a cook. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    private val _ringWithChroma = MutableStateFlow(true)
    /** Ring the done alarm in Chroma Noise when it's installed (otherwise the phone's alarm sound). */
    val ringWithChroma: StateFlow<Boolean> = _ringWithChroma

    private val _alarmSound = MutableStateFlow(AlarmSound.MATCH_ALARM)
    val alarmSound: StateFlow<AlarmSound> = _alarmSound

    private val _devices = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Boosters seen before: Bluetooth address → advertised name ("" if none). */
    val devices: StateFlow<Map<String, String>> = _devices

    fun init(context: Context) {
        sp = context.getSharedPreferences("tempwatch", Context.MODE_PRIVATE)
        _unit.value = if (sp.getString(KEY_UNIT, "F") == "C") TempUnit.C else TempUnit.F
        _headsUp.value = sp.getBoolean(KEY_HEADS_UP, true)
        _keepScreenOn.value = sp.getBoolean(KEY_SCREEN_ON, true)
        _ringWithChroma.value = sp.getBoolean(KEY_RING_WITH_CHROMA, true)
        _alarmSound.value = AlarmSound.fromStored(sp.getString(KEY_ALARM_SOUND, null), sp.getString(KEY_ALARM_SOUND_NAME, null))
        _devices.value = (sp.getStringSet(KEY_DEVICES, emptySet()) ?: emptySet())
            .associateWith { sp.getString(nameKey(it), "") ?: "" }
    }

    fun setUnit(unit: TempUnit) {
        _unit.value = unit
        sp.edit().putString(KEY_UNIT, unit.name).apply()
    }

    fun setHeadsUp(on: Boolean) {
        _headsUp.value = on
        sp.edit().putBoolean(KEY_HEADS_UP, on).apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        sp.edit().putBoolean(KEY_SCREEN_ON, on).apply()
    }

    fun setRingWithChroma(on: Boolean) {
        _ringWithChroma.value = on
        sp.edit().putBoolean(KEY_RING_WITH_CHROMA, on).apply()
    }

    fun setAlarmSound(sound: AlarmSound) {
        _alarmSound.value = sound
        sp.edit().putString(KEY_ALARM_SOUND, sound.value).putString(KEY_ALARM_SOUND_NAME, sound.name).apply()
    }

    /** Remembers a booster so it can be found with the screen off (see BleScanner filters). */
    fun rememberDevice(address: String, name: String?) {
        val current = _devices.value
        val newName = name.orEmpty()
        if (current[address] == newName || (address in current && newName.isEmpty())) return
        val next = current + (address to newName)
        _devices.value = next
        sp.edit()
            .putStringSet(KEY_DEVICES, next.keys.toSet())
            .putString(nameKey(address), newName)
            .apply()
    }

    fun forgetDevice(address: String) {
        val next = _devices.value - address
        _devices.value = next
        sp.edit().putStringSet(KEY_DEVICES, next.keys.toSet()).remove(nameKey(address)).apply()
    }

    private fun nameKey(address: String) = "name_$address"

    private const val KEY_UNIT = "unit"
    private const val KEY_HEADS_UP = "heads_up"
    private const val KEY_DEVICES = "devices"
    private const val KEY_SCREEN_ON = "keep_screen_on"
    private const val KEY_RING_WITH_CHROMA = "ring_with_chroma"
    // v2: 1.0.11 stored the soft chimes as "no value", which now means "same as my alarm".
    private const val KEY_ALARM_SOUND = "alarm_sound"
    private const val KEY_ALARM_SOUND_NAME = "alarm_sound_name"
}
