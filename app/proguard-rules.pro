# Piper TTS Android - ProGuard / R8 Rules

# ── ONNX Runtime ──────────────────────────────────────────
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Gson ──────────────────────────────────────────────────
# Keep generic signatures used by TypeToken (critical for R8)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep TypeToken and its sub-classes so Gson can read generic types at runtime
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep all model/data classes that Gson deserializes
-keep class com.aitorpazos.pipertts.model.** { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$* { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$CatalogVoice { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$Language { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$FileInfo { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$DownloadableVoice { *; }

# Keep fields annotated with @SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── TTS Service ───────────────────────────────────────────
-keep class com.aitorpazos.pipertts.service.PiperTtsService { *; }

# ── TTS Engine Activities (required by Android TTS framework) ──
-keep class com.aitorpazos.pipertts.ui.CheckVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.InstallVoiceData { *; }
-keep class com.aitorpazos.pipertts.ui.GetSampleText { *; }

# ── Kotlin data classes used with Gson ────────────────────
# Prevent R8 from removing no-arg constructors or fields
-keepclassmembers class com.aitorpazos.pipertts.model.** {
    <init>(...);
    <fields>;
}
-keepclassmembers class com.aitorpazos.pipertts.download.VoiceDownloadManager$* {
    <init>(...);
    <fields>;
}
