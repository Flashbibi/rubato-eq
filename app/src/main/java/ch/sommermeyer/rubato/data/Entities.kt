package ch.sommermeyer.rubato.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One song as identified by artist + title + duration. Band lists follow BandLayout.DEFAULT;
 * an empty list means "not available".
 */
@Serializable
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val songKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val sourcePackage: String,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
    val playCount: Int,
    /** Analysed (non-silent) audio in ms over all plays. */
    val analyzedMs: Long,
    val frameCount: Long,
    /** Linear mean power per band; kept to continue the average on the next play. */
    val bandPowerMean: List<Double>,
    /** Normalised spectrum in dB (tonal balance). */
    val spectrumDb: List<Double>,
    /** Last automatic correction in dB. */
    val autoCorrectionDb: List<Double>,
    /** Last correction that was actually applied (automatic + manual) in dB. */
    val appliedCorrectionDb: List<Double>,
    val manualBassDb: Double,
    val manualMidDb: Double,
    val manualTrebleDb: Double,
    /** Whether the song contributes to the library target curve. */
    val inLibrary: Boolean,
    /** Whether our own EQ was subtracted from the measurement. */
    val compensated: Boolean,
    val eqMode: String,
)

/** A manual correction of the user together with the song's features: training data for later. */
@Serializable
@Entity(tableName = "training_examples", indices = [Index("songKey")])
data class TrainingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val songKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sourcePackage: String,
    val analyzedMs: Long,
    val spectrumDb: List<Double>,
    val targetDb: List<Double>,
    val autoCorrectionDb: List<Double>,
    val manualBassDb: Double,
    val manualMidDb: Double,
    val manualTrebleDb: Double,
    val strength: Double,
    val maxCorrectionDb: Double,
    val tasteBassDb: Double,
    val tasteTrebleDb: Double,
    val librarySongs: Int,
)
