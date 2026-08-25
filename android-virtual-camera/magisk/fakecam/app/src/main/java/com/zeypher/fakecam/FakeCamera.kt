package com.zeypher.fakecam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.IXposedMod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executor

/**
 * LSPosed module. Runs in system_server (package "android").
 *
 * Adds a virtual camera id "lucy_vcam". When an app opens it we open the real
 * front camera, tag the instance, and in createCaptureSession divert the client's
 * output Surfaces to our EGL renderer (fed by the Lucy app's Ashmem I420 buffer)
 * while giving the camera a throwaway surface so the session stays valid.
 *
 * Hooks every openCamera / createCaptureSession overload shipping apps actually call.
 */
class FakeCamera : IXposedMod {

    private var virtualDevice: CameraDevice? = null
    private var activeRenderer: VcamRenderer? = null
    private val W = 1280
    private val H = 720
    private val TAG = "LucyFakeCam"

    override fun initZygote(startupParam: IXposedMod.StartupParam) {}

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName != "android") return
        try {
            hookCameraManager(lpp.classLoader)
            VcamBufferClient.ensure() // start binding to the Lucy app's Ashmem service
        } catch (t: Throwable) { Log.e(TAG, "hook fail", t) }
    }

    private fun hookCameraManager(loader: ClassLoader) {
        // 1) enumerate -> add our id
        XposedHelpers.findAndHookMethod(
            CameraManager::class.java.name, loader, "getCameraIdList",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = (param.result as? Array<*>)?.map { it.toString() }?.toMutableList()
                        ?: mutableListOf()
                    if (!orig.contains(VCAM_ID)) orig.add(VCAM_ID)
                    param.result = orig.toTypedArray()
                }
            }
        )

        // also the hidden String[] variant some frameworks use
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager::class.java.name, loader, "getCameraIdListNoLazy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val orig = (param.result as? Array<*>)?.map { it.toString() }?.toMutableList()
                            ?: return
                        if (!orig.contains(VCAM_ID)) orig.add(VCAM_ID)
                        param.result = orig.toTypedArray()
                    }
                }
            )
        } catch (_: Throwable) { }

        // 2) characteristics for our id
        XposedHelpers.findAndHookMethod(
            CameraManager::class.java.name, loader, "getCameraCharacteristics", String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != VCAM_ID) return
                    val mgr = param.thisObject as CameraManager
                    val front = frontCameraId(mgr) ?: return
                    // build a synthetic characteristics from the front camera's, with our facing/orientation
                    param.result = buildCharacteristics(mgr, front)
                }
            }
        )

        // 3) openCamera (Handler variant)
        XposedHelpers.findAndHookMethod(
            CameraManager::class.java.name, loader, "openCamera",
            String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = openVirtual(param)
            }
        )

        // 3b) openCamera (Executor variant, API 28+)
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager::class.java.name, loader, "openCamera",
                String::class.java, Executor::class.java, CameraDevice.StateCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] != VCAM_ID) return
                        val mgr = param.thisObject as CameraManager
                        val cam = otherCameraId(mgr) ?: return
                        val clientCb = param.args[2] as CameraDevice.StateCallback
                        val exec = param.args[1] as Executor?
                        val wrapped = wrapCallback(clientCb, exec)
                        param.args[0] = cam
                        param.args[1] = makeExecutor()
                        param.args[2] = wrapped
                    }
                }
            )
        } catch (_: Throwable) { }

        // 3c) openCameraForUid (hidden system entry)
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager::class.java.name, loader, "openCameraForUid",
                String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = openVirtual(param)
                }
            )
        } catch (_: Throwable) { }

        // 4) createCaptureSession (List variant)
        XposedHelpers.findAndHookMethod(
            CameraDevice::class.java.name, loader, "createCaptureSession",
            List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = divertSession(param, W, H)
            }
        )

        // 4b) createCaptureSession (SessionConfiguration variant, API 28+)
        try {
            XposedHelpers.findAndHookMethod(
                CameraDevice::class.java.name, loader, "createCaptureSession",
                SessionConfiguration::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== virtualDevice) return
                        val orig = param.args[0] as SessionConfiguration
                        val clientSurfaces = orig.outputConfigurations.flatMap { oc -> oc.surfaces }
                        val origCb = orig.stateCallback
                        val exec = orig.executor ?: makeExecutor()
                        val ir = ImageReader.newInstance(W, H, ImageFormat.PRIVATE, 2)
                        val throwaway = OutputConfiguration(ir.surface)
                        val wrapped = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                startRenderer(clientSurfaces)
                                origCb.onConfigured(session)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) = origCb.onConfigureFailed(session)
                            override fun onReady(session: CameraCaptureSession) = origCb.onReady(session)
                            override fun onActive(session: CameraCaptureSession) = origCb.onActive(session)
                            override fun onClosed(session: CameraCaptureSession) {
                                stopRenderer(); origCb.onClosed(session)
                            }
                        }
                        param.args[0] = SessionConfiguration(orig.sessionType, listOf(throwaway), exec, wrapped)
                    }
                }
            )
        } catch (_: Throwable) { }

        // 4c) createReprocessableCaptureSession (some apps)
        try {
            XposedHelpers.findAndHookMethod(
                CameraDevice::class.java.name, loader, "createReprocessableCaptureSession",
                OutputConfiguration::class.java, List::class.java,
                CameraCaptureSession.StateCallback::class.java, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== virtualDevice) return
                        val input = param.args[0] as OutputConfiguration
                        val outs = param.args[1] as List<*>
                        val clientSurfaces = outs.filterIsInstance<Surface>() + input.surfaces
                        val origCb = param.args[2] as CameraCaptureSession.StateCallback
                        val ir = ImageReader.newInstance(W, H, ImageFormat.PRIVATE, 2)
                        val throwaway = OutputConfiguration(ir.surface)
                        val wrapped = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                startRenderer(clientSurfaces)
                                origCb.onConfigured(session)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) = origCb.onConfigureFailed(session)
                            override fun onReady(session: CameraCaptureSession) = origCb.onReady(session)
                            override fun onActive(session: CameraCaptureSession) = origCb.onActive(session)
                            override fun onClosed(session: CameraCaptureSession) {
                                stopRenderer(); origCb.onClosed(session)
                            }
                        }
                        param.args[1] = listOf(throwaway)
                        param.args[2] = wrapped
                    }
                }
            )
        } catch (_: Throwable) { }
    }

    private fun openVirtual(param: MethodHookParam) {
        if (param.args[0] != VCAM_ID) return
        val mgr = param.thisObject as CameraManager
        val cam = otherCameraId(mgr) ?: return
        val clientCb = param.args[1] as CameraDevice.StateCallback
        val handler = param.args[2] as Handler?
        val exec = if (handler != null) Executor { handler.post(it) } else makeExecutor()
        val wrapped = wrapCallback(clientCb, exec)
        param.args[0] = cam
        param.args[1] = wrapped
        if (param.args.size > 3) param.args[2] = makeHandler()
    }

    private fun wrapCallback(clientCb: CameraDevice.StateCallback, exec: Executor?): CameraDevice.StateCallback {
        val e = exec ?: makeExecutor()
        return object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                virtualDevice = device
                e.execute { clientCb.onOpened(device) }
            }
            override fun onDisconnected(device: CameraDevice) = e.execute { clientCb.onDisconnected(device) }
            override fun onError(device: CameraDevice, error: Int) = e.execute { clientCb.onError(device, error) }
            override fun onClosed(device: CameraDevice) = e.execute { clientCb.onClosed(device) }
        }
    }

    private fun divertSession(param: MethodHookParam, w: Int, h: Int) {
        if (param.thisObject !== virtualDevice) return
        val clientSurfaces = (param.args[0] as List<*>).filterIsInstance<Surface>()
        val originalCb = param.args[1] as CameraCaptureSession.StateCallback
        val ir = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 2)
        val throwaway = ir.surface
        param.args[0] = listOf(throwaway)
        param.args[1] = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                startRenderer(clientSurfaces)
                originalCb.onConfigured(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = originalCb.onConfigureFailed(session)
            override fun onReady(session: CameraCaptureSession) = originalCb.onReady(session)
            override fun onActive(session: CameraCaptureSession) = originalCb.onActive(session)
            override fun onClosed(session: CameraCaptureSession) {
                stopRenderer(); originalCb.onClosed(session)
            }
        }
    }

    private fun startRenderer(surfaces: List<Surface>) {
        stopRenderer()
        if (surfaces.isEmpty()) return
        activeRenderer = VcamRenderer(surfaces)
    }

    private fun stopRenderer() {
        activeRenderer?.stop()
        activeRenderer = null
    }

    private fun buildCharacteristics(mgr: CameraManager, front: String): CameraCharacteristics {
        // Synthetic characteristics: front camera's profile with our facing/orientation.
        val base = mgr.getCameraCharacteristics(front)
        val meta = CameraMetadataBridge.duplicate(base)
        if (meta != null) {
            CameraMetadataBridge.set(meta, CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_FRONT)
            CameraMetadataBridge.set(meta, CameraCharacteristics.SENSOR_ORIENTATION, 270)
            CameraMetadataBridge.set(meta, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL)
            return CameraMetadataBridge.wrap(meta)
        }
        return base
    }

    private fun frontCameraId(mgr: CameraManager): String? {
        for (id in mgr.cameraIdList) {
            val c = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return mgr.cameraIdList.firstOrNull()
    }

    // Camera to open for the virtual device. The Lucy app already holds the FRONT
    // camera to generate the avatar, so we open a different (usually BACK) camera as
    // the throwaway device — avoids "camera in use" and lets both run at once.
    private fun otherCameraId(mgr: CameraManager): String? {
        val ids = mgr.cameraIdList
        var back: String? = null
        for (id in ids) {
            val c = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) back = id
        }
        return back ?: ids.firstOrNull { it != frontCameraId(mgr) } ?: ids.firstOrNull()
    }

    private fun makeHandler(): Handler {
        val t = HandlerThread("vcam-h").apply { start() }
        return Handler(t.looper)
    }

    private fun makeExecutor(): Executor {
        val h = makeHandler()
        return Executor { h.post(it) }
    }

    companion object {
        const val VCAM_ID = "lucy_vcam"
    }
}
