package com.madmandriver.service

import com.madmandriver.city.TransitMode
import com.madmandriver.data.HarshEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One point of the live acceleration trace (wall-clock ms, smoothed horizontal m/s²). */
data class TracePoint(val tMs: Long, val accelMps2: Float)

/** Live recording state shared between [RecordingService] and the UI. Null when idle. */
data class RecordingUiState(
    val rideId: Long?,
    val cityId: String,
    val mode: TransitMode,
    val line: String,
    val vehicleNumber: String?,
    val startedAtMs: Long,
    val eventCount: Int = 0,
    val currentAccelMps2: Float = 0f,
    val speedKmh: Float? = null,
    val gpsAccuracyM: Float? = null,
    val lastEvent: HarshEventEntity? = null,
    val stopping: Boolean = false,
)

object RecordingStateHolder {
    private val _state = MutableStateFlow<RecordingUiState?>(null)
    val state = _state.asStateFlow()

    /** Recent acceleration trace for the live chart (last ~75 s). */
    private val _trace = MutableStateFlow<List<TracePoint>>(emptyList())
    val trace = _trace.asStateFlow()

    fun begin(initial: RecordingUiState) {
        _trace.value = emptyList()
        _state.value = initial
    }

    fun mutate(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update { it?.let(transform) }
    }

    fun setTrace(points: List<TracePoint>) {
        _trace.value = points
    }

    fun clear() {
        _state.value = null
        _trace.value = emptyList()
    }
}
