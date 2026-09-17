package com.matiasnl.hakiosk.data.update.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.matiasnl.hakiosk.data.update.InstallPermission

/** [InstallPermission] on top of `PackageManager`; the permission itself is declared in the manifest. */
class AndroidInstallPermission(context: Context) : InstallPermission {
    private val appContext = context.applicationContext

    override fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /**
     * With the `package:` uri Settings opens straight on this app's toggle; without it the user lands on
     * a list of every app and has to find this one.
     */
    override fun settingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}"),
    )
}
