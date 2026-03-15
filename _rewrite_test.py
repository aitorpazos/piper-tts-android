#!/usr/bin/env python3
import re

path = "piper-tts-android/app/src/test/java/com/aitorpazos/pipertts/TtsSpeechRoundtripTest.kt"

with open(path, "r") as f:
    content = f.read()

print("Read file:", len(content), "chars")
print("Contains EN_TEXT:", "EN_TEXT" in content)
print("Contains ES_TEXT:", "ES_TEXT" in content)
print("Contains EN_KEYWORDS:", "EN_KEYWORDS" in content)
print("Contains ES_KEYWORDS:", "ES_KEYWORDS" in content)
