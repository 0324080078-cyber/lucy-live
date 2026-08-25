package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface IXposedMod {
    @Throws(Throwable::class)
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)

    @Throws(Throwable::class)
    fun initZygote(startupParam: StartupParam)

    class StartupParam {
        @JvmField
        var modulePath: String? = null
        @JvmField
        var cacheDir: String? = null
    }
}
