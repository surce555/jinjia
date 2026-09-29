import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = re.sub(r'\] \| 美指\[收\$dxyPriceStr\]\n"\)', r')] | 美指[收$dxyPriceStr]\\n")', content)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Second fix applied")
