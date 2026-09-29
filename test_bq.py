import urllib.request, urllib.error
req = urllib.request.Request('https://jinjia.lingchenyidianban.site/api/XAUUSD/ohlc?interval=1W', headers={'User-Agent': 'Mozilla/5.0'})
try:
    print(len(urllib.request.urlopen(req).read()))
except urllib.error.HTTPError as e:
    print(e.read().decode())
