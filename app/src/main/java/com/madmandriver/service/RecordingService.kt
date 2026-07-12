package com.madmandriver.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.madmandriver.Graph
import com.madmandriver.R
import com.madmandriver.city.TransitMode
import com.madmandriver.data.HarshEventEntity
import com.madmandriver.data.RideEntity
import com.madmandriver.data.TraceSampleEntity
import com.madmandriver.detect.DetectedEvent
import com.madmandriver.detect.HarshEventDetector
import com.madmandriver.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordingService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val ACTION_START = "com.madmandriver.action.START"
        private const val ACTION_STOP = "com.madmandriver.action.STOP"
        private const val EXTRA_CITY = "cityId"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_VEHICLE = "vehicle"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** GPS speed change (m/s) across an event needed to call it braking vs acceleration. */
        private const val KIND_SPEED_DELTA_MPS = 1.0f
        private const val STRONG_MPS2 = 3.0f
        private const val EXTREME_MPS2 = 4.5f
        private const val LIVE_TRACE_WINDOW_MS = 75_000L
        private const val TRACE_FLUSH_BATCH = 25 // ~every 5 s at 5 Hz

        fun start(context: Context, cityId: String, mode: TransitMode, line: String, vehicleNumber: String?) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CITY, cityId)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_LINE, line)
                .putExtra(EXTRA_VEHICLE, vehicleNumber)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sensorManager: SensorManager
    private var locationManager: LocationManager? = null
    private var sensorThread: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var detector: HarshEventDetector? = null
    private var recording = false
    private var stopping = false
    private val rideIdDeferred = CompletableDeferred<Long>()

    private var hasGravitySensor = false
    private var hasLinearSensor = false

    /** Latest gravity estimate, device coords. Written on the sensor thread only. */
    private val gravityVec = floatArrayOf(0f, 0f, 9.81f)
    private var gravityInitialized = false

    private var lastUiPushNanos = 0L
    private var eventCount = 0

    /** Live-chart ring buffer and pending Room batch. Sensor thread only (plus one final flush on stop). */
    private var lastTraceNanos = 0L
    private val traceBuffer = ArrayDeque<TracePoint>()
    private val pendingTraceSamples = ArrayList<TracePoint>()

    @Volatile
    private var lastLocation: Location? = null

    /** (wall-clock ms, speed m/s) samples from GPS, newest last. Guarded by [speedLock]. */
    private val speedHistory = ArrayDeque<Pair<Long, Float>>()
    private val speedLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recording) return
        recording = true

        val cityId = intent.getStringExtra(EXTRA_CITY) ?: "sk-bratislava"
        val mode = runCatching {
            TransitMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "")
        }.getOrDefault(TransitMode.BUS)
        val line = intent.getStringExtra(EXTRA_LINE) ?: "?"
        val vehicle = intent.getStringExtra(EXTRA_VEHICLE)?.takeIf { it.isNotBlank() }
        val startedAt = System.currentTimeMillis()

        createNotificationChannel()
        startInForeground(buildNotification("Line $line — recording", "0 harsh events detected"))

        RecordingStateHolder.begin(
            RecordingUiState(
                rideId = null,
                cityId = cityId,
                mode = mode,
                line = line,
                vehicleNumber = vehicle,
                startedAtMs = startedAt,
            )
        )

        scope.launch {
            val id = Graph.db.rideDao().insertRide(
                RideEntity(
                    cityId = cityId,
                    mode = mode.name,
                    line = line,
                    vehicleNumber = vehicle,
                    startedAtUtcMs = startedAt,
                )
            )
            rideIdDeferred.complete(id)
            RecordingStateHolder.mutate { it.copy(rideId = id) }
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "madmandriver:recording")
            .also { it.acquire(4 * 60 * 60 * 1000L) } // 4 h safety cap

        detector = HarshEventDetector(
            triggerMps2 = Graph.settings.current.sensitivity.triggerMps2,
            onSmoothedSample = ::onSmoothedSample,
            onEvent = ::onHarshEvent,
        )

        startSensors()
        startLocationUpdates()
    }

    private fun startSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val thread = HandlerThread("madman-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)

        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        hasLinearSensor = linear != null
        hasGravitySensor = gravity != null

        linear?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        gravity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        if (!hasLinearSensor || !hasGravitySensor) {
            // Fallback: derive gravity (low-pass) and/or linear acceleration from the raw accelerometer.
            accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        }
    }

    private fun startLocationUpdates() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this, sensorThread!!.looper
                )
            }
        } catch (_: SecurityException) {
            // Permission revoked mid-flight; keep recording without GPS.
        }
    }

    // ---- Sensor pipeline (runs on the sensor thread) ----

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || stopping) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravityVec[0] = event.values[0]
                gravityVec[1] = event.values[1]
                gravityVec[2] = event.values[2]
                gravityInitialized = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                detector?.onSample(
                    event.timestamp,
                    event.values[0], event.values[1], event.values[2],
                    gravityVec[0], gravityVec[1], gravityVec[2],
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasGravitySensor) {
                    // Low-pass filter isolates gravity from the raw signal.
                    val alpha = if (gravityInitialized) 0.9f else 0f
                    gravityVec[0] = alpha * gravityVec[0] + (1 - alpha) * event.values[0]
                    gravityVec[1] = alpha * gravityVec[1] + (1 - alpha) * event.values[1]
                    gravityVec[2] = alpha * gravityVec[2] + (1 - alpha) * event.values[2]
                    gravityInitialized = true
                }
                if (!hasLinearSensor) {
                    detector?.onSample(
                        event.timestamp,
                        event.values[0] - gravityVec[0],
                        event.values[1] - gravityVec[1],
                        event.values[2] - gravityVec[2],
                        gravityVec[0], gravityVec[1], gravityVec[2],
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onSmoothedSample(timestampNanos: Long, magnitude: Float) {
        // Live gauge at ~10 Hz so we don't spam recomposition.
        if (timestampNanos - lastUiPushNanos >= 100_000_000L) {
            lastUiPushNanos = timestampNanos
            RecordingStateHolder.mutate { it.copy(currentAccelMps2 = magnitude) }
        }
        // Trace at ~5 Hz: ring buffer for the live chart + batched inserts for the ride record.
        if (timestampNanos - lastTraceNanos >= 200_000_000L) {
            lastTraceNanos = timestampNanos
            val wallMs = elapsedNanosToWallMs(timestampNanos)
            traceBuffer.addLast(TracePoint(wallMs, magnitude))
            while (traceBuffer.isNotEmpty() && wallMs - traceBuffer.first().tMs > LIVE_TRACE_WINDOW_MS) {
                traceBuffer.removeFirst()
            }
            RecordingStateHolder.setTrace(traceBuffer.toList())
            pendingTraceSamples.add(TracePoint(wallMs, magnitude))
            if (pendingTraceSamples.size >= TRACE_FLUSH_BATCH) flushTrace()
        }
    }

    private fun flushTrace() {
        if (pendingTraceSamples.isEmpty()) return
        val batch = pendingTraceSamples.toList()
        pendingTraceSamples.clear()
        scope.launch {
            val rideId = rideIdDeferred.await()
            Graph.db.rideDao().insertTraceSamples(
                batch.map { TraceSampleEntity(rideId = rideId, tMs = it.tMs, accelMps2 = it.accelMps2) }
            )
        }
    }

    private fun onHarshEvent(event: DetectedEvent) {
        if (Graph.settings.current.hapticFeedback) {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
        val startWallMs = elapsedNanosToWallMs(event.startElapsedNanos)
        val endWallMs = elapsedNanosToWallMs(event.endElapsedNanos)
        val location = lastLocation

        scope.launch {
            val rideId = rideIdDeferred.await()
            // Wait for a post-event GPS fix so we can tell braking from acceleration.
            delay(2500)

            var speedBefore: Float? = null
            var speedAfter: Float? = null
            synchronized(speedLock) {
                speedBefore = speedHistory.lastOrNull { it.first <= startWallMs - 300 }?.second
                speedAfter = speedHistory.firstOrNull { it.first >= endWallMs + 500 }?.second
            }
            val kind = classifyKind(speedBefore, speedAfter)
            val severity = when {
                event.peakMps2 >= EXTREME_MPS2 -> "EXTREME"
                event.peakMps2 >= STRONG_MPS2 -> "STRONG"
                else -> "MODERATE"
            }
            val entity = HarshEventEntity(
                rideId = rideId,
                occurredAtUtcMs = startWallMs,
                lat = location?.latitude,
                lon = location?.longitude,
                gpsAccuracyM = location?.accuracy,
                peakMps2 = event.peakMps2,
                meanMps2 = event.meanMps2,
                durationMs = event.durationMs,
                kind = kind,
                severity = severity,
                speedBeforeKmh = speedBefore?.times(3.6f),
                speedAfterKmh = speedAfter?.times(3.6f),
            )
            val eventId = Graph.db.rideDao().insertEvent(entity)
            eventCount++
            RecordingStateHolder.mutate {
                it.copy(eventCount = eventCount, lastEvent = entity.copy(id = eventId))
            }
            updateNotification()
        }
    }

    private fun classifyKind(speedBeforeMps: Float?, speedAfterMps: Float?): String {
        if (speedBeforeMps == null || speedAfterMps == null) return "OTHER"
        val delta = speedAfterMps - speedBeforeMps
        return when {
            delta <= -KIND_SPEED_DELTA_MPS -> "BRAKING"
            delta >= KIND_SPEED_DELTA_MPS -> "ACCELERATION"
            else -> "OTHER"
        }
    }

    private fun elapsedNanosToWallMs(elapsedNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000L

    // ---- Location (runs on the sensor thread looper) ----

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val now = System.currentTimeMillis()
        val speed = if (location.hasSpeed()) location.speed else null
        if (speed != null) {
            synchronized(speedLock) {
                speedHistory.addLast(now to speed)
                while (speedHistory.isNotEmpty() && now - speedHistory.first().first > 120_000L) {
                    speedHistory.removeFirst()
                }
            }
        }
        RecordingStateHolder.mutate {
            it.copy(speedKmh = speed?.times(3.6f), gpsAccuracyM = location.accuracy)
        }
    }

    @Deprecated("Framework callback on old API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    // ---- Stop / teardown ----

    private fun stopRecording() {
        if (!recording || stopping) {
            if (!recording) stopSelf()
            return
        }
        stopping = true
        RecordingStateHolder.mutate { it.copy(stopping = true) }
        updateNotification("Saving ride…")

        sensorManager.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        flushTrace()

        scope.launch {
            // Give in-flight event finalizers (2.5 s GPS wait) time to land.
            delay(2700)
            val rideId = rideIdDeferred.await()
            Graph.db.rideDao().finishRide(rideId, System.currentTimeMillis())
            RecordingStateHolder.clear()
            recording = false
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (recording) {
            sensorManager.unregisterListener(this)
            try {
                locationManager?.removeUpdates(this)
            } catch (_: SecurityException) {
            }
            RecordingStateHolder.clear()
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        sensorThread?.quitSafely()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while a ride is being recorded" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(textOverride: String? = null) {
        val state = RecordingStateHolder.state.value
        val title = "Line ${state?.line ?: "?"} — recording"
        val text = textOverride ?: "$eventCount harsh event${if (eventCount == 1) "" else "s"} detected"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }
}
