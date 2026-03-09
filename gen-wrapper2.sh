#!/bin/bash
cd /Users/aitor/zeroclaw-agents/personal/workspace/piper-tts-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/aitor/Library/Android/sdk
rm -f gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
/opt/homebrew/bin/gradle wrapper --gradle-version 8.5
echo "Result:"
ls -la gradlew gradle/wrapper/
