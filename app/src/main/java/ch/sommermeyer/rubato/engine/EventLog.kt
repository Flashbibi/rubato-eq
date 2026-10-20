package ch.sommermeyer.rubato.engine

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ring buffer of recent events for the debug screen, mirrored to logcat. Messages are string
 * resources because they are shown in the UI.
 */
class EventLog(private val context: Context, private val capacity: Int = 200) {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(tag: String, @StringRes message: Int, vararg args: Any?) =
        add(Level.INFO, tag, message, args, null)

    fun warn(tag: String, @StringRes message: Int, vararg args: Any?, error: Throwable? = null) =
        add(Level.WARN, tag, message, args, error)

    fun error(tag: String, @StringRes message: Int, vararg args: Any?, error: Throwable? = null) =
        add(Level.ERROR, tag, message, args, error)

    private fun add(level: Level, tag: String, @StringRes message: Int, args: Array<out Any?>, error: Throwable?) {
        val base = context.getString(message, *args)
        val text = if (error == null) base else "$base (${error.javaClass.simpleName}: ${error.message})"
        val logTag = "rubato.$tag"
        when (level) {
            Level.INFO -> Log.i(logTag, text)
            Level.WARN -> Log.w(logTag, text, error)
            Level.ERROR -> Log.e(logTag, text, error)
        }
        val entry = Entry(System.currentTimeMillis(), level, tag, text)
        _entries.update { (listOf(entry) + it).take(capacity) }
    }
}
