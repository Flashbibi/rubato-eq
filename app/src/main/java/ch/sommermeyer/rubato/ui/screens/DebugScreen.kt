package ch.sommermeyer.rubato.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.audio.EqEffectController
import ch.sommermeyer.rubato.audio.PlayerSession
import ch.sommermeyer.rubato.audio.VisualizerCapture
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.data.EqMode
import ch.sommermeyer.rubato.data.RubatoSettings
import ch.sommermeyer.rubato.engine.EngineState
import ch.sommermeyer.rubato.engine.EventLog
import ch.sommermeyer.rubato.engine.RubatoEngine
import ch.sommermeyer.rubato.ui.components.SectionCard
import ch.sommermeyer.rubato.ui.components.StatusRow
import java.text.DateFormat
import java.util.Date

@Composable
fun DebugScreen(
    engine: EngineState,
    settings: RubatoSettings,
    sessions: Map<Int, PlayerSession>,
    log: List<EventLog.Entry>,
    onEqMode: (EqMode) -> Unit,
    onCompensation: (EqMode, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.debug_mode_title)) {
            EqMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.eqMode == mode, onClick = { onEqMode(mode) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.eqMode == mode, onClick = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(RubatoEngine.modeLabel(mode)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (mode == EqMode.GLOBAL) R.string.debug_mode_global_hint else R.string.debug_mode_session_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.debug_compensation_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.debug_compensation_label, stringResource(RubatoEngine.modeLabel(settings.eqMode))),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(stringResource(R.string.debug_compensation_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.compensation,
                    onCheckedChange = { onCompensation(settings.eqMode, it) },
                )
            }
        }

        VisualizerCard(engine.visualizer)
        EffectCard(engine, sessions)

        SectionCard(title = stringResource(R.string.debug_gains_title)) {
            BandLayout.DEFAULT.forEachIndexed { i, band ->
                StatusRow(
                    label = band.label,
                    value = stringResource(
                        R.string.debug_gain_row,
                        engine.appliedGainsDb.getOrElse(i) { 0.0 },
                        engine.song?.autoCorrectionDb?.getOrNull(i) ?: 0.0,
                        engine.liveSpectrumDb?.getOrNull(i) ?: 0.0,
                    ),
                )
            }
        }

        SectionCard(title = stringResource(R.string.debug_log_title)) {
            if (log.isEmpty()) Text(stringResource(R.string.debug_log_empty), style = MaterialTheme.typography.bodySmall)
            val locale = LocalConfiguration.current.locales[0]
            val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.MEDIUM, locale) }
            log.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                val color = when (entry.level) {
                    EventLog.Level.INFO -> Color.Unspecified
                    EventLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                    EventLog.Level.ERROR -> MaterialTheme.colorScheme.error
                }
                Text(
                    "${timeFormat.format(Date(entry.timeMillis))} [${entry.tag}] ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun VisualizerCard(status: VisualizerCapture.Status) {
    SectionCard(title = stringResource(R.string.debug_visualizer_title)) {
        val state = when (status.state) {
            VisualizerCapture.State.OFF -> R.string.debug_visualizer_off
            VisualizerCapture.State.RUNNING -> R.string.debug_visualizer_running
            VisualizerCapture.State.ERROR -> R.string.debug_visualizer_error
        }
        StatusRow(stringResource(R.string.debug_state), stringResource(state))
        StatusRow(stringResource(R.string.debug_fps), stringResource(R.string.format_fps, status.framesPerSecond, status.captureRateHz))
        StatusRow(stringResource(R.string.debug_capture), stringResource(R.string.format_capture, status.captureSize, status.samplingRateHz))
        StatusRow(
            stringResource(R.string.debug_measurement),
            stringResource(if (status.measurementAvailable) R.string.yes else R.string.no),
        )
        StatusRow(stringResource(R.string.debug_restarts), status.restarts.toString())
        status.lastError?.let { StatusRow(stringResource(R.string.debug_last_error), it) }
    }
}

@Composable
private fun EffectCard(engine: EngineState, sessions: Map<Int, PlayerSession>) {
    val effect = engine.effect
    SectionCard(title = stringResource(R.string.debug_effect_title)) {
        StatusRow(
            stringResource(R.string.debug_mode),
            effect.mode?.let { stringResource(RubatoEngine.modeLabel(it)) } ?: stringResource(R.string.debug_effect_none),
        )
        StatusRow(stringResource(R.string.debug_bypassed), stringResource(if (effect.bypassed) R.string.yes else R.string.no))
        if (effect.attachments.isEmpty()) {
            Text(stringResource(R.string.debug_effect_no_attachments), style = MaterialTheme.typography.bodySmall)
        }
        effect.attachments.forEach { a ->
            val state = when (a.state) {
                EqEffectController.AttachmentState.ACTIVE -> R.string.debug_attachment_active
                EqEffectController.AttachmentState.DISABLED -> R.string.debug_attachment_disabled
                EqEffectController.AttachmentState.NO_CONTROL -> R.string.debug_attachment_no_control
                EqEffectController.AttachmentState.ERROR -> R.string.debug_attachment_error
            }
            val name = if (a.sessionId == EqEffectController.GLOBAL_SESSION) {
                stringResource(R.string.debug_session_global)
            } else {
                stringResource(R.string.debug_session_player, a.sessionId, a.packageName ?: stringResource(R.string.unknown_app))
            }
            StatusRow(name, listOfNotNull(stringResource(state), a.detail).joinToString(" – "))
        }
        effect.lastError?.let { StatusRow(stringResource(R.string.debug_last_error), it) }
        HorizontalDivider()
        Text(stringResource(R.string.debug_sessions_title), style = MaterialTheme.typography.labelLarge)
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.debug_sessions_empty), style = MaterialTheme.typography.bodySmall)
        }
        sessions.values.sortedBy { it.openedAtMillis }.forEach { s ->
            StatusRow("#${s.sessionId}", s.packageName ?: stringResource(R.string.unknown_app))
        }
    }
}
