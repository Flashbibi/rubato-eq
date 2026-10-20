package ch.sommermeyer.rubato.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ch.sommermeyer.rubato.BuildConfig
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.core.analysis.BandLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class ExportBand(val label: String, val cutoffHz: Double, val analysisLimitHz: Double)

@Serializable
data class ExportPayload(
    val app: String,
    val versionName: String,
    val versionCode: Int,
    val exportedAt: String,
    val bands: List<ExportBand>,
    val settings: RubatoSettings,
    val songs: List<SongEntity>,
    val trainingExamples: List<TrainingExampleEntity>,
)

/** Writes the whole database as JSON and offers it through the share sheet. */
class DataExporter(
    private val context: Context,
    private val database: RubatoDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun export(): Uri = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val payload = ExportPayload(
            app = "rubato-eq",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            exportedAt = now.toString(),
            bands = BandLayout.DEFAULT.map { ExportBand(it.label, it.cutoffHz, it.analysisLimitHz) },
            settings = settings.settings.first(),
            songs = database.songs().all(),
            trainingExamples = database.trainingExamples().all(),
        )
        val directory = File(context.cacheDir, EXPORT_DIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(now)
        val file = File(directory, "rubato-export-$stamp.json")
        file.writeText(json.encodeToString(ExportPayload.serializer(), payload))
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, context.getString(R.string.export_chooser_title))
    }

    private companion object {
        const val EXPORT_DIR = "exports"
    }
}
