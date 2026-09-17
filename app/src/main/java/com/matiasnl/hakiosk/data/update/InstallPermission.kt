package com.matiasnl.hakiosk.data.update

import android.content.Intent

/**
 * "Install unknown apps", the one-off permission a sideloaded app needs to install another APK.
 *
 * Without Device Owner the system confirmation dialog appears on every install no matter what: that is
 * expected and there is no flag to skip it on Android 10 (`setRequireUserAction` is API 31). This only
 * covers the permission that lets the dialog appear at all.
 */
interface InstallPermission {
    /** `PackageManager.canRequestPackageInstalls()`. Granted once, in Settings, by the user. */
    fun canInstallPackages(): Boolean

    /** Settings screen where the user grants it, scoped to this app with a `package:` uri. */
    fun settingsIntent(): Intent
}
