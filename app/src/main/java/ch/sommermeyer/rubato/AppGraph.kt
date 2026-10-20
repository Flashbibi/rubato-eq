package ch.sommermeyer.rubato

import android.content.Context
import ch.sommermeyer.rubato.audio.AudioSessionRegistry
import ch.sommermeyer.rubato.data.DataExporter
import ch.sommermeyer.rubato.data.RubatoDatabase
import ch.sommermeyer.rubato.data.SerialWriter
import ch.sommermeyer.rubato.data.ServiceStateStore
import ch.sommermeyer.rubato.data.SettingsRepository
import ch.sommermeyer.rubato.engine.EventLog
import ch.sommermeyer.rubato.engine.RubatoEngine
import ch.sommermeyer.rubato.service.Notifications
import ch.sommermeyer.rubato.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide singletons. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = EventLog(appContext)
    val settings = SettingsRepository(appContext)
    val serviceState = ServiceStateStore(appContext)
    val audioSessions = AudioSessionRegistry(appContext, log)
    val notifications = Notifications(appContext).also { it.createChannels() }

    /** Incremented whenever the notification listener (re)connects. */
    val listenerConnections = MutableStateFlow(0)

    val database: RubatoDatabase by lazy { RubatoDatabase.build(appContext) }
    val dbWriter: SerialWriter by lazy { SerialWriter(appScope, log) }
    val engine: RubatoEngine by lazy { RubatoEngine(appContext, this) }
    val updater: UpdateManager by lazy { UpdateManager(appContext, log) }
    val exporter: DataExporter by lazy { DataExporter(appContext, database, settings) }
}
