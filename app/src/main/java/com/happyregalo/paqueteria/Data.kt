package com.happyregalo.paqueteria

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "packages")
data class PackageEntity(
    @PrimaryKey val shipmentId: String,
    val size: Int,
    val location: String,
    val enteredAt: Long = System.currentTimeMillis(),
    val delivered: Boolean = false,
    val deliveredAt: Long? = null
)

@Entity(tableName = "slot_config")
data class SlotConfig(
    @PrimaryKey val size: Int,
    val prefix: String,
    val count: Int
)

@Dao
interface PackageDao {
    @Query("SELECT * FROM packages WHERE delivered=0 ORDER BY location")
    fun active(): Flow<List<PackageEntity>>

    @Query("SELECT * FROM packages WHERE delivered=0")
    suspend fun activeNow(): List<PackageEntity>

    @Query("SELECT * FROM packages WHERE delivered=0 AND shipmentId=:id LIMIT 1")
    suspend fun byId(id: String): PackageEntity?

    @Query("SELECT * FROM packages WHERE delivered=0 AND shipmentId LIKE '%' || :suffix")
    suspend fun bySuffix(suffix: String): List<PackageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(p: PackageEntity)

    @Query("UPDATE packages SET delivered=1, deliveredAt=:now WHERE shipmentId=:id")
    suspend fun deliver(id: String, now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE packages
        SET location = :newLocation
        WHERE shipmentId = :shipmentId
          AND delivered = 0
          AND NOT EXISTS (
              SELECT 1
              FROM packages AS occupied
              WHERE occupied.location = :newLocation
                AND occupied.delivered = 0
                AND occupied.shipmentId != :shipmentId
          )
        """
    )
    suspend fun moveIfFree(shipmentId: String, newLocation: String): Int
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM slot_config ORDER BY size")
    suspend fun all(): List<SlotConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(c: SlotConfig)
}

@Database(entities = [PackageEntity::class, SlotConfig::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun packages(): PackageDao
    abstract fun config(): ConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context, AppDb::class.java, "paqueteria.db")
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
