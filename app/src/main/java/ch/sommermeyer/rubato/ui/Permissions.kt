package ch.sommermeyer.rubato.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.sommermeyer.rubato.nowplaying.MediaListenerService

data class PermissionSnapshot(
    val notificationListener: Boolean = false,
    val recordAudio: Boolean = false,
    val postNotifications: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val installPackages: Boolean = false,
) {
    /** Minimum for the service: song detection and the Visualizer. */
    val readyForService: Boolean get() = notificationListener && recordAudio
}

object Permissions {
    fun snapshot(context: Context) = PermissionSnapshot(
        notificationListener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        recordAudio = isGranted(context, Manifest.permission.RECORD_AUDIO),
        postNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        installPackages = context.packageManager.canRequestPackageInstalls(),
    )

    fun openNotificationListenerSettings(context: Context) {
        val component = ComponentName(context, MediaListenerService::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        context.startFirst(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        context.startFirst(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    @SuppressLint("BatteryLife") // Sideloaded personal app; the exemption is the point.
    fun requestIgnoreBatteryOptimizations(context: Context) {
        context.startFirst(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }

    fun openUnknownSources(context: Context) {
        context.startFirst(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context)),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
    }

    fun openSecuritySettings(context: Context) {
        context.startFirst(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    fun openBatterySettings(context: Context) {
        context.startFirst(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_SETTINGS))
    }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** Starts the first intent that resolves; settings screens differ between vendors. */
    private fun Context.startFirst(vararg intents: Intent) {
        for (intent in intents) {
            val started = try {
                startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
            if (started) return
        }
    }
}
