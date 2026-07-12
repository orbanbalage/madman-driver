package com.madmandriver.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.madmandriver.Graph
import com.madmandriver.city.CityCatalog
import com.madmandriver.city.TransitMode
import com.madmandriver.util.Fmt

@Composable
fun HistoryScreen(onOpenRide: (Long) -> Unit) {
    val rides by Graph.db.rideDao().ridesWithCounts().collectAsState(initial = emptyList())

    if (rides.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No rides recorded yet", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rides, key = { it.ride.id }) { item ->
            val city = CityCatalog.byId(item.ride.cityId)
            val mode = runCatching { TransitMode.valueOf(item.ride.mode) }
                .getOrDefault(TransitMode.BUS)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenRide(item.ride.id) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${mode.label} ${item.ride.line} · ${city.name}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            Fmt.dateTime(item.ride.startedAtUtcMs, city.timeZone) +
                                (item.ride.vehicleNumber?.let { " · vehicle $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Badge {
                        Text("${item.eventCount}")
                    }
                }
            }
        }
    }
}
