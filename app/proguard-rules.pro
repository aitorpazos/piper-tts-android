# Piper TTS Android - ProGuard / R8 Rules

# ── ONNX Runtime ──────────────────────────────────────────
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Model / data classes ──────────────────────────────────
-keep class com.aitorpazos.pipertts.model.** { *; }
-keepclassmembers class com.aitorpazos.pipertts.model.** {
    <init>(...);
    <fields>;
    <methods>;
}

# ── TTS Service ───────────────────────────────────────────
# Keep the entire service class and all its methods — Android TTS framework
# calls methods via reflection/framework binding, and R8 may strip overrides
# (onIsLanguageAvailable, onLoadLanguage, onSynthesizeText, etc.)
-keep public class com.aitorpazos.pipertts.service.PiperTtsService { *; }
-keep public class * extends android.speech.tts.TextToSpeechService { *; }
-keepclassmembers class * extends android.speech.tts.TextToSpeechService {
    *;
}

# Keep the TextToSpeechService base class itself
-keep class android.speech.tts.TextToSpeechService { *; }
-keep class android.speech.tts.TextToSpeech$** { *; }
-keep class android.speech.tts.Voice { *; }
-keep class android.speech.tts.SynthesisCallback { *; }
-keep class android.speech.tts.SynthesisRequest { *; }

# ── TTS Engine Activities (required by Android TTS framework) ──
# These activities handle CHECK_TTS_DATA, INSTALL_TTS_DATA, GET_SAMPLE_TEXT
# intents. Android resolves them by package name + intent filter, so they
# must not be renamed or stripped.
-keep public class com.aitorpazos.pipertts.ui.CheckVoiceData { *; }
-keep public class com.aitorpazos.pipertts.ui.InstallVoiceData { *; }
-keep public class com.aitorpazos.pipertts.ui.GetSampleText { *; }
-keep public class com.aitorpazos.pipertts.ui.TtsSettingsActivity { *; }
-keep public class com.aitorpazos.pipertts.ui.MainActivity { *; }
-keep public class com.aitorpazos.pipertts.ui.VoiceListActivity { *; }

# ── Application class ────────────────────────────────────
-keep public class com.aitorpazos.pipertts.PiperTtsApplication { *; }

# ── Engine and utility classes ────────────────────────────
-keep class com.aitorpazos.pipertts.engine.** { *; }
-keep class com.aitorpazos.pipertts.util.** { *; }

# ── Download manager data classes ─────────────────────────
-keep class com.aitorpazos.pipertts.download.** { *; }
-keepclassmembers class com.aitorpazos.pipertts.download.** {
    <init>(...);
    <fields>;
    <methods>;
}

# ── org.json (Android built-in, but keep references) ─────
-dontwarn org.json.**
