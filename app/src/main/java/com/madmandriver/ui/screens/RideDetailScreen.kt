package com.madmandriver.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.madmandriver.Graph
import com.madmandriver.city.CityCatalog
import com.madmandriver.city.TransitMode
import com.madmandriver.export.Exporter
import com.madmandriver.service.TracePoint
import com.madmandriver.util.Fmt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(rideId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ride by Graph.db.rideDao().rideFlow(rideId).collectAsState(initial = null)
    val events by Graph.db.rideDao().eventsFlow(rideId).collectAsState(initial = emptyList())
    val traceSamples by Graph.db.rideDao().traceFlow(rideId).collectAsState(initial = emptyList())
    val tracePoints = remember(traceSamples) {
        traceSamples.map { TracePoint(it.tMs, it.accelMps2) }
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    val currentRide = ride ?: return
    val city = CityCatalog.byId(currentRide.cityId)
    val mode = runCatching { TransitMode.valueOf(currentRide.mode) }.getOrDefault(TransitMode.BUS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${mode.label} ${currentRide.line}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ride details")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ride")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${city.name} · ${Fmt.dateTime(currentRide.startedAtUtcMs, city.timeZone)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        currentRide.vehicleNumber?.let { "Vehicle $it" } ?: "Vehicle number not set",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val end = currentRide.endedAtUtcMs
                    if (end != null) {
                        Text(
                            "Duration ${Fmt.elapsed(end - currentRide.startedAtUtcMs)} · " +
                                "${events.size} harsh event${if (events.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (tracePoints.size >= 2) {
                Card(Modifier.fillMaxWidth()) {
                    AccelChart(
                        points = tracePoints,
                        events = events,
                        triggerMps2 = Graph.settings.current.sensitivity.triggerMps2,
                        windowMs = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(8.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val uris = Exporter.writeCsvFiles(context, city, currentRide, events, traceSamples)
                            context.startActivity(
                                Intent.createChooser(Exporter.shareCsvIntent(uris), "Share CSV")
                            )
                        }
                    },
                    enabled = events.isNotEmpty() || traceSamples.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("CSV")
                }
                Button(
                    onClick = {
                        scope.launch {
                            val uris = Exporter.writeCsvFiles(context, city, currentRide, events, traceSamples)
                            val intent = Exporter.reportEmailIntent(
                                city, Graph.settings.current.agencyEmail, currentRide, events, uris
                            )
                            context.startActivity(Intent.createChooser(intent, "Send report"))
                        }
                    },
                    enabled = events.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Report to ${city.agencyShortName}")
                }
            }

            if (events.isEmpty()) {
                Text("No harsh events on this ride — smooth driving!", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(events, key = { it.id }) { event ->
                        EventRow(event = event, zone = city.timeZone)
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        EditRideDialog(
            city = city,
            initialMode = mode,
            initialLine = currentRide.line,
            initialVehicleNumber = currentRide.vehicleNumber,
            onDismiss = { showEditDialog = false },
            onSave = { newMode, newLine, newVehicle ->
                showEditDialog = false
                scope.launch {
                    Graph.db.rideDao().updateRideDetails(rideId, newMode.name, newLine, newVehicle)
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ride?") },
            text = { Text("The ride and all its recorded events will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        Graph.db.rideDao().deleteRide(rideId)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
