# HAL-shim path (maximum compatibility)

The LSPosed/Camera2 injection works for the preview/capture Surface path used by almost
all video-call and social apps. A few apps (and some HAL-direct recorders) bypass Camera2
and talk to the camera HAL (`camera3_device_ops`) directly, or require specific private
formats. For those, inject at the HAL instead of the Java API.

## Sketch

1. Build a `camera.<vendor>.so` shim that wraps the real HAL and, for a virtual camera id,
   returns frames sourced from an Ashmem/gralloc buffer instead of the sensor.
2. The shim reads the same `LUCYVCAM` Ashmem region the Lucy app writes (convert RGBA→YUV
   in the shim, or have the app write YUV directly).
3. Register the virtual camera in `camera_provider` (HAL stub) so `CameraManager` enumerates
   it without the Java hook.
4. Load the shim via a Magisk module that replaces/overlay-mounts the vendor HAL and patches
   `cameraserver` to load it.

This is device- and ROM-specific (needs your board's HAL headers / `camera.ranchu` or
`camera.<soc>` source). The LSPosed module already does 90% of what you need for everyday
apps; reach for the HAL shim only when an app ignores `lucy_vcam`.
