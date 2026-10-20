package ch.sommermeyer.rubato.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import ch.sommermeyer.rubato.BuildConfig
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.appGraph
import ch.sommermeyer.rubato.core.update.AppVersion
import ch.sommermeyer.rubato.core.update.GitHubAsset
import ch.sommermeyer.rubato.core.update.GitHubRelease
import ch.sommermeyer.rubato.engine.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-tap self-updater (release builds only): looks up the latest GitHub release, downloads
 * `rubato-eq.apk` into the cache and installs it with a PackageInstaller session that does not
 * require user action where Android allows it (the app updates itself).
 */
class UpdateManager(private val context: Context, private val log: EventLog) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val release: GitHubRelease) : State
        data class Available(val release: GitHubRelease, val asset: GitHubAsset) : State
        data class Downloading(val release: GitHubRelease, val progress: Float?) : State
        data class Installing(val release: GitHubRelease) : State
        data class WaitingForUser(val release: GitHubRelease?, val confirmation: Intent) : State
        data class Failed(val message: String, val release: GitHubRelease?) : State
    }

    val enabled: Boolean = BuildConfig.UPDATER_ENABLED

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _latestRelease = MutableStateFlow<GitHubRelease?>(null)
    val latestRelease: StateFlow<GitHubRelease?> = _latestRelease.asStateFlow()

    private var checkedOnStart = false

    suspend fun checkOnAppStart() {
        if (!enabled || checkedOnStart) return
        checkedOnStart = true
        check()
    }

    suspend fun check() {
        if (!enabled || _state.value.isBusy()) return
        _state.value = State.Checking
        _state.value = withContext(Dispatchers.IO) {
            try {
                val release = fetchLatestRelease()
                _latestRelease.value = release
                val asset = release.asset()
                when {
                    !AppVersion.isNewer(release.tagName, BuildConfig.VERSION_NAME) -> State.UpToDate(release)
                    asset == null -> State.Failed(context.getString(R.string.update_error_no_asset, release.tagName), release)
                    else -> {
                        log.info(TAG, R.string.log_update_available, release.tagName)
                        State.Available(release, asset)
                    }
                }
            } catch (e: Exception) {
                log.warn(TAG, R.string.log_update_check_failed, error = e)
                State.Failed(e.userMessage(), null)
            }
        }
    }

    suspend fun downloadAndInstall() {
        val available = _state.value as? State.Available ?: return
        withContext(Dispatchers.IO) {
            try {
                val apk = download(available.release, available.asset)
                _state.value = State.Installing(available.release)
                install(apk)
            } catch (e: Exception) {
                log.error(TAG, R.string.log_update_install_failed, error = e)
                _state.value = State.Failed(e.userMessage(), available.release)
            }
        }
    }

    /** The user opened the system confirmation from the update screen. */
    fun confirmationShown() {
        val waiting = _state.value as? State.WaitingForUser ?: return
        _state.value = waiting.release?.let { State.Installing(it) } ?: State.Idle
    }

    fun onInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val release = when (val s = _state.value) {
            is State.Installing -> s.release
            is State.WaitingForUser -> s.release
            else -> _latestRelease.value
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    _state.value = State.Failed(context.getString(R.string.update_error_no_confirmation), release)
                    return
                }
                log.info(TAG, R.string.log_update_needs_confirmation)
                _state.value = State.WaitingForUser(release, confirmation)
                // Works while the app is visible; otherwise the update screen offers a button.
                runCatching { context.startActivity(Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onSuccess { confirmationShown() }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                log.info(TAG, R.string.log_update_installed)
                _state.value = State.Idle
            }
            else -> {
                val text = message ?: context.getString(R.string.update_error_status, status)
                log.error(TAG, R.string.log_update_status, status, text)
                _state.value = State.Failed(text, release)
            }
        }
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> GitHubRelease.parse(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateException(context.getString(R.string.update_error_no_release))
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw UpdateException(context.getString(R.string.update_error_rate_limit))
                else -> throw UpdateException(context.getString(R.string.update_error_http, code))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(release: GitHubRelease, asset: GitHubAsset): File {
        val directory = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val target = File(directory, GitHubRelease.APK_ASSET_NAME)
        _state.value = State.Downloading(release, null)
        val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw UpdateException(context.getString(R.string.update_error_http, code))
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - reported >= PROGRESS_STEP_BYTES) {
                            reported = copied
                            _state.value = State.Downloading(release, total?.let { copied.toFloat() / it })
                        }
                    }
                }
            }
            _state.value = State.Downloading(release, 1f)
        } finally {
            connection.disconnect()
        }
        if (asset.size > 0 && target.length() != asset.size) {
            throw UpdateException(context.getString(R.string.update_error_incomplete))
        }
        return target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Possible because the app updates itself and holds UPDATE_PACKAGES_WITHOUT_USER_ACTION.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(GitHubRelease.APK_ASSET_NAME, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                // Mutable: the installer adds the status extras.
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            log.info(TAG, R.string.log_update_committed)
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun State.isBusy() = this is State.Checking || this is State.Downloading || this is State.Installing

    private fun Exception.userMessage(): String = when (this) {
        is UpdateException -> message.orEmpty()
        is IOException -> context.getString(R.string.update_error_network, message ?: javaClass.simpleName)
        else -> message ?: javaClass.simpleName
    }

    private class UpdateException(message: String) : Exception(message)

    private companion object {
        const val TAG = "update"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Flashbibi/rubato-eq/releases/latest"
        const val TIMEOUT_MS = 20_000
        const val PROGRESS_STEP_BYTES = 128 * 1024L
        val USER_AGENT = "rubato-eq/${BuildConfig.VERSION_NAME}"
    }
}

/** Receives the result of a PackageInstaller session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) context.appGraph.updater.onInstallStatus(intent)
    }

    companion object {
        const val ACTION = "ch.sommermeyer.rubato.action.INSTALL_STATUS"
    }
}
