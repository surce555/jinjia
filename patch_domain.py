import re

file_path = 'app/src/main/java/com/example/jinjia/GoldRepository.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    '"https://jinjia.suziqi1994.workers.dev"',
    '"https://jinjia.lingchenyidianban.site"'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to GoldRepository.kt")
