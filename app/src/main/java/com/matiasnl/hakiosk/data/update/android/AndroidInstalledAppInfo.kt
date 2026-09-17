package com.matiasnl.hakiosk.data.update.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.matiasnl.hakiosk.data.update.InstalledAppInfo

/** [InstalledAppInfo] from `PackageManager` and `Build`. Read once: none of it changes while the app runs. */
class AndroidInstalledAppInfo(context: Context) : InstalledAppInfo {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val packageInfo = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    override val packageName: String = appContext.packageName

    /** 0 when the package can't be read, which makes every published release look newer, never older. */
    override val versionCode: Long = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

    override val versionName: String = packageInfo?.versionName ?: UNKNOWN_VERSION

    override val sdkInt: Int = Build.VERSION.SDK_INT

    /** `Build.SUPPORTED_ABIS`, never the deprecated `Build.CPU_ABI`: order matters when picking an APK. */
    override val supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
    }
}
