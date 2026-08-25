package com.zeypher.fakecam

import android.hardware.camera2.CameraCharacteristics
import android.os.Parcel
import android.util.Log
import java.lang.reflect.Method

/**
 * Reflection helpers to clone a CameraCharacteristics and wrap a
 * CameraMetadataNative. The hidden class `android.hardware.camera2.impl
 * .CameraMetadataNative` is NOT in the public SDK, so we never reference it by
 * type — only via reflection at runtime (where system_server can see it).
 */
object CameraMetadataBridge {

    private const val TAG = "LucyFakeCam"
    private val nativeClass by lazy { Class.forName("android.hardware.camera2.impl.CameraMetadataNative") }
    private val setMethod: Method? by lazy {
        runCatching { nativeClass.getMethod("set", CameraCharacteristics.Key::class.java, Any::class.java) }.getOrNull()
    }
    private val creator by lazy {
        runCatching { nativeClass.getField("CREATOR").get(null) as android.os.Parcelable.Creator<*> }.getOrNull()
    }

    /** Returns a deep-cloned CameraMetadataNative instance (as Any), or null. */
    fun duplicate(base: CameraCharacteristics): Any? {
        val src = getMetadata(base) ?: return null
        return try {
            val p = Parcel.obtain()
            (src.javaClass.getMethod("writeToParcel", Parcel::class.java, Int::class.javaPrimitiveType)
                .invoke(src, p, 0))
            p.setDataPosition(0)
            val cloned = creator?.javaClass?.getMethod("createFromParcel", Parcel::class.java)
                ?.invoke(creator, p)
                ?: nativeClass.getMethod("createFromParcel", Parcel::class.java).invoke(null, p)
            p.recycle()
            cloned
        } catch (t: Throwable) {
            Log.e(TAG, "duplicate meta fail", t)
            null
        }
    }

    fun set(meta: Any, key: CameraCharacteristics.Key<*>, value: Any) {
        try {
            setMethod?.invoke(meta, key, value)
        } catch (t: Throwable) {
            Log.e(TAG, "meta set fail", t)
        }
    }

    fun wrap(meta: Any): CameraCharacteristics {
        val ctor = CameraCharacteristics::class.java.getDeclaredConstructor(nativeClass)
        ctor.isAccessible = true
        return ctor.newInstance(meta) as CameraCharacteristics
    }

    private fun getMetadata(c: CameraCharacteristics): Any? {
        for (name in arrayOf("mProperties", "mMetadata", "mCameraMetadata")) {
            try {
                val f = CameraCharacteristics::class.java.getDeclaredField(name)
                f.isAccessible = true
                return f.get(c)
            } catch (_: Throwable) { }
        }
        return null
    }
}
