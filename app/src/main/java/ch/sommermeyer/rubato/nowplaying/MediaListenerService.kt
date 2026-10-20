package ch.sommermeyer.rubato.nowplaying

import android.service.notification.NotificationListenerService
import ch.sommermeyer.rubato.appGraph
import kotlinx.coroutines.flow.update

/**
 * Notification listener. It does not look at notifications; being an enabled listener is what
 * allows [android.media.session.MediaSessionManager.getActiveSessions] to see other apps' sessions.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        applicationContext.appGraph.listenerConnections.update { it + 1 }
    }
}
