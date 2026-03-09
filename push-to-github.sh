#!/bin/bash
cd /Users/aitor/zeroclaw-agents/personal/workspace/piper-tts-android

# Init git repo and push to GitHub
git init
git add -A
git commit -m "feat: initial Piper TTS Android app

- System TTS engine (TextToSpeechService) powered by Piper/ONNX Runtime
- Bundled en_US-amy-low voice model (~60MB)
- PhonemeConverter for text-to-phoneme-ID mapping
- PiperEngine for ONNX inference
- VoiceManager for voice model discovery and loading
- MainActivity with test UI (speak/stop)
- TtsSettingsActivity for system TTS settings integration
- Unit tests: PhonemeConverterTest (9 tests), PiperVoiceConfigTest (6 tests)
- All 15 unit tests passing"

git branch -M main
git remote add origin git@github.com:aitorpazos/piper-tts-android.git
git push -u origin main

echo "Push complete"
