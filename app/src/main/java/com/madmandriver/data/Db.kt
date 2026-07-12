package com.madmandriver.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cityId: String,
    val mode: String,
    val line: String,
    val vehicleNumber: String?,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("rideId")],
)
data class HarshEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val occurredAtUtcMs: Long,
    val lat: Double?,
    val lon: Double?,
    val gpsAccuracyM: Float?,
    val peakMps2: Float,
    val meanMps2: Float,
    val durationMs: Long,
    /** BRAKING / ACCELERATION / OTHER (no usable GPS speed delta) */
    val kind: String,
    /** MODERATE / STRONG / EXTREME */
    val severity: String,
    val speedBeforeKmh: Float?,
    val speedAfterKmh: Float?,
)

/**
 * Downsampled (~5 Hz) smoothed horizontal acceleration, recorded for the whole
 * ride so the full curve can be charted and exported, not just the events.
 */
@Entity(
    tableName = "trace_samples",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("rideId")],
)
data class TraceSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val tMs: Long,
    val accelMps2: Float,
)

data class RideWithCount(
    @Embedded val ride: RideEntity,
    val eventCount: Int,
)

@Dao
interface RideDao {
    @Insert
    suspend fun insertRide(ride: RideEntity): Long

    @Query("UPDATE rides SET endedAtUtcMs = :endedAtUtcMs WHERE id = :rideId")
    suspend fun finishRide(rideId: Long, endedAtUtcMs: Long)

    @Insert
    suspend fun insertEvent(event: HarshEventEntity): Long

    @Query(
        "SELECT r.*, (SELECT COUNT(*) FROM events e WHERE e.rideId = r.id) AS eventCount " +
            "FROM rides r ORDER BY r.startedAtUtcMs DESC"
    )
    fun ridesWithCounts(): Flow<List<RideWithCount>>

    @Query("SELECT * FROM rides WHERE id = :rideId")
    fun rideFlow(rideId: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun ride(rideId: Long): RideEntity?

    @Query("SELECT * FROM events WHERE rideId = :rideId ORDER BY occurredAtUtcMs ASC")
    fun eventsFlow(rideId: Long): Flow<List<HarshEventEntity>>

    @Query("SELECT * FROM events WHERE rideId = :rideId ORDER BY occurredAtUtcMs ASC")
    suspend fun eventsForRide(rideId: Long): List<HarshEventEntity>

    @Query("DELETE FROM rides WHERE id = :rideId")
    suspend fun deleteRide(rideId: Long)

    @Insert
    suspend fun insertTraceSamples(samples: List<TraceSampleEntity>)

    @Query("SELECT * FROM trace_samples WHERE rideId = :rideId ORDER BY tMs ASC")
    fun traceFlow(rideId: Long): Flow<List<TraceSampleEntity>>

    @Query("SELECT * FROM trace_samples WHERE rideId = :rideId ORDER BY tMs ASC")
    suspend fun traceForRide(rideId: Long): List<TraceSampleEntity>
}

@Database(
    entities = [RideEntity::class, HarshEventEntity::class, TraceSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trace_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`rideId` INTEGER NOT NULL, " +
                        "`tMs` INTEGER NOT NULL, " +
                        "`accelMps2` REAL NOT NULL, " +
                        "FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trace_samples_rideId` ON `trace_samples` (`rideId`)"
                )
            }
        }
    }
}
