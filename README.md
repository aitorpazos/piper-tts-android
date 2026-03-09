# Piper TTS for Android

An open-source Android TTS engine powered by [Piper](https://github.com/rhasspy/piper) — a fast, local, neural text-to-speech system using ONNX Runtime.

## Features

- 🎙️ **System TTS Provider** — registers as an Android TTS engine, usable by any app
- 🔒 **Fully offline** — no internet required after installation
- ⚡ **Fast inference** — ONNX Runtime optimized for mobile
- 🎯 **High quality** — neural TTS with natural-sounding voices
- 📦 **Small footprint** — low-quality models ~15MB, medium ~60MB
- 🌍 **Multi-language** — supports 20+ languages via Piper voice models

## Architecture

```
┌──────────────────────────────────┐
│         Android System           │
│    (TextToSpeech Framework)      │
└──────────┬───────────────────────┘
           │
┌──────────▼───────────────────────┐
│      PiperTtsService             │
│   (TextToSpeechService impl)    │
└──────────┬───────────────────────┘
           │
┌──────────▼───────────────────────┐
│        PiperEngine               │
│  ┌─────────────┐ ┌────────────┐ │
│  │ PhonemeConv  │ │ ONNX       │ │
│  │ (text→IDs)  │ │ Runtime    │ │
│  └─────────────┘ └────────────┘ │
└──────────────────────────────────┘
```

## Building

```bash
# Clone
git clone git@github.com:aitorpazos/piper-tts-android.git
cd piper-tts-android

# Download a voice model
bash download-voice.sh

# Build debug APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17  # or your JDK 17 path
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

## Adding Voice Models

Place Piper `.onnx` and `.onnx.json` files in `app/src/main/assets/voices/`.

Download voices from: https://huggingface.co/rhasspy/piper-voices

## Usage as System TTS

1. Install the APK
2. Go to **Settings → Accessibility → Text-to-Speech** (or **Settings → Language & Input → TTS**)
3. Select **Piper TTS** as the preferred engine
4. Any app using Android's TTS API will now use Piper

## License

MIT License. Piper voices have their own licenses (see the Piper project).
