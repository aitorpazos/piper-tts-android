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
-keep class com.aitorpazos.pipertts.service.PiperTtsService { *; }

# ── TTS Engine Activities (required by Android TTS framework) ──
-keep class com.aitorpazos.pipertts.ui.CheckVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.InstallVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.GetSampleText { *; }

# ── Download manager data classes ─────────────────────────
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$* { *; }
-keepclassmembers class com.aitorpazos.pipertts.download.VoiceDownloadManager$* {
    <init>(...);
    <fields>;
}
