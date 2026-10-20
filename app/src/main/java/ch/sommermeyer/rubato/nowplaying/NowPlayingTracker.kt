package ch.sommermeyer.rubato.nowplaying

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import ch.sommermeyer.rubato.core.model.SongKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStatus { PLAYING, BUFFERING, PAUSED, STOPPED }

data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val status: PlaybackStatus,
) {
    val songKey: SongKey? get() = SongKey.of(artist, title, durationMs)

    /** Audio is (about to be) audible. Buffering covers track changes and seeks. */
    val isActive: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING
}

/**
 * Follows the active media sessions (requires notification access) and reports the primary one:
 * the first playing session in priority order, otherwise the most important session.
 * Must be used on the thread of [handler]; callbacks are delivered there as well.
 */
class NowPlayingTracker(private val context: Context, private val handler: Handler) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private val controllers = linkedMapOf<MediaSession.Token, MediaController>()
    private val callbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()
    private val labels = mutableMapOf<String, String>()
    private var listening = false

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list.orEmpty())
    }

    val isListening: Boolean get() = listening

    /** Starts tracking. Returns false if notification access has not been granted (yet). */
    fun start(): Boolean {
        if (listening) return true
        return try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            listening = true
            onSessionsChanged(sessionManager.getActiveSessions(listenerComponent))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        controllers.forEach { (token, controller) -> callbacks[token]?.let(controller::unregisterCallback) }
        controllers.clear()
        callbacks.clear()
        _nowPlaying.value = null
    }

    private fun onSessionsChanged(active: List<MediaController>) {
        val tokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token ->
            callbacks.remove(token)?.let { controllers[token]?.unregisterCallback(it) }
            controllers.remove(token)
        }
        val ordered = linkedMapOf<MediaSession.Token, MediaController>()
        for (controller in active) {
            val token = controller.sessionToken
            val known = controllers[token]
            if (known != null) {
                ordered[token] = known
            } else {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = update()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = update()
                    override fun onSessionDestroyed() = update()
                }
                controller.registerCallback(callback, handler)
                callbacks[token] = callback
                ordered[token] = controller
            }
        }
        controllers.clear()
        controllers.putAll(ordered)
        update()
    }

    private fun update() {
        val list = controllers.values.toList()
        val primary = list.firstOrNull { statusOf(it.playbackState).isActiveStatus() } ?: list.firstOrNull()
        _nowPlaying.value = primary?.let(::describe)
    }

    private fun describe(controller: MediaController): NowPlaying {
        val metadata = controller.metadata
        val packageName = controller.packageName
        return NowPlaying(
            packageName = packageName,
            appLabel = labelOf(packageName),
            title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = metadata?.text(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            status = statusOf(controller.playbackState),
        )
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }

    private fun statusOf(state: PlaybackState?): PlaybackStatus = when (state?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> PlaybackStatus.PLAYING
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.BUFFERING
        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
        else -> PlaybackStatus.STOPPED
    }

    private fun PlaybackStatus.isActiveStatus() = this == PlaybackStatus.PLAYING || this == PlaybackStatus.BUFFERING

    private fun labelOf(packageName: String): String = labels.getOrPut(packageName) {
        val pm = context.packageManager
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }
}
