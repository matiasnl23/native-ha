package com.matiasnl.hakiosk.data.display

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Screen brightness, screen-off timeout and camera auto-close; a file of its own. */
private val Context.displayPreferencesDataStore by preferencesDataStore(name = "display_prefs")

/** Wires display preferences persistence. Owned by the android-ui work stream. */
class DisplayModule(private val context: Context) {
    val preferencesStore: DisplayPreferencesStore by lazy {
        DataStoreDisplayPreferencesStore(context.displayPreferencesDataStore)
    }
}
