package ch.sommermeyer.rubato.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.appGraph
import ch.sommermeyer.rubato.audio.PlayerSession
import ch.sommermeyer.rubato.core.correction.ToneOffset
import ch.sommermeyer.rubato.data.EqMode
import ch.sommermeyer.rubato.data.RubatoSettings
import ch.sommermeyer.rubato.engine.EngineState
import ch.sommermeyer.rubato.engine.EventLog
import ch.sommermeyer.rubato.service.RubatoService
import ch.sommermeyer.rubato.update.UpdateManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiEvent {
    data class Launch(val intent: Intent) : UiEvent
    data class Message(val text: String) : UiEvent
}

class RubatoViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph

    val settings: StateFlow<RubatoSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val engine: StateFlow<EngineState> = graph.engine.state
    val log: StateFlow<List<EventLog.Entry>> = graph.log.entries
    val sessions: StateFlow<Map<Int, PlayerSession>> = graph.audioSessions.sessions
    val update: StateFlow<UpdateManager.State> = graph.updater.state
    val latestRelease = graph.updater.latestRelease
    val updaterEnabled: Boolean = graph.updater.enabled
    val songCount: StateFlow<Int> =
        graph.database.songs().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val trainingCount: StateFlow<Int> =
        graph.database.trainingExamples().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _permissions = MutableStateFlow(Permissions.snapshot(application))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _showOnboarding = MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    fun refreshPermissions() {
        _permissions.value = Permissions.snapshot(getApplication())
    }

    fun openOnboarding() {
        _showOnboarding.value = true
    }

    fun finishOnboarding() {
        _showOnboarding.value = false
        viewModelScope.launch { graph.settings.setOnboardingDone(true) }
    }

    fun setServiceRunning(running: Boolean) {
        val app = getApplication<Application>()
        if (!running) {
            RubatoService.stop(app)
            return
        }
        refreshPermissions()
        if (!_permissions.value.readyForService) {
            openOnboarding()
            return
        }
        try {
            RubatoService.start(app)
        } catch (e: Exception) {
            message(app.getString(R.string.error_service_start, e.message ?: e.javaClass.simpleName))
        }
    }

    fun togglePause() = graph.engine.togglePause()

    fun setStrength(value: Float) = viewModelScope.launch { graph.settings.setStrength(value) }

    fun setMaxCorrection(value: Float) = viewModelScope.launch { graph.settings.setMaxCorrectionDb(value) }

    fun setTasteBass(value: Float) = viewModelScope.launch { graph.settings.setTasteBassDb(value) }

    fun setTasteTreble(value: Float) = viewModelScope.launch { graph.settings.setTasteTrebleDb(value) }

    fun setEqMode(mode: EqMode) = viewModelScope.launch { graph.settings.setEqMode(mode) }

    fun setCompensation(mode: EqMode, enabled: Boolean) =
        viewModelScope.launch { graph.settings.setCompensation(mode, enabled) }

    fun setManualOffset(offset: ToneOffset) = graph.engine.setManualOffset(offset)

    fun commitManualOffset() = graph.engine.commitManualOffset()

    fun resetAnalysisData() {
        graph.engine.resetAnalysisData()
        message(getApplication<Application>().getString(R.string.data_reset_done))
    }

    fun exportData() = viewModelScope.launch {
        try {
            val uri = graph.exporter.export()
            _events.send(UiEvent.Launch(graph.exporter.shareIntent(uri)))
        } catch (e: Exception) {
            message(getApplication<Application>().getString(R.string.export_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    fun checkForUpdatesOnAppStart() = viewModelScope.launch { graph.updater.checkOnAppStart() }

    fun checkForUpdates() = viewModelScope.launch { graph.updater.check() }

    fun installUpdate() = viewModelScope.launch { graph.updater.downloadAndInstall() }

    fun confirmationShown() = graph.updater.confirmationShown()

    private fun message(text: String) {
        _events.trySend(UiEvent.Message(text))
    }
}
