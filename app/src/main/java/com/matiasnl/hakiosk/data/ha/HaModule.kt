package com.matiasnl.hakiosk.data.ha

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import com.matiasnl.hakiosk.data.ha.camera.WebSocketHaCameraSource
import com.matiasnl.hakiosk.data.ha.store.DataStoreHaConfigStore
import com.matiasnl.hakiosk.data.ha.store.KeystoreTokenCipher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Wires the Home Assistant data layer. Owned by the ha-client work stream. One instance per process. */
class HaModule(context: Context) {
    private val appContext = context.applicationContext

    /** Single shared client: one connection pool and dispatcher for REST and WebSocket. */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // Transport-level WebSocket ping: a missing pong fails the socket and triggers reconnect.
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(DataStoreHaConfigStore.DATASTORE_NAME) },
        )
    }

    val configStore: HaConfigStore by lazy { DataStoreHaConfigStore(dataStore, KeystoreTokenCipher()) }

    // Concrete type stays private: the camera source shares its live connection without widening HaRepository.
    private val webSocketRepository by lazy { WebSocketHaRepository(configStore, okHttpClient) }

    val repository: HaRepository get() = webSocketRepository

    /** Signaling rides the repository's connection (no extra socket); snapshots use REST with the stored config. */
    val cameraSource: HaCameraSource by lazy { WebSocketHaCameraSource(webSocketRepository, configStore, okHttpClient) }
}
