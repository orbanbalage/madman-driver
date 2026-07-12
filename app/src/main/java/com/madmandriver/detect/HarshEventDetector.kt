package com.madmandriver.detect

import kotlin.math.sqrt

/** One completed harsh-driving event, timestamps in the sensor time base (elapsed realtime nanos). */
data class DetectedEvent(
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    val peakMps2: Float,
    val meanMps2: Float,
) {
    val durationMs: Long get() = (endElapsedNanos - startElapsedNanos) / 1_000_000L
}

/**
 * Orientation-independent harsh acceleration/braking detector.
 *
 * The phone can sit in a pocket or be held in any orientation, so instead of
 * assuming axes we project linear acceleration onto the horizontal plane using
 * the current gravity vector, which isolates vehicle accelerating/braking/
 * cornering forces from bumps (mostly vertical) and from gravity itself.
 *
 * The horizontal magnitude is smoothed over a short moving window to reject
 * hand jitter and cobblestone noise, then run through a hysteresis state
 * machine: an event opens when the smoothed value crosses [triggerMps2] and
 * closes when it falls below [releaseMps2]. Events shorter than
 * [minDurationNanos] are dropped as road bumps rather than driver behaviour.
 *
 * Not thread-safe; feed it from a single sensor thread.
 */
class HarshEventDetector(
    private val triggerMps2: Float,
    private val releaseMps2: Float = triggerMps2 * 0.7f,
    private val smoothWindowNanos: Long = 400_000_000L,
    private val minDurationNanos: Long = 350_000_000L,
    private val onSmoothedSample: (timestampNanos: Long, magnitudeMps2: Float) -> Unit = { _, _ -> },
    private val onEvent: (DetectedEvent) -> Unit,
) {
    private class Sample(val t: Long, val mag: Float)

    private val window = ArrayDeque<Sample>()
    private var windowSum = 0f

    private var inEvent = false
    private var eventStart = 0L
    private var peak = 0f
    private var magSum = 0f
    private var magCount = 0

    /**
     * @param t  sensor timestamp, elapsed realtime nanos
     * @param ax/ay/az linear acceleration (gravity already removed), device coords, m/s²
     * @param gx/gy/gz current gravity vector estimate, device coords, m/s²
     */
    fun onSample(t: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        val gMag = sqrt(gx * gx + gy * gy + gz * gz)
        val horizontal = if (gMag > 1f) {
            val dot = (ax * gx + ay * gy + az * gz) / gMag
            val hx = ax - dot * gx / gMag
            val hy = ay - dot * gy / gMag
            val hz = az - dot * gz / gMag
            sqrt(hx * hx + hy * hy + hz * hz)
        } else {
            // No usable gravity estimate yet — fall back to full magnitude.
            sqrt(ax * ax + ay * ay + az * az)
        }

        window.addLast(Sample(t, horizontal))
        windowSum += horizontal
        while (window.isNotEmpty() && t - window.first().t > smoothWindowNanos) {
            windowSum -= window.removeFirst().mag
        }
        val smoothed = windowSum / window.size
        onSmoothedSample(t, smoothed)

        if (!inEvent) {
            if (smoothed >= triggerMps2) {
                inEvent = true
                eventStart = t
                peak = smoothed
                magSum = smoothed
                magCount = 1
            }
        } else {
            if (smoothed > peak) peak = smoothed
            magSum += smoothed
            magCount++
            if (smoothed < releaseMps2) {
                inEvent = false
                if (t - eventStart >= minDurationNanos) {
                    onEvent(DetectedEvent(eventStart, t, peak, magSum / magCount))
                }
            }
        }
    }

    fun reset() {
        window.clear()
        windowSum = 0f
        inEvent = false
    }
}
