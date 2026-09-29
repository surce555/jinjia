import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldRepository.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

kt = kt.replace('val url = "$base/api/$symbol/ohlc?interval=$actualInterval"',
                'val limitParam = if (interval == "1w" || interval == "1M") "&limit=600" else ""\n        val url = "$base/api/$symbol/ohlc?interval=$actualInterval$limitParam"')

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Applied limit param")
