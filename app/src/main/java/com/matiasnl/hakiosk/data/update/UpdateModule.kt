package com.matiasnl.hakiosk.data.update

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.matiasnl.hakiosk.data.update.android.AndroidInstallPermission
import com.matiasnl.hakiosk.data.update.android.AndroidInstalledAppInfo
import com.matiasnl.hakiosk.data.update.android.PackageInstallerApkInstaller
import com.matiasnl.hakiosk.data.update.android.PackageManagerApkSignatureVerifier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Wires over-the-air updates. Owned by the device-control work stream. One instance per process.
 *
 * Takes the app's shared [OkHttpClient] rather than building its own: on a 2 GB tablet a second
 * connection pool and dispatcher for one HTTPS GET a day is not worth it.
 */
class UpdateModule(context: Context, okHttpClient: OkHttpClient) {
    private val appContext = context.applicationContext

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(DataStoreUpdatePreferencesStore.DATASTORE_NAME) },
        )
    }

    val preferencesStore: UpdatePreferencesStore by lazy { DataStoreUpdatePreferencesStore(dataStore) }

    /**
     * Internal storage only ([Context.getCacheDir]): on external storage another app could swap the APK
     * between the verification and the install.
     */
    private val downloadDir get() = File(appContext.cacheDir, DOWNLOAD_DIR_NAME)

    val manager: UpdateManager by lazy {
        UpdateManager(
            metadataSource = ReleaseMetadataClient(okHttpClient),
            downloader = ApkDownloader(okHttpClient, downloadDir),
            signatureVerifier = PackageManagerApkSignatureVerifier(appContext),
            installer = PackageInstallerApkInstaller(appContext, log = { Log.i(TAG, it) }),
            installPermission = AndroidInstallPermission(appContext),
            preferencesStore = preferencesStore,
            installedApp = AndroidInstalledAppInfo(appContext),
            // Process-wide: the schedule outlives every activity, like the MQTT connection.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = { Log.i(TAG, it) },
        )
    }

    init {
        // Started here rather than from an activity: the schedule belongs to the process, and on a kiosk
        // the process is what stays alive. It only reaches the network when a check is actually due.
        manager.start()
    }

    private companion object {
        const val TAG = "Update"
        const val DOWNLOAD_DIR_NAME = "updates"
    }
}
