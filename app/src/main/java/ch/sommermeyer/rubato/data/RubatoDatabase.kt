package ch.sommermeyer.rubato.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE songKey = :key LIMIT 1")
    suspend fun find(key: String): SongEntity?

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Query("SELECT * FROM songs WHERE inLibrary = 1")
    suspend fun librarySongs(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY lastPlayedAt DESC")
    suspend fun all(): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    fun count(): Flow<Int>

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}

@Dao
interface TrainingExampleDao {
    @Insert
    suspend fun insert(example: TrainingExampleEntity): Long

    @Query("SELECT * FROM training_examples ORDER BY createdAt")
    suspend fun all(): List<TrainingExampleEntity>

    @Query("SELECT COUNT(*) FROM training_examples")
    fun count(): Flow<Int>

    @Query("DELETE FROM training_examples")
    suspend fun deleteAll()
}

class Converters {
    private val serializer = ListSerializer(Double.serializer())

    @TypeConverter
    fun fromDoubles(values: List<Double>): String = Json.encodeToString(serializer, values)

    @TypeConverter
    fun toDoubles(text: String): List<Double> = Json.decodeFromString(serializer, text)
}

@Database(
    entities = [SongEntity::class, TrainingExampleEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RubatoDatabase : RoomDatabase() {
    abstract fun songs(): SongDao

    abstract fun trainingExamples(): TrainingExampleDao

    companion object {
        fun build(context: Context): RubatoDatabase =
            Room.databaseBuilder(context, RubatoDatabase::class.java, "rubato.db").build()
    }
}
