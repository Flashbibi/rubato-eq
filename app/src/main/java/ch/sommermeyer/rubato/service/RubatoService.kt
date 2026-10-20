package ch.sommermeyer.rubato.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.appGraph
import ch.sommermeyer.rubato.audio.AudioSessionRegistry
import ch.sommermeyer.rubato.audio.AudioSessionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps analysis and EQ alive.
 *
 * Type microphone: the Visualizer needs RECORD_AUDIO, a while-in-use permission, so the service has
 * to be started while the app is in the foreground (on Android 14+ startForeground() throws a
 * SecurityException otherwise).
 */
class RubatoService : Service() {
    private val graph by lazy { appGraph }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var sessionReceiver: AudioSessionReceiver? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGracefully()
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY

        stopping = false
        graph.serviceState.serviceWanted = true
        graph.notifications.cancelTapToStart()
        registerSessionReceiver()
        graph.engine.start()
        if (notificationJob == null) {
            notificationJob = serviceScope.launch {
                graph.engine.state
                    .map { graph.notifications.serviceContent(it) }
                    .distinctUntilChanged()
                    .conflate()
                    .collect {
                        graph.notifications.updateService(it)
                        delay(NOTIFICATION_THROTTLE_MS)
                    }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSessionReceiver()
        notificationJob = null
        serviceScope.cancel()
        if (!stopping) {
            // Stopped by the system (or the app was updated): release the effects.
            graph.appScope.launch { graph.engine.stop() }
        }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = graph.notifications.buildService(
            graph.notifications.serviceContent(graph.engine.state.value),
        )
        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: Exception) {
            // Typically a SecurityException: microphone-type services cannot start from the background.
            graph.log.error(TAG, R.string.log_fgs_denied, error = e)
            satisfyForegroundContract()
            graph.notifications.showTapToStart(TapToStartReason.BACKGROUND_START_DENIED)
            stopSelf()
            false
        }
    }

    /**
     * A service started with startForegroundService() must call startForeground() or the app is
     * killed. When the microphone type is refused, briefly become a short service (no permission
     * prerequisites) and stop again.
     */
    private fun satisfyForegroundContract() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_TAP_TO_START,
                graph.notifications.buildTapToStart(TapToStartReason.BACKGROUND_START_DENIED),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    private fun stopGracefully() {
        if (stopping) return
        stopping = true
        graph.serviceState.serviceWanted = false
        serviceScope.launch {
            graph.engine.stop() // fades the EQ out first
            if (!stopping) {
                // Switched on again while the fade-out was running.
                graph.engine.start()
                return@launch
            }
            // No late update may bring the notification back after it was removed.
            notificationJob?.cancel()
            notificationJob = null
            ServiceCompat.stopForeground(this@RubatoService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Implicit broadcasts reach receivers registered at runtime; the players are other apps. */
    private fun registerSessionReceiver() {
        if (sessionReceiver != null) return
        val receiver = AudioSessionReceiver()
        ContextCompat.registerReceiver(this, receiver, AudioSessionRegistry.intentFilter, ContextCompat.RECEIVER_EXPORTED)
        sessionReceiver = receiver
    }

    private fun unregisterSessionReceiver() {
        sessionReceiver?.let { runCatching { unregisterReceiver(it) } }
        sessionReceiver = null
    }

    companion object {
        private const val TAG = "service"
        private const val ACTION_STOP = "ch.sommermeyer.rubato.action.STOP"
        private const val NOTIFICATION_THROTTLE_MS = 1_000L

        /** Must be called while the app is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RubatoService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RubatoService::class.java).setAction(ACTION_STOP))
        }
    }
}
