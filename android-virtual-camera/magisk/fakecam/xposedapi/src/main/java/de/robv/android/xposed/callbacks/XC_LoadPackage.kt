package de.robv.android.xposed.callbacks

import android.content.pm.ApplicationInfo

abstract class XC_LoadPackage : XCallback() {
    @Throws(Throwable::class)
    abstract fun handleLoadPackage(lpparam: LoadPackageParam)

    class LoadPackageParam : XCallback.Param {
        var appInfo: ApplicationInfo? = null

        constructor()
        constructor(
            packageName: String?,
            processName: String?,
            classLoader: ClassLoader?,
            appInfo: ApplicationInfo?
        ) {
            this.packageName = packageName
            this.processName = processName
            this.classLoader = classLoader
            this.appInfo = appInfo
        }
    }
}
