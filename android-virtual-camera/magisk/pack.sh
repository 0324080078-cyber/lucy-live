#!/bin/sh
# Pack the Magisk module (mirrors the GitHub Actions packaging step)
cd "$(dirname "$0")"
rm -rf lucy-vcam lucy-vcam-magisk.zip
mkdir -p lucy-vcam/fakecam
cp module.prop customize.sh service.sh HAL_SHIM.md README.md lucy-vcam/
cp ../fakecam/app/build/outputs/apk/debug/*.apk lucy-vcam/fakecam/lucy-fakecam.apk 2>/dev/null || \
  echo "WARNING: fakecam APK not found at ../fakecam/app/build/outputs/apk/debug/ — build it first (cd ../fakecam && ./gradlew assembleDebug)"
(cd lucy-vcam && zip -r ../lucy-vcam-magisk.zip .)
echo "built lucy-vcam-magisk.zip"
