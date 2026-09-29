import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'cachedDxy = pts\n                        // Note: target and london might not be ready yet, so it will wait until they are.\n                    } }',
    'cachedDxy = pts\n                        withContext(Dispatchers.Main) { updateRealtimeCache() }\n                    } }'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt for updateRealtimeCache")
