package ch.sommermeyer.rubato.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.appGraph

/** Pause / resume from the service notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_PAUSE) context.appGraph.engine.togglePause()
    }

    companion object {
        const val ACTION_TOGGLE_PAUSE = "ch.sommermeyer.rubato.action.TOGGLE_PAUSE"
    }
}

/**
 * After an app update (MY_PACKAGE_REPLACED) or a reboot the service should come back if it was
 * running before.
 *
 * MY_PACKAGE_REPLACED and BOOT_COMPLETED are exempt from the general background-start restriction,
 * but not from the while-in-use rule of the microphone type: on Android 14+ startForeground()
 * throws a SecurityException from the background, before that the service would run without
 * microphone access and the Visualizer would fail. So instead of starting the service, ask the
 * user to tap a notification; the tap opens the activity, which starts the service.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> TapToStartReason.UPDATE
            Intent.ACTION_BOOT_COMPLETED -> TapToStartReason.BOOT
            else -> return
        }
        val graph = context.appGraph
        if (!graph.serviceState.serviceWanted) return
        graph.log.info(TAG, if (reason == TapToStartReason.UPDATE) R.string.log_restart_after_update else R.string.log_restart_after_boot)
        graph.notifications.showTapToStart(reason)
    }

    private companion object {
        const val TAG = "system"
    }
}
