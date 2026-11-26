package com.paperreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

data class Settings(
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    /** WebView text zoom in percent. */
    val textZoom: Int = 100,
)

/**
 * Reader preferences. Backed by SharedPreferences so the WebView request
 * handler (a background thread) can read the current values synchronously.
 */
class ReaderSettings(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun load() = Settings(
        theme = runCatching { ReaderTheme.valueOf(prefs.getString("theme", null)!!) }
            .getOrDefault(ReaderTheme.SYSTEM),
        textZoom = prefs.getInt("textZoom", 100),
    )

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit().putString("theme", next.theme.name).putInt("textZoom", next.textZoom).apply()
        _state.value = next
    }
}
