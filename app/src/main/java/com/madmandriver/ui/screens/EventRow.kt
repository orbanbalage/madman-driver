package com.madmandriver.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madmandriver.data.HarshEventEntity
import com.madmandriver.ui.theme.severityColor
import com.madmandriver.util.Fmt
import java.time.ZoneId
import java.util.Locale

@Composable
fun EventRow(event: HarshEventEntity, zone: ZoneId) {
    val context = LocalContext.current
    val hasLocation = event.lat != null && event.lon != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasLocation) {
                val uri = String.format(
                    Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Harsh+event)",
                    event.lat, event.lon, event.lat, event.lon
                )
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(severityColor(event.severity), CircleShape)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${kindLabel(event.kind)} — ${String.format(Locale.US, "%.1f m/s²", event.peakMps2)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val speeds = if (event.speedBeforeKmh != null && event.speedAfterKmh != null) {
                    String.format(
                        Locale.US, " · %.0f→%.0f km/h",
                        event.speedBeforeKmh, event.speedAfterKmh
                    )
                } else ""
                Text(
                    "${Fmt.time(event.occurredAtUtcMs, zone)} · ${event.durationMs} ms$speeds" +
                        if (hasLocation) " · map" else " · no GPS",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

fun kindLabel(kind: String): String = when (kind) {
    "BRAKING" -> "Harsh braking"
    "ACCELERATION" -> "Harsh acceleration"
    else -> "Harsh manoeuvre"
}
