package com.madmandriver.city

import java.time.ZoneId

enum class TransitMode(val label: String) {
    BUS("Bus"),
    TRAM("Tram"),
    TROLLEYBUS("Trolleybus");
}

/**
 * A supported city / transit network. The whole app is keyed off this model so
 * adding another city (or country) later means adding one more entry here —
 * or, eventually, loading these from a remote registry.
 */
data class City(
    val id: String,
    val name: String,
    val country: String,
    val timeZone: ZoneId,
    val agencyName: String,
    /** Short operator abbreviation for buttons and compact UI. */
    val agencyShortName: String,
    val defaultAgencyEmail: String,
    val modes: List<TransitMode>,
    /** Known line numbers per mode, offered as quick suggestions. Free-text entry is always allowed. */
    val suggestedLines: Map<TransitMode, List<String>>,
    /**
     * Official line badge colours (ARGB), as published by the city's transit
     * portal — for Bratislava that is imhd.sk. Only lines carrying a distinctive
     * colour are listed; everything else falls back to [defaultLineColor], which
     * is the same neutral grey the portal uses for undistinguished lines.
     */
    val lineColors: Map<String, Long> = emptyMap(),
    val defaultLineColor: Long = 0xFF808080L,
) {
    fun lineColorArgb(line: String): Long = lineColors[line.trim()] ?: defaultLineColor
}

/**
 * Line badge colours read from imhd.sk (the Bratislava transit portal). Lines
 * the portal leaves neutral grey are intentionally absent — see
 * [City.defaultLineColor]. Every colour here is paired with white text there.
 */
private val BRATISLAVA_LINE_COLORS: Map<String, Long> = mapOf(
    // Trams
    "1" to 0xFFF56200L, "3" to 0xFFE31E24L, "4" to 0xFF5071B9L, "9" to 0xFF849717L,
    // Trolleybuses
    "33" to 0xFF776AB4L, "40" to 0xFFE51A4BL, "42" to 0xFFA65430L, "44" to 0xFFA979B3L,
    "45" to 0xFF898989L, "47" to 0xFFAD8B00L, "49" to 0xFFD16C1AL, "60" to 0xFF2A4493L,
    "64" to 0xFF0E562CL, "71" to 0xFF007DB3L, "72" to 0xFF899D1BL,
    // Buses
    "21" to 0xFF338632L, "37" to 0xFFE31E24L, "50" to 0xFF2EA0B0L, "61" to 0xFFA21517L,
    "63" to 0xFF642F6CL, "68" to 0xFF887917L, "80" to 0xFFD77E09L, "83" to 0xFF007AB8L,
    "84" to 0xFF007AB8L, "88" to 0xFF642F6CL, "93" to 0xFFAB075FL, "94" to 0xFFAB075FL,
    "96" to 0xFF338632L, "98" to 0xFFB25C2EL, "99" to 0xFFEE639FL,
)

object CityCatalog {
    val bratislava = City(
        id = "sk-bratislava",
        name = "Bratislava",
        country = "Slovakia",
        timeZone = ZoneId.of("Europe/Bratislava"),
        agencyName = "Dopravný podnik Bratislava, a.s. (DPB)",
        agencyShortName = "DPB",
        defaultAgencyEmail = "info@dpb.sk",
        modes = listOf(TransitMode.BUS, TransitMode.TRAM, TransitMode.TROLLEYBUS),
        // Line lists mirror the current DPB network as published by imhd.sk.
        // Trams and trolleybuses are complete; buses cover the core daytime
        // network (20–99). Outer 1xx lines, night (N) lines, school and
        // regional services are deliberately left out to keep the picker short
        // — any line can still be typed by hand.
        suggestedLines = mapOf(
            TransitMode.TRAM to listOf("1", "3", "4", "9"),
            TransitMode.TROLLEYBUS to listOf(
                "33", "40", "42", "44", "45", "47", "49", "60", "61", "64", "71", "72"
            ),
            TransitMode.BUS to listOf(
                "20", "21", "23", "25", "26", "27", "29", "30", "31", "32", "35",
                "36", "37", "39", "43", "50", "51", "52", "53", "56", "57", "63",
                "65", "66", "67", "68", "69", "70", "75", "77", "78", "79", "80",
                "83", "84", "87", "88", "90", "91", "92", "93", "94", "96", "98", "99"
            ),
        ),
        lineColors = BRATISLAVA_LINE_COLORS,
    )

    val cities: List<City> = listOf(bratislava)

    fun byId(id: String): City = cities.firstOrNull { it.id == id } ?: bratislava
}
