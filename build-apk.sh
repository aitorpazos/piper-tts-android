#!/bin/bash
cd /Users/aitor/zeroclaw-agents/personal/workspace/piper-tts-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/aitor/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/aitor/Library/Android/sdk

echo "=== Running unit tests ==="
./gradlew test --no-daemon 2>&1

echo ""
echo "=== Building debug APK ==="
./gradlew assembleDebug --no-daemon 2>&1

echo ""
echo "=== Build output ==="
find app/build/outputs -name "*.apk" 2>/dev/null
