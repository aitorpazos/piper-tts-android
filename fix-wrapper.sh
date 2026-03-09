#!/bin/bash
cd /Users/aitor/zeroclaw-agents/personal/workspace/piper-tts-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/aitor/Library/Android/sdk

# Download proper gradle wrapper from gradle services
GRADLE_VERSION="8.5"
WRAPPER_DIR="gradle/wrapper"
mkdir -p "$WRAPPER_DIR"

# Use the official gradle wrapper jar from services.gradle.org
curl -L -o "$WRAPPER_DIR/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"

ls -la "$WRAPPER_DIR/gradle-wrapper.jar"
