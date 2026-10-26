package com.voiceclean.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawText: String,
    val cleanedText: String,
    val style: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE rawText LIKE '%' || :q || '%' OR cleanedText LIKE '%' || :q || '%' ORDER BY createdAt DESC")
    fun search(q: String): Flow<List<TranscriptionEntity>>

    @Insert suspend fun insert(e: TranscriptionEntity): Long
    @Update suspend fun update(e: TranscriptionEntity)
    @Delete suspend fun delete(e: TranscriptionEntity)
    @Query("DELETE FROM transcriptions") suspend fun clear()
}

@Database(entities = [TranscriptionEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
}

class HistoryRepository(private val dao: TranscriptionDao) {
    fun observe(query: String = ""): Flow<List<TranscriptionEntity>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query.trim())
    suspend fun add(raw: String, cleaned: String, style: String): Long =
        dao.insert(TranscriptionEntity(rawText = raw, cleanedText = cleaned, style = style))
    suspend fun update(e: TranscriptionEntity) = dao.update(e)
    suspend fun delete(e: TranscriptionEntity) = dao.delete(e)
    suspend fun clear() = dao.clear()
}
