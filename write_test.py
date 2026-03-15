
#!/usr/bin/env python3
import os

target = 'piper-tts-android/app/src/test/java/com/aitorpazos/pipertts/TtsSpeechRoundtripTest.kt'
print(f'Will write to {target}')
print(f'File exists: {os.path.exists(target)}')
