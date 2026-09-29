import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'withContext(Dispatchers.Main) { updateRealtimeCache() }',
    'runOnUiThread { updateRealtimeCache() }'
)

content = content.replace(
    'withContext(Dispatchers.Main) { updateKlineCache() }',
    'runOnUiThread { updateKlineCache() }'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied")
