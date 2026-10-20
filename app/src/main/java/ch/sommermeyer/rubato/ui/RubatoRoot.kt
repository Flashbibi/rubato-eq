package ch.sommermeyer.rubato.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.ui.screens.DebugScreen
import ch.sommermeyer.rubato.ui.screens.LiveScreen
import ch.sommermeyer.rubato.ui.screens.OnboardingScreen
import ch.sommermeyer.rubato.ui.screens.SettingsScreen
import ch.sommermeyer.rubato.ui.screens.UpdateScreen
import ch.sommermeyer.rubato.update.UpdateManager

private enum class Tab(val label: Int, val icon: ImageVector) {
    LIVE(R.string.tab_live, Icons.Filled.Home),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
    UPDATE(R.string.tab_update, Icons.Filled.Refresh),
    DEBUG(R.string.tab_debug, Icons.Filled.Build),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RubatoRoot(viewModel: RubatoViewModel) {
    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: return
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val latestRelease by viewModel.latestRelease.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val songCount by viewModel.songCount.collectAsStateWithLifecycle()
    val trainingCount by viewModel.trainingCount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val noAppMessage = stringResource(R.string.error_no_app)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(noAppMessage)
                }
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val onboardingVisible = !settings.onboardingDone || showOnboarding
    if (onboardingVisible && settings.onboardingDone) BackHandler { viewModel.finishOnboarding() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (onboardingVisible) R.string.onboarding_app_bar else R.string.app_name))
                },
            )
        },
        bottomBar = {
            if (!onboardingVisible) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                if (entry == Tab.UPDATE && update is UpdateManager.State.Available) {
                                    BadgedBox(badge = { Badge() }) { Icon(entry.icon, contentDescription = null) }
                                } else {
                                    Icon(entry.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(entry.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (onboardingVisible) {
                OnboardingScreen(
                    permissions = permissions,
                    onRefresh = viewModel::refreshPermissions,
                    onDone = viewModel::finishOnboarding,
                )
                return@Box
            }
            when (tab) {
                Tab.LIVE -> LiveScreen(
                    engine = engine,
                    settings = settings,
                    permissions = permissions,
                    onServiceRunning = viewModel::setServiceRunning,
                    onTogglePause = viewModel::togglePause,
                    onOpenOnboarding = viewModel::openOnboarding,
                    onStrength = { viewModel.setStrength(it) },
                    onMaxCorrection = { viewModel.setMaxCorrection(it) },
                    onManualOffset = viewModel::setManualOffset,
                    onCommitManualOffset = viewModel::commitManualOffset,
                )
                Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    songCount = songCount,
                    trainingCount = trainingCount,
                    librarySongs = engine.librarySongs,
                    onTasteBass = { viewModel.setTasteBass(it) },
                    onTasteTreble = { viewModel.setTasteTreble(it) },
                    onExport = { viewModel.exportData() },
                    onReset = viewModel::resetAnalysisData,
                    onOpenOnboarding = viewModel::openOnboarding,
                )
                Tab.UPDATE -> UpdateScreen(
                    state = update,
                    latestRelease = latestRelease,
                    updaterEnabled = viewModel.updaterEnabled,
                    onCheck = { viewModel.checkForUpdates() },
                    onInstall = { viewModel.installUpdate() },
                    onConfirm = { waiting ->
                        try {
                            context.startActivity(waiting.confirmation)
                            viewModel.confirmationShown()
                        } catch (e: ActivityNotFoundException) {
                            viewModel.checkForUpdates()
                        }
                    },
                )
                Tab.DEBUG -> DebugScreen(
                    engine = engine,
                    settings = settings,
                    sessions = sessions,
                    log = log,
                    onEqMode = { viewModel.setEqMode(it) },
                    onCompensation = { mode, enabled -> viewModel.setCompensation(mode, enabled) },
                )
            }
        }
    }
}
