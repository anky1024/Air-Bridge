package com.airbridge.touch.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.airbridge.touch.core.TransferDirection
import com.airbridge.touch.core.TransferStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val fileName: String,
    val sizeBytes: Long,
    val peerName: String,
    val status: String,
    val bytesTransferred: Long,
    val sha256: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val failureReason: String?,
    val destinationUri: String?
) {
    fun toRecord(): com.airbridge.touch.core.TransferRecord = com.airbridge.touch.core.TransferRecord(
        id = id,
        direction = TransferDirection.valueOf(direction),
        fileName = fileName,
        sizeBytes = sizeBytes,
        peerName = peerName,
        status = TransferStatus.valueOf(status),
        bytesTransferred = bytesTransferred,
        sha256 = sha256,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        failureReason = failureReason,
        destinationUri = destinationUri
    )

    companion object {
        fun fromRecord(r: com.airbridge.touch.core.TransferRecord): TransferEntity = TransferEntity(
            id = r.id, direction = r.direction.name, fileName = r.fileName, sizeBytes = r.sizeBytes,
            peerName = r.peerName, status = r.status.name, bytesTransferred = r.bytesTransferred,
            sha256 = r.sha256, startedAtEpochMs = r.startedAtEpochMs,
            completedAtEpochMs = r.completedAtEpochMs, failureReason = r.failureReason,
            destinationUri = r.destinationUri
        )
    }
}

@Dao
interface TransferDao {
    @Insert suspend fun insert(e: TransferEntity)
    @Update suspend fun update(e: TransferEntity)
    @Query("SELECT * FROM transfers ORDER BY startedAtEpochMs DESC LIMIT 500")
    fun observeRecent(): Flow<List<TransferEntity>>
    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun byId(id: String): TransferEntity?
    @Query("DELETE FROM transfers") suspend fun clear()
}

@Database(entities = [TransferEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao
}

class HistoryRepository(context: Context) {
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "airbridge.db")
        .fallbackToDestructiveMigration()
        .build()
    private val dao = db.transferDao()

    fun observeRecent(): Flow<List<com.airbridge.touch.core.TransferRecord>> =
        kotlinx.coroutines.flow.map(dao.observeRecent()) { list -> list.map { it.toRecord() } }

    suspend fun insert(r: com.airbridge.touch.core.TransferRecord) = dao.insert(TransferEntity.fromRecord(r))
    suspend fun update(r: com.airbridge.touch.core.TransferRecord) = dao.update(TransferEntity.fromRecord(r))
    suspend fun clear() = dao.clear()
}
