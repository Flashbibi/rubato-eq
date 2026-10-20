package ch.sommermeyer.rubato.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.data.RubatoSettings
import ch.sommermeyer.rubato.ui.components.SectionCard
import ch.sommermeyer.rubato.ui.components.ValueSlider

@Composable
fun SettingsScreen(
    settings: RubatoSettings,
    songCount: Int,
    trainingCount: Int,
    librarySongs: Int,
    onTasteBass: (Float) -> Unit,
    onTasteTreble: (Float) -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val range = RubatoSettings.TASTE_RANGE_DB
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_taste_title)) {
            Text(stringResource(R.string.settings_taste_hint), style = MaterialTheme.typography.bodySmall)
            ValueSlider(
                label = stringResource(R.string.tone_bass),
                value = settings.tasteBassDb,
                range = -range..range,
                steps = 23,
                valueText = { stringResource(R.string.format_db_short, it.toDouble()) },
                onCommit = onTasteBass,
            )
            ValueSlider(
                label = stringResource(R.string.tone_treble),
                value = settings.tasteTrebleDb,
                range = -range..range,
                steps = 23,
                valueText = { stringResource(R.string.format_db_short, it.toDouble()) },
                onCommit = onTasteTreble,
            )
        }

        SectionCard(title = stringResource(R.string.settings_data_title)) {
            Text(
                stringResource(R.string.settings_data_counts, songCount, librarySongs, trainingCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text(stringResource(R.string.settings_export), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { confirmReset = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.settings_reset), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionCard(title = stringResource(R.string.settings_permissions_title)) {
            Text(stringResource(R.string.settings_permissions_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenOnboarding) { Text(stringResource(R.string.settings_permissions_open)) }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.settings_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
