package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XCallback
import java.lang.reflect.Member

abstract class XC_MethodHook : XCallback {
    constructor() : super()
    constructor(priority: Int) : super(priority)

    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {
    }

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {
    }

    class MethodHookParam : XCallback.Param() {
        @JvmField
        var method: Member? = null
        @JvmField
        var thisObject: Any? = null
        @JvmField
        var args: Array<Any?> = emptyArray()
        @JvmField
        var result: Any? = null
        @JvmField
        var throwable: Throwable? = null
        var returnEarly: Boolean = false

        fun setResult(result: Any?) {
            this.result = result
            this.returnEarly = true
        }

        fun setThrowable(throwable: Throwable?) {
            this.throwable = throwable
            this.returnEarly = true
        }
    }

    class Unhook {
        fun unhook() {}
    }
}
