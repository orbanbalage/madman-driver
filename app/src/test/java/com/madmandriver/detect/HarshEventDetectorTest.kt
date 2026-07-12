package com.madmandriver.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarshEventDetectorTest {

    private val g = floatArrayOf(0f, 0f, 9.81f)

    /** Feed [seconds] of samples at 50 Hz with horizontal accel [ax] along x. */
    private fun feed(detector: HarshEventDetector, startNanos: Long, seconds: Double, ax: Float): Long {
        var t = startNanos
        val stepNanos = 20_000_000L // 50 Hz
        val steps = (seconds * 50).toInt()
        repeat(steps) {
            detector.onSample(t, ax, 0f, 0f, g[0], g[1], g[2])
            t += stepNanos
        }
        return t
    }

    @Test
    fun `detects a sustained harsh braking pulse`() {
        val events = mutableListOf<DetectedEvent>()
        val detector = HarshEventDetector(triggerMps2 = 2.0f, onEvent = events::add)

        var t = feed(detector, 0L, 2.0, 0.2f)      // quiet cruise
        t = feed(detector, t, 1.0, 3.5f)           // 1 s harsh pulse
        feed(detector, t, 2.0, 0.2f)               // quiet again

        assertEquals(1, events.size)
        val e = events.first()
        assertTrue("peak ${e.peakMps2}", e.peakMps2 in 3.0f..3.6f)
        assertTrue("duration ${e.durationMs}", e.durationMs in 800..1600)
    }

    @Test
    fun `ignores short bumps below minimum duration`() {
        val events = mutableListOf<DetectedEvent>()
        val detector = HarshEventDetector(triggerMps2 = 2.0f, onEvent = events::add)

        var t = feed(detector, 0L, 2.0, 0.2f)
        t = feed(detector, t, 0.1, 6.0f)           // 100 ms pothole spike
        feed(detector, t, 2.0, 0.2f)

        assertEquals(0, events.size)
    }

    @Test
    fun `ignores vertical acceleration such as cobblestones`() {
        val events = mutableListOf<DetectedEvent>()
        val detector = HarshEventDetector(triggerMps2 = 2.0f, onEvent = events::add)

        // Strong vertical (along gravity) shaking must not trigger.
        var t = 0L
        repeat(200) {
            detector.onSample(t, 0f, 0f, 5.0f, g[0], g[1], g[2])
            t += 20_000_000L
        }
        assertEquals(0, events.size)
    }

    @Test
    fun `two separate pulses produce two events`() {
        val events = mutableListOf<DetectedEvent>()
        val detector = HarshEventDetector(triggerMps2 = 2.0f, onEvent = events::add)

        var t = feed(detector, 0L, 1.0, 0.2f)
        t = feed(detector, t, 0.8, 3.0f)
        t = feed(detector, t, 2.0, 0.2f)
        t = feed(detector, t, 0.8, 4.0f)
        feed(detector, t, 2.0, 0.2f)

        assertEquals(2, events.size)
    }
}
