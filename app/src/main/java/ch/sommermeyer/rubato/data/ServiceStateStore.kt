package ch.sommermeyer.rubato.data

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers whether the user wants the service to run. Plain SharedPreferences, because broadcast
 * receivers (package replaced, boot) need a synchronous answer.
 */
class ServiceStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)

    var serviceWanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) = prefs.edit { putBoolean(KEY_WANTED, value) }

    private companion object {
        const val KEY_WANTED = "service_wanted"
    }
}
