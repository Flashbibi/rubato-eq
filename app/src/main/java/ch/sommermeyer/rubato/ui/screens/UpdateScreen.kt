package ch.sommermeyer.rubato.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.sommermeyer.rubato.BuildConfig
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.core.update.GitHubRelease
import ch.sommermeyer.rubato.ui.components.SectionCard
import ch.sommermeyer.rubato.ui.components.StatusRow
import ch.sommermeyer.rubato.update.UpdateManager
import kotlin.math.roundToInt

@Composable
fun UpdateScreen(
    state: UpdateManager.State,
    latestRelease: GitHubRelease?,
    updaterEnabled: Boolean,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onConfirm: (UpdateManager.State.WaitingForUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.update_version_title)) {
            StatusRow(stringResource(R.string.update_version_name), BuildConfig.VERSION_NAME)
            StatusRow(stringResource(R.string.update_version_code), BuildConfig.VERSION_CODE.toString())
            StatusRow(stringResource(R.string.update_build_type), BuildConfig.BUILD_TYPE)
        }

        SectionCard(title = stringResource(R.string.update_title)) {
            if (!updaterEnabled) {
                Text(stringResource(R.string.update_disabled_debug), style = MaterialTheme.typography.bodyMedium)
                return@SectionCard
            }
            UpdateStatus(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = state is UpdateManager.State.Checking ||
                    state is UpdateManager.State.Downloading ||
                    state is UpdateManager.State.Installing
                OutlinedButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.update_check)) }
                when (state) {
                    is UpdateManager.State.Available -> Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                    is UpdateManager.State.WaitingForUser -> Button(onClick = { onConfirm(state) }) {
                        Text(stringResource(R.string.update_confirm))
                    }
                    else -> Unit
                }
            }
        }

        SectionCard(title = stringResource(R.string.update_changelog_title)) {
            if (latestRelease == null) {
                Text(stringResource(R.string.update_changelog_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(latestRelease.name ?: latestRelease.tagName, style = MaterialTheme.typography.titleSmall)
                Text(
                    latestRelease.body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.update_changelog_no_notes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateManager.State) {
    when (state) {
        UpdateManager.State.Idle -> Text(stringResource(R.string.update_state_idle), style = MaterialTheme.typography.bodyMedium)
        UpdateManager.State.Checking -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.update_state_checking))
        }
        is UpdateManager.State.UpToDate -> Text(stringResource(R.string.update_state_up_to_date, state.release.tagName))
        is UpdateManager.State.Available -> Text(
            stringResource(R.string.update_state_available, state.release.tagName, state.asset.size / 1_048_576.0),
            color = MaterialTheme.colorScheme.primary,
        )
        is UpdateManager.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val progress = state.progress
            if (progress == null) {
                Text(stringResource(R.string.update_state_downloading_unknown))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.update_state_downloading, (progress * 100).roundToInt()))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is UpdateManager.State.Installing -> Text(stringResource(R.string.update_state_installing, state.release.tagName))
        is UpdateManager.State.WaitingForUser -> Text(stringResource(R.string.update_state_waiting))
        is UpdateManager.State.Failed -> Text(
            stringResource(R.string.update_state_failed, state.message),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
