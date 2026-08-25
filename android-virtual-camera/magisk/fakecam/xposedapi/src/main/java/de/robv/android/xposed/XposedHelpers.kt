package de.robv.android.xposed

import de.robv.android.xposed.XC_MethodHook.Unhook

object XposedHelpers {
    @JvmStatic
    fun findAndHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): Unhook? = null

    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ): Unhook? = null
}
