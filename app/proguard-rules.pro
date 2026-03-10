# Piper TTS Android - ProGuard / R8 Rules

# ── ONNX Runtime ──────────────────────────────────────────
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Model / data classes ──────────────────────────────────
-keep class com.aitorpazos.pipertts.model.** { *; }
-keepclassmembers class com.aitorpazos.pipertts.model.** {
    <init>(...);
    <fields>;
}

# ── TTS Service ───────────────────────────────────────────
# Keep the entire service class and all its methods — Android TTS framework
# calls methods via reflection/framework binding, and R8 may strip overrides
# (onIsLanguageAvailable, onLoadLanguage, onSynthesizeText, etc.)
-keep class com.aitorpazos.pipertts.service.PiperTtsService { *; }
-keep class android.speech.tts.TextToSpeechService { *; }
-keepclassmembers class * extends android.speech.tts.TextToSpeechService {
    *;
}

# ── TTS Engine Activities (required by Android TTS framework) ──
-keep class com.aitorpazos.pipertts.ui.CheckVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.InstallVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.GetSampleText { *; }
-keep class com.aitorpazos.pipertts.ui.TtsSettingsActivity { *; }
-keep class com.aitorpazos.pipertts.ui.MainActivity { *; }
-keep class com.aitorpazos.pipertts.ui.VoiceListActivity { *; }

# ── Download manager data classes ─────────────────────────
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$* { *; }
-keepclassmembers class com.aitorpazos.pipertts.download.VoiceDownloadManager$* {
    <init>(...);
    <fields>;
}
