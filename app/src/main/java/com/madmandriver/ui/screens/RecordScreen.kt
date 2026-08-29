package com.madmandriver.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.madmandriver.Graph
import com.madmandriver.city.CityCatalog
import com.madmandriver.city.TransitMode
import com.madmandriver.service.RecordingService
import com.madmandriver.service.RecordingStateHolder
import com.madmandriver.service.RecordingUiState
import com.madmandriver.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

@Composable
fun RecordScreen() {
    val recordingState by RecordingStateHolder.state.collectAsState()
    val state = recordingState
    if (state == null) {
        RideSetup()
    } else {
        LiveRide(state)
    }
}

@Composable
private fun RideSetup() {
    val context = LocalContext.current
    val city = CityCatalog.byId(Graph.settings.current.cityId)

    var mode by remember { mutableStateOf(TransitMode.BUS) }
    var line by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf("") }

    fun startRecording() {
        RecordingService.start(context, city.id, mode, line.trim(), vehicle.trim().ifBlank { null })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Start even if location was denied — events are still recorded, just without GPS.
        startRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("New ride", style = MaterialTheme.typography.headlineMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(city.name + ", " + city.country, style = MaterialTheme.typography.titleMedium)
                Text(city.agencyName, style = MaterialTheme.typography.bodySmall)
            }
        }

        RideDetailsEditor(
            city = city,
            mode = mode,
            onModeChange = { mode = it },
            line = line,
            onLineChange = { line = it },
            vehicleNumber = vehicle,
            onVehicleNumberChange = { vehicle = it },
        )

        Button(
            onClick = {
                val permissions = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            },
            enabled = line.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start recording")
        }

        Text(
            "Keep the phone still (pocket, bag or hand) during the ride. Location permission is " +
                "recommended so each event carries GPS coordinates and speed. Line and vehicle " +
                "number can still be corrected after the ride.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LiveRide(state: RecordingUiState) {
    val context = LocalContext.current
    val settings by Graph.settings.flow.collectAsState()

    // Keep the screen on during recording if enabled in settings.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val city = CityCatalog.byId(state.cityId)
    val events by remember(state.rideId) {
        state.rideId?.let { Graph.db.rideDao().eventsFlow(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val trace by RecordingStateHolder.trace.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${state.mode.label} ${state.line} — ${Fmt.elapsed(nowMs - state.startedAtMs)}",
            style = MaterialTheme.typography.headlineSmall,
        )

        val trigger = settings.sensitivity.triggerMps2
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccelGauge(
                    valueMps2 = state.currentAccelMps2,
                    triggerMps2 = trigger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.speedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "— km/h",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        state.gpsAccuracyM?.let { String.format(Locale.US, "GPS ±%.0f m", it) } ?: "no GPS fix",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("trigger $trigger m/s²", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Card {
            AccelChart(
                points = trace,
                events = events,
                triggerMps2 = trigger,
                windowMs = 60_000L,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(8.dp),
            )
        }

        Text(
            "${state.eventCount} harsh event${if (state.eventCount == 1) "" else "s"}",
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(events.reversed(), key = { it.id }) { event ->
                EventRow(event = event, zone = city.timeZone)
            }
        }

        Button(
            onClick = { RecordingService.stop(context) },
            enabled = !state.stopping,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.stopping) "Saving…" else "Stop & save ride")
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
