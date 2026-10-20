package ch.sommermeyer.rubato.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.ui.PermissionSnapshot
import ch.sommermeyer.rubato.ui.Permissions

/** Collects all permissions step by step and shows their status. */
@Composable
fun OnboardingScreen(
    permissions: PermissionSnapshot,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    var audioDenied by rememberSaveable { mutableStateOf(false) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioDenied = !granted
        onRefresh()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRefresh()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium)

        StepCard(
            number = 1,
            title = stringResource(R.string.onboarding_listener_title),
            text = stringResource(R.string.onboarding_listener_text),
            done = permissions.notificationListener,
        ) {
            Button(onClick = { Permissions.openNotificationListenerSettings(context) }) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
            Text(stringResource(R.string.onboarding_listener_restricted), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { Permissions.openAppDetails(context) }) {
                Text(stringResource(R.string.onboarding_open_app_info))
            }
        }

        StepCard(
            number = 2,
            title = stringResource(R.string.onboarding_audio_title),
            text = stringResource(R.string.onboarding_audio_text),
            done = permissions.recordAudio,
        ) {
            Button(onClick = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(stringResource(R.string.onboarding_allow))
            }
            if (audioDenied) {
                Text(stringResource(R.string.onboarding_denied_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { Permissions.openAppDetails(context) }) {
                    Text(stringResource(R.string.onboarding_open_app_info))
                }
            }
        }

        StepCard(
            number = 3,
            title = stringResource(R.string.onboarding_notifications_title),
            text = stringResource(R.string.onboarding_notifications_text),
            done = permissions.postNotifications,
        ) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Permissions.openAppDetails(context)
                }
            }) { Text(stringResource(R.string.onboarding_allow)) }
        }

        StepCard(
            number = 4,
            title = stringResource(R.string.onboarding_battery_title),
            text = stringResource(R.string.onboarding_battery_text),
            done = permissions.batteryUnrestricted,
        ) {
            Button(onClick = { Permissions.requestIgnoreBatteryOptimizations(context) }) {
                Text(stringResource(R.string.onboarding_battery_action))
            }
        }

        StepCard(
            number = 5,
            title = stringResource(R.string.onboarding_install_title),
            text = stringResource(R.string.onboarding_install_text),
            done = permissions.installPackages,
        ) {
            Button(onClick = { Permissions.openUnknownSources(context) }) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        }

        HintCard(
            title = stringResource(R.string.onboarding_auto_blocker_title),
            text = stringResource(R.string.onboarding_auto_blocker_text),
        ) {
            OutlinedButton(onClick = { Permissions.openSecuritySettings(context) }) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        }

        HintCard(
            title = stringResource(R.string.onboarding_never_sleeping_title),
            text = stringResource(R.string.onboarding_never_sleeping_text),
        ) {
            OutlinedButton(onClick = { Permissions.openBatterySettings(context) }) {
                Text(stringResource(R.string.onboarding_open_battery))
            }
        }

        if (!permissions.readyForService) {
            Text(
                stringResource(R.string.onboarding_required_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_done))
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    text: String,
    done: Boolean,
    actions: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = stringResource(if (done) R.string.onboarding_done_cd else R.string.onboarding_open_cd),
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.onboarding_step, number, title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (done) {
                Text(stringResource(R.string.onboarding_granted), color = MaterialTheme.colorScheme.primary)
            } else {
                actions()
            }
        }
    }
}

@Composable
private fun HintCard(title: String, text: String, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
