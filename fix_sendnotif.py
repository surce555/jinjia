import re

kt_path = 'app/src/main/java/com/example/jinjia/GoldPriceService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

kt = kt.replace('CHANNEL_ID_ALERTS', 'CHANNEL_ALERT_ID')
kt = kt.replace('ic_launcher_foreground', 'ic_gold')

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Fixed channel ID and icon")
