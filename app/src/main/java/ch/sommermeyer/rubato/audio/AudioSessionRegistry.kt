package ch.sommermeyer.rubato.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.appGraph
import ch.sommermeyer.rubato.engine.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlayerSession(val sessionId: Int, val packageName: String?, val openedAtMillis: Long)

/** Audio sessions that players announced with ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION. */
class AudioSessionRegistry(private val context: Context, private val log: EventLog) {
    private val _sessions = MutableStateFlow<Map<Int, PlayerSession>>(emptyMap())
    val sessions: StateFlow<Map<Int, PlayerSession>> = _sessions.asStateFlow()

    /** Returns true if [intent] was an audio session broadcast. */
    fun handle(intent: Intent): Boolean {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioManager.AUDIO_SESSION_ID_GENERATE)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                if (sessionId <= 0) return true
                val known = _sessions.value[sessionId]
                if (known?.packageName == packageName) return true
                _sessions.update { it + (sessionId to PlayerSession(sessionId, packageName, System.currentTimeMillis())) }
                log.info(TAG, R.string.log_session_opened, sessionId, packageName ?: unknownPackage())
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                if (sessionId !in _sessions.value) return true
                _sessions.update { it - sessionId }
                log.info(TAG, R.string.log_session_closed, sessionId, packageName ?: unknownPackage())
            }
            else -> return false
        }
        return true
    }

    private fun unknownPackage() = context.getString(R.string.unknown_app)

    companion object {
        private const val TAG = "sessions"

        val intentFilter: IntentFilter
            get() = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
    }
}

/**
 * Manifest receiver for the session broadcasts. Implicit broadcasts usually only reach receivers
 * registered at runtime (the service registers one as well); this one catches the rest.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.appGraph.audioSessions.handle(intent)
    }
}
