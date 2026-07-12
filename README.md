# Madman Driver

Android app for public-transport passengers who experience aggressive acceleration and
harsh braking on board. It measures the forces with the phone's accelerometer, stamps each
event with **GPS position, speed, date/time, line number, vehicle type and (optionally) the
fleet number**, stores everything locally, and generates a ready-to-send complaint —
CSV attachment plus a bilingual e-mail — so the transport operator can identify who was
driving that service.

**First release is tailored to Bratislava (Dopravný podnik Bratislava — bus, tram,
trolleybus).** The city model is fully pluggable for future cities and countries.

## How it works

1. **Start a ride** — pick bus / tram / trolleybus, enter the line number (suggestions for
   DPB lines are offered) and optionally the vehicle fleet number printed on the vehicle.
2. A **foreground service** samples the accelerometer at ~50 Hz and GPS at 1 Hz. It keeps
   working with the screen off (partial wake lock).
3. **Detection** is orientation-independent: linear acceleration is projected onto the
   horizontal plane using the live gravity vector, so pocket/bag/hand positions all work and
   vertical noise (potholes, cobblestones) is rejected. The signal is smoothed over a 400 ms
   window and run through a hysteresis trigger; pulses shorter than 350 ms are discarded.
4. Each event records local + UTC time, GPS lat/lon & accuracy, peak and mean acceleration,
   duration, and GPS speed before/after — the speed delta classifies the event as
   **braking**, **acceleration**, or other manoeuvre. Severity tiers: moderate (≥ trigger),
   strong (≥ 3.0 m/s²), extreme (≥ 4.5 m/s²).
5. From ride history you can **share a CSV** or **send a prefilled report e-mail**
   (Slovak + English) to the operator, with per-event Google Maps links.

### Thresholds

Comfort research puts ~1.5–2 m/s² as the point where standing passengers must brace, and
~3 m/s² as a stumble/fall risk. The default trigger is 2.0 m/s², adjustable in Settings
(Relaxed 2.5 / Normal 2.0 / Sensitive 1.6).

## Extending to other cities

Add an entry to `CityCatalog` (`app/src/main/java/com/madmandriver/city/CityCatalog.kt`):
name, country, time zone, operator name + complaint e-mail, available modes and suggested
line numbers. Everything else — recording, storage, CSV, report template — is keyed off the
`City` model. Longer-term the catalog can be loaded from a remote registry or GTFS feeds.

## Build

Requires JDK 17 and the Android SDK (API 35).

```
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew test                 # detector unit tests
./gradlew installDebug         # install on a connected device
```

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · foreground service with
`SensorManager` + `LocationManager` · no third-party runtime dependencies.

## Privacy & fair use

All measurements stay on the device until you explicitly share them. Smartphone sensors are
not calibrated instruments — the data is indicative evidence to help an operator check its
own telemetry/duty rosters, not a legal measurement. The app records no audio, no imagery,
and nothing about other passengers.

## Roadmap

- Lateral vs. longitudinal split using GPS bearing (cornering detection)
- Line validation / auto-suggestion from GTFS feeds per city
- Map view of a ride with event pins
- Optional anonymous aggregation server for city-wide "driving quality" statistics
- Localized UI (Slovak first)
