package ch.sommermeyer.rubato.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.sommermeyer.rubato.MainActivity
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.core.correction.ToneOffset
import ch.sommermeyer.rubato.engine.EngineState

enum class TapToStartReason { UPDATE, BOOT, BACKGROUND_START_DENIED }

class Notifications(private val context: Context) {
    data class ServiceContent(val title: String, val text: String, val paused: Boolean)

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val service = NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_service_name))
            .setDescription(context.getString(R.string.channel_service_description))
            .setShowBadge(false)
            .build()
        val alerts = NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.channel_alerts_name))
            .setDescription(context.getString(R.string.channel_alerts_description))
            .build()
        manager.createNotificationChannelsCompat(listOf(service, alerts))
    }

    fun serviceContent(state: EngineState): ServiceContent {
        val np = state.nowPlaying
        val title = np?.let { listOfNotNull(it.title, it.artist).joinToString(" – ").ifEmpty { it.appLabel } }
            ?: context.getString(R.string.notif_service_idle_title)
        val song = state.song
        val text = when {
            state.paused -> context.getString(R.string.notif_service_paused)
            !state.notificationAccess -> context.getString(R.string.notif_service_no_access)
            np == null -> context.getString(R.string.notif_service_no_playback)
            state.effect.attachments.isNotEmpty() && !state.effect.active -> context.getString(R.string.notif_service_effect_error)
            song == null || !song.loaded -> context.getString(R.string.notif_service_waiting)
            !song.hasCorrection && song.manual.isNeutral ->
                context.getString(R.string.notif_service_analysing, song.sessionAnalyzedMs / 1000)
            else -> {
                val summary = ToneOffset.summarize(state.appliedGainsDb.toDoubleArray())
                val values = context.getString(R.string.notif_service_correction, summary.bassDb, summary.midDb, summary.trebleDb)
                if (song.known) context.getString(R.string.notif_service_known, values) else values
            }
        }
        return ServiceContent(title, text, state.paused)
    }

    fun buildService(content: ServiceContent): Notification {
        val toggleLabel = context.getString(if (content.paused) R.string.action_resume else R.string.action_pause)
        val toggleIcon = if (content.paused) R.drawable.ic_play else R.drawable.ic_pause
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_rubato)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(toggleIcon, toggleLabel, togglePauseIntent())
            .build()
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    fun updateService(content: ServiceContent) {
        if (canPost()) manager.notify(ID_SERVICE, buildService(content))
    }

    fun buildTapToStart(reason: TapToStartReason): Notification {
        val title = when (reason) {
            TapToStartReason.UPDATE -> R.string.notif_update_installed_title
            TapToStartReason.BOOT -> R.string.notif_boot_title
            TapToStartReason.BACKGROUND_START_DENIED -> R.string.notif_start_denied_title
        }
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_rubato)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(R.string.notif_tap_to_start))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(startFromActivityIntent())
            .build()
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    fun showTapToStart(reason: TapToStartReason) {
        if (canPost()) manager.notify(ID_TAP_TO_START, buildTapToStart(reason))
    }

    fun cancelTapToStart() = manager.cancel(ID_TAP_TO_START)

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The service needs a microphone-type foreground service, which only an app in the foreground
     * may start. Tapping opens the activity, which then starts the service.
     */
    private fun startFromActivityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_START,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_SERVICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun togglePauseIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_TOGGLE,
        Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_TOGGLE_PAUSE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_SERVICE = 1
        const val ID_TAP_TO_START = 2
        private const val REQUEST_OPEN = 1
        private const val REQUEST_START = 2
        private const val REQUEST_TOGGLE = 3
    }
}
