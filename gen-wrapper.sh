#!/bin/bash
cd /Users/aitor/zeroclaw-agents/personal/workspace/piper-tts-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/aitor/Library/Android/sdk

# Remove old wrapper files
rm -f gradle/wrapper/gradle-wrapper.jar
rm -f gradlew gradlew.bat

# Generate proper wrapper using installed gradle
/opt/homebrew/bin/gradle wrapper --gradle-version 8.5

echo "Wrapper generated:"
ls -la gradlew gradle/wrapper/
