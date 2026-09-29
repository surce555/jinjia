import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'cachedLondon = pts; updateKlineCache()',
    'cachedLondon = pts; withContext(Dispatchers.Main) { updateKlineCache() }'
)

content = content.replace(
    'cachedDxy = pts; updateKlineCache()',
    'cachedDxy = pts; withContext(Dispatchers.Main) { updateKlineCache() }'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt for Main Thread")
