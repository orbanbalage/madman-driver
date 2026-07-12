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
        suggestedLines = mapOf(
            TransitMode.TRAM to listOf("1", "2", "3", "4", "7", "9"),
            TransitMode.TROLLEYBUS to listOf(
                "40", "42", "44", "45", "47", "48", "49", "60", "64", "71", "72"
            ),
            // Buses change often; this is a convenience subset, any number can be typed.
            TransitMode.BUS to listOf(
                "21", "25", "28", "29", "31", "32", "37", "39", "50", "61", "63",
                "65", "66", "68", "70", "74", "75", "78", "83", "84", "87", "88",
                "90", "91", "93", "94", "95", "96", "98", "99"
            ),
        ),
    )

    val cities: List<City> = listOf(bratislava)

    fun byId(id: String): City = cities.firstOrNull { it.id == id } ?: bratislava
}
