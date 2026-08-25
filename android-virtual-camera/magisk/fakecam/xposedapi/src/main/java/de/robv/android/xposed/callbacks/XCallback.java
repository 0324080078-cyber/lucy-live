package de.robv.android.xposed.callbacks

open class XCallback {
    @JvmField
    var priority: Int = 50

    constructor()
    constructor(priority: Int) {
        this.priority = priority
    }

    open class Param {
        @JvmField
        var packageName: String? = null
        @JvmField
        var processName: String? = null
        @JvmField
        var classLoader: ClassLoader? = null
    }
}
