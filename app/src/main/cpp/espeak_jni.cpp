/*
 * Piper TTS for Android
 * Copyright (C) 2026 Aitor Pazos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <jni.h>
#include <string>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define TAG "EspeakJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_initialized = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aitorpazos_pipertts_engine_EspeakNative_nativeInitialize(
        JNIEnv *env, jclass clazz, jstring dataPath) {

    if (g_initialized) {
        LOGI("eSpeak-ng already initialized");
        return JNI_TRUE;
    }

    const char *path = env->GetStringUTFChars(dataPath, nullptr);
    LOGI("Initializing eSpeak-ng with data path: %s", path);

    // AUDIO_OUTPUT_SYNCHRONOUS: we don't want espeak to play audio,
    // we only use it for text-to-phoneme conversion.
    int result = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);
    env->ReleaseStringUTFChars(dataPath, path);

    if (result == -1) {
        LOGE("espeak_Initialize failed");
        return JNI_FALSE;
    }

    LOGI("eSpeak-ng initialized successfully, sample rate: %d", result);
    g_initialized = true;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_aitorpazos_pipertts_engine_EspeakNative_nativeTextToPhonemes(
        JNIEnv *env, jclass clazz, jstring voiceName, jstring text) {

    if (!g_initialized) {
        LOGE("eSpeak-ng not initialized");
        return env->NewStringUTF("");
    }

    // Set the voice
    const char *voice = env->GetStringUTFChars(voiceName, nullptr);
    espeak_ERROR err = espeak_SetVoiceByName(voice);
    env->ReleaseStringUTFChars(voiceName, voice);

    if (err != EE_OK) {
        LOGE("espeak_SetVoiceByName failed: %d", err);
        return env->NewStringUTF("");
    }

    // Convert text to IPA phonemes
    const char *textStr = env->GetStringUTFChars(text, nullptr);
    std::string result;

    const void *textPtr = textStr;
    while (textPtr != nullptr) {
        // phonememode: bit 1 = IPA output (value 2)
        // textmode: espeakCHARS_UTF8 = 1
        const char *phonemes = espeak_TextToPhonemes(&textPtr, 1, 2);
        if (phonemes != nullptr && phonemes[0] != '\0') {
            if (!result.empty()) {
                result += " ";
            }
            result += phonemes;
        }
    }

    env->ReleaseStringUTFChars(text, textStr);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_aitorpazos_pipertts_engine_EspeakNative_nativeTerminate(
        JNIEnv *env, jclass clazz) {
    if (g_initialized) {
        espeak_Terminate();
        g_initialized = false;
        LOGI("eSpeak-ng terminated");
    }
}

} // extern "C"
