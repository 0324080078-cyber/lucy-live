package de.robv.android.xposed

import de.robv.android.xposed.XC_MethodHook.Unhook

object XposedHelpers {
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun findAndHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): Unhook? = null

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun findAndHookMethod(
        clazz: Class<*>?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): Unhook? = null
}
