package org.openscore.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dark mode is a tri-state: unset follows the phone, which is what everyone gets until they touch the switch. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _darkMode = MutableStateFlow(if (prefs.contains(DARK)) prefs.getBoolean(DARK, false) else null)
    val darkMode: StateFlow<Boolean?> = _darkMode

    /** Null goes back to following the phone. */
    fun setDarkMode(dark: Boolean?) {
        _darkMode.value = dark
        prefs.edit().apply { if (dark == null) remove(DARK) else putBoolean(DARK, dark) }.apply()
    }

    private companion object {
        const val DARK = "dark"
    }
}
