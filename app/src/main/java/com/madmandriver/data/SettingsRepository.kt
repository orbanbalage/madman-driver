package com.madmandriver.data

import android.content.Context
import com.madmandriver.city.CityCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Detection sensitivity. The trigger is the smoothed horizontal acceleration
 * (m/s²) that opens an event. Comfort studies put ~1.5–2 m/s² as the point
 * where standing passengers have to brace; ~3 m/s² is a stumble/fall risk.
 */
enum class Sensitivity(val label: String, val triggerMps2: Float) {
    RELAXED("Relaxed (2.5 m/s²)", 2.5f),
    NORMAL("Normal (2.0 m/s²)", 2.0f),
    SENSITIVE("Sensitive (1.6 m/s²)", 1.6f),
}

data class Settings(
    val cityId: String,
    val sensitivity: Sensitivity,
    val hapticFeedback: Boolean,
    val keepScreenOn: Boolean,
    val agencyEmail: String,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow

    val current: Settings get() = _flow.value

    private fun load(): Settings {
        val cityId = prefs.getString("cityId", CityCatalog.bratislava.id)!!
        val city = CityCatalog.byId(cityId)
        return Settings(
            cityId = cityId,
            sensitivity = runCatching {
                Sensitivity.valueOf(prefs.getString("sensitivity", Sensitivity.NORMAL.name)!!)
            }.getOrDefault(Sensitivity.NORMAL),
            hapticFeedback = prefs.getBoolean("hapticFeedback", true),
            keepScreenOn = prefs.getBoolean("keepScreenOn", true),
            agencyEmail = prefs.getString("agencyEmail", city.defaultAgencyEmail)!!,
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_flow.value)
        prefs.edit()
            .putString("cityId", next.cityId)
            .putString("sensitivity", next.sensitivity.name)
            .putBoolean("hapticFeedback", next.hapticFeedback)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putString("agencyEmail", next.agencyEmail)
            .apply()
        _flow.value = next
    }
}
