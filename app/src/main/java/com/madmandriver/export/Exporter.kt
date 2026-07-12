package com.madmandriver.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.madmandriver.city.City
import com.madmandriver.city.TransitMode
import com.madmandriver.data.HarshEventEntity
import com.madmandriver.data.RideEntity
import com.madmandriver.data.TraceSampleEntity
import com.madmandriver.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object Exporter {

    fun csvContent(city: City, ride: RideEntity, events: List<HarshEventEntity>): String {
        val sb = StringBuilder()
        sb.append("city,country,mode,line,vehicle_number,event_time_utc_ms,event_time_local,")
        sb.append("lat,lon,gps_accuracy_m,kind,severity,peak_ms2,mean_ms2,duration_ms,")
        sb.append("speed_before_kmh,speed_after_kmh\n")
        for (e in events) {
            sb.append(
                listOf(
                    city.name,
                    city.country,
                    ride.mode,
                    csv(ride.line),
                    csv(ride.vehicleNumber ?: ""),
                    e.occurredAtUtcMs.toString(),
                    Fmt.dateTime(e.occurredAtUtcMs, city.timeZone),
                    e.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                    e.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                    e.gpsAccuracyM?.let { String.format(Locale.US, "%.0f", it) } ?: "",
                    e.kind,
                    e.severity,
                    String.format(Locale.US, "%.2f", e.peakMps2),
                    String.format(Locale.US, "%.2f", e.meanMps2),
                    e.durationMs.toString(),
                    e.speedBeforeKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    e.speedAfterKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                ).joinToString(",")
            )
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun csv(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Full acceleration curve of the ride (~5 Hz), one row per sample. */
    fun traceCsvContent(city: City, samples: List<TraceSampleEntity>): String {
        val sb = StringBuilder("t_utc_ms,t_local,accel_ms2\n")
        for (s in samples) {
            sb.append(s.tMs).append(',')
                .append(Fmt.dateTime(s.tMs, city.timeZone)).append(',')
                .append(String.format(Locale.US, "%.2f", s.accelMps2)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Writes the events CSV plus (when a trace was recorded) the acceleration
     * trace CSV, returning share-ready content URIs.
     */
    suspend fun writeCsvFiles(
        context: Context,
        city: City,
        ride: RideEntity,
        events: List<HarshEventEntity>,
        traceSamples: List<TraceSampleEntity>,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stem = "madman-driver_${city.id}_line-${ride.line}_${Fmt.fileStamp(ride.startedAtUtcMs, city.timeZone)}"
        val uris = mutableListOf<Uri>()

        val eventsFile = File(dir, "$stem.csv")
        eventsFile.writeText(csvContent(city, ride, events))
        uris += FileProvider.getUriForFile(context, "com.madmandriver.fileprovider", eventsFile)

        if (traceSamples.isNotEmpty()) {
            val traceFile = File(dir, "${stem}_trace.csv")
            traceFile.writeText(traceCsvContent(city, traceSamples))
            uris += FileProvider.getUriForFile(context, "com.madmandriver.fileprovider", traceFile)
        }
        uris
    }

    fun shareCsvIntent(csvUris: List<Uri>): Intent = withAttachments(Intent(), csvUris).apply { type = "text/csv" }

    fun reportEmailIntent(
        city: City,
        agencyEmail: String,
        ride: RideEntity,
        events: List<HarshEventEntity>,
        csvUris: List<Uri>,
    ): Intent =
        withAttachments(Intent(), csvUris).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(agencyEmail))
            putExtra(Intent.EXTRA_SUBJECT, ReportBuilder.subject(city, ride))
            putExtra(Intent.EXTRA_TEXT, ReportBuilder.body(city, ride, events))
        }

    private fun withAttachments(intent: Intent, uris: List<Uri>): Intent = intent.apply {
        if (uris.size == 1) {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

object ReportBuilder {

    fun subject(city: City, ride: RideEntity): String {
        val mode = modeLabel(ride.mode)
        val date = Fmt.date(ride.startedAtUtcMs, city.timeZone)
        return "Podnet: nekomfortná/nebezpečná jazda — $mode ${ride.line}, $date"
    }

    /**
     * Bilingual (Slovak + English) complaint body. Slovak first because v1
     * targets DPB Bratislava; when more cities are added this should move to
     * per-city templates selected via [City].
     */
    fun body(city: City, ride: RideEntity, events: List<HarshEventEntity>): String {
        val zone = city.timeZone
        val mode = modeLabel(ride.mode)
        val date = Fmt.date(ride.startedAtUtcMs, zone)
        val start = Fmt.time(ride.startedAtUtcMs, zone)
        val end = ride.endedAtUtcMs?.let { Fmt.time(it, zone) } ?: "?"
        val vehicle = ride.vehicleNumber?.let { "\nČíslo vozidla / Vehicle number: $it" } ?: ""

        val eventLines = events.joinToString("\n") { e ->
            val time = Fmt.time(e.occurredAtUtcMs, zone)
            val kind = when (e.kind) {
                "BRAKING" -> "prudké brzdenie / harsh braking"
                "ACCELERATION" -> "prudké zrýchlenie / harsh acceleration"
                else -> "prudký manéver / harsh manoeuvre"
            }
            val map = if (e.lat != null && e.lon != null) {
                String.format(Locale.US, " — https://maps.google.com/?q=%.6f,%.6f", e.lat, e.lon)
            } else ""
            String.format(Locale.US, "• %s — %s, špička/peak %.1f m/s², %d ms%s", time, kind, e.peakMps2, e.durationMs, map)
        }

        return """
Dobrý deň,

počas jazdy ${mode.lowercase(Locale.getDefault())}om linky ${ride.line} dňa $date (nástup $start, výstup $end) som zaznamenal/a opakované prudké brzdenie a zrýchľovanie, ktoré ohrozovalo pohodlie a bezpečnosť cestujúcich.$vehicle

Namerané udalosti (čas miestny, ${city.name}):
$eventLines

Merania boli zaznamenané akcelerometrom smartfónu aplikáciou Madman Driver; podrobné dáta sú v priloženom CSV súbore. Prosím o preverenie, ktorý vodič viedol uvedený spoj v danom čase.

Ďakujem.

---

Dear ${city.agencyName},

During my ride on ${mode.lowercase(Locale.getDefault())} line ${ride.line} on $date (boarded $start, alighted $end) I experienced repeated harsh braking and acceleration that compromised passenger comfort and safety.${if (ride.vehicleNumber != null) "\nVehicle number: ${ride.vehicleNumber}" else ""}

Measured events (local time, ${city.name}) are listed above with map links. The measurements were recorded with a smartphone accelerometer by the Madman Driver app; detailed data is in the attached CSV file. Please identify the driver operating this service at the given time and location.

Thank you.
""".trimIndent()
    }

    private fun modeLabel(mode: String): String = when (mode) {
        TransitMode.TRAM.name -> "Električka / Tram"
        TransitMode.TROLLEYBUS.name -> "Trolejbus / Trolleybus"
        else -> "Autobus / Bus"
    }
}
