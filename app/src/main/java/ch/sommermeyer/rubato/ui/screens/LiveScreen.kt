package ch.sommermeyer.rubato.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.core.correction.CorrectionMath
import ch.sommermeyer.rubato.core.correction.LibraryTarget
import ch.sommermeyer.rubato.core.correction.ToneOffset
import ch.sommermeyer.rubato.data.RubatoSettings
import ch.sommermeyer.rubato.engine.EngineState
import ch.sommermeyer.rubato.engine.RubatoEngine
import ch.sommermeyer.rubato.nowplaying.PlaybackStatus
import ch.sommermeyer.rubato.ui.PermissionSnapshot
import ch.sommermeyer.rubato.ui.components.CorrectionChart
import ch.sommermeyer.rubato.ui.components.SectionCard
import ch.sommermeyer.rubato.ui.components.SpectrumChart
import ch.sommermeyer.rubato.ui.components.ValueSlider
import kotlin.math.roundToInt

@Composable
fun LiveScreen(
    engine: EngineState,
    settings: RubatoSettings,
    permissions: PermissionSnapshot,
    onServiceRunning: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onStrength: (Float) -> Unit,
    onMaxCorrection: (Float) -> Unit,
    onManualOffset: (ToneOffset) -> Unit,
    onCommitManualOffset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = BandLayout.DEFAULT.map { it.label }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PowerCard(engine, onServiceRunning, onTogglePause)

        if (!permissions.readyForService || (engine.running && !engine.notificationAccess)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(stringResource(R.string.live_permissions_missing), modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenOnboarding) { Text(stringResource(R.string.live_permissions_fix)) }
                }
            }
        }

        NowPlayingCard(engine)

        SectionCard(title = stringResource(R.string.live_spectrum_title)) {
            SpectrumChart(
                labels = labels,
                live = engine.liveSpectrumDb,
                target = engine.targetDb,
                songAverage = engine.song?.spectrumDb,
                liveLabel = stringResource(if (engine.compensation) R.string.legend_live_compensated else R.string.legend_live),
                targetLabel = stringResource(R.string.legend_target),
                songLabel = stringResource(R.string.legend_song),
            )
            if (engine.targetDb == null) {
                Text(stringResource(R.string.live_no_target), style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard(title = stringResource(R.string.live_correction_title)) {
            val resources = LocalResources.current
            CorrectionChart(
                labels = labels,
                gainsDb = engine.appliedGainsDb,
                valueLabels = engine.appliedGainsDb.map { resources.getString(R.string.format_db_short, it) },
                limitDb = settings.maxCorrectionDb.toDouble(),
            )
        }

        SectionCard(title = stringResource(R.string.live_auto_title)) {
            ValueSlider(
                label = stringResource(R.string.live_strength),
                value = settings.strength,
                range = 0f..1f,
                valueText = { stringResource(R.string.format_percent, (it * 100).roundToInt()) },
                onCommit = onStrength,
            )
            ValueSlider(
                label = stringResource(R.string.live_max_correction),
                value = settings.maxCorrectionDb,
                range = 1f..CorrectionMath.MAX_ALLOWED_DB.toFloat(),
                steps = 9,
                valueText = { stringResource(R.string.format_db_limit, it) },
                onCommit = onMaxCorrection,
            )
            Text(
                stringResource(
                    R.string.live_effective_strength,
                    (engine.effectiveStrength * 100).roundToInt(),
                    engine.librarySongs.coerceAtMost(LibraryTarget.FULL_CONFIDENCE_SONGS),
                    LibraryTarget.FULL_CONFIDENCE_SONGS,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SongSlidersCard(engine, onManualOffset, onCommitManualOffset)
    }
}

@Composable
private fun PowerCard(engine: EngineState, onServiceRunning: (Boolean) -> Unit, onTogglePause: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_power_title), style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        !engine.running -> R.string.live_power_off
                        engine.paused -> R.string.live_power_paused
                        engine.effect.active -> R.string.live_power_active
                        else -> R.string.live_power_no_effect
                    }
                    Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = engine.running, onCheckedChange = onServiceRunning)
            }
            if (engine.running) {
                OutlinedButton(onClick = onTogglePause) {
                    Text(stringResource(if (engine.paused) R.string.action_resume else R.string.action_pause))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(engine: EngineState) {
    SectionCard(title = stringResource(R.string.live_now_playing_title)) {
        val np = engine.nowPlaying
        if (np == null) {
            Text(
                stringResource(if (engine.running) R.string.live_nothing_playing else R.string.live_service_off_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Text(np.title ?: stringResource(R.string.unknown_title), style = MaterialTheme.typography.titleLarge)
        Text(np.artist ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodyLarge)
        val status = when (np.status) {
            PlaybackStatus.PLAYING -> R.string.status_playing
            PlaybackStatus.BUFFERING -> R.string.status_buffering
            PlaybackStatus.PAUSED -> R.string.status_paused
            PlaybackStatus.STOPPED -> R.string.status_stopped
        }
        Text(
            stringResource(R.string.live_source, np.appLabel, stringResource(status)),
            style = MaterialTheme.typography.bodySmall,
        )
        val song = engine.song
        val detail = when {
            song == null -> stringResource(R.string.live_song_pending)
            song.known -> stringResource(R.string.live_song_known, song.totalAnalyzedMs / 1000)
            song.hasCorrection -> stringResource(R.string.live_song_corrected, song.sessionAnalyzedMs / 1000)
            else -> stringResource(R.string.live_song_analysing, song.sessionAnalyzedMs / 1000)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SongSlidersCard(
    engine: EngineState,
    onManualOffset: (ToneOffset) -> Unit,
    onCommitManualOffset: () -> Unit,
) {
    val song = engine.song
    SectionCard(title = stringResource(R.string.live_song_sliders_title)) {
        if (song == null || !song.loaded) {
            Text(stringResource(R.string.live_song_sliders_disabled), style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        // Local copy so the three sliders stay consistent while one of them is dragged.
        var offset by remember(song.key) { mutableStateOf(song.manual) }
        val range = RubatoEngine.MANUAL_RANGE_DB.toFloat()
        val format: @Composable (Float) -> String = { stringResource(R.string.format_db_short, it.toDouble()) }
        ValueSlider(
            label = stringResource(R.string.tone_bass),
            value = song.manual.bassDb.toFloat(),
            range = -range..range,
            steps = 23,
            valueText = format,
            onChange = { offset = offset.copy(bassDb = it.toDouble()); onManualOffset(offset) },
            onCommit = { onCommitManualOffset() },
        )
        ValueSlider(
            label = stringResource(R.string.tone_mid),
            value = song.manual.midDb.toFloat(),
            range = -range..range,
            steps = 23,
            valueText = format,
            onChange = { offset = offset.copy(midDb = it.toDouble()); onManualOffset(offset) },
            onCommit = { onCommitManualOffset() },
        )
        ValueSlider(
            label = stringResource(R.string.tone_treble),
            value = song.manual.trebleDb.toFloat(),
            range = -range..range,
            steps = 23,
            valueText = format,
            onChange = { offset = offset.copy(trebleDb = it.toDouble()); onManualOffset(offset) },
            onCommit = { onCommitManualOffset() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.live_song_sliders_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    offset = ToneOffset.NEUTRAL
                    onManualOffset(offset)
                    onCommitManualOffset()
                },
                enabled = !song.manual.isNeutral,
            ) { Text(stringResource(R.string.action_reset)) }
        }
    }
}
