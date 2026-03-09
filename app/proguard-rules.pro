# Piper TTS Android - ProGuard Rules

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.aitorpazos.pipertts.model.** { *; }
-keep class com.aitorpazos.pipertts.download.VoiceDownloadManager$* { *; }

# Keep TTS Service
-keep class com.aitorpazos.pipertts.service.PiperTtsService { *; }
