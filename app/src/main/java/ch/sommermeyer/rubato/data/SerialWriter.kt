package ch.sommermeyer.rubato.data

import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.engine.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs database writes one after another in submission order, so a newer state of a song can
 * never be overwritten by an older one. Writes outlive the engine (they run in the app scope).
 */
class SerialWriter(scope: CoroutineScope, private val log: EventLog) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (write in queue) {
                try {
                    write()
                } catch (e: Exception) {
                    log.error(TAG, R.string.log_db_write_failed, error = e)
                }
            }
        }
    }

    fun submit(write: suspend () -> Unit) {
        queue.trySend(write)
    }

    private companion object {
        const val TAG = "db"
    }
}
