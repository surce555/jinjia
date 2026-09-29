import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix the literal newline issue in formatKLinePoints
bad_str1 = ')] | 美指[收$dxyPriceStr]\n")'
good_str1 = ')] | 美指[收$dxyPriceStr]\\n")'
content = content.replace(bad_str1, good_str1)

# Fix the literal newline issue in buildMegaAiPrompt
bad_str2 = 'pt.price)}\n")'
good_str2 = 'pt.price)}\\n")'
content = content.replace(bad_str2, good_str2)

# Insert copyAiAnalysisPromptForItem before copyAiAnalysisPrompt
item_prompt_func = """    private fun copyAiAnalysisPromptForItem(item: GoldItem, preloadedPoints: List<ChartPoint>?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "⏳ 正在为【${item.displayName}】构建全维度数据...", Toast.LENGTH_SHORT).show() }
                val londonMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1M") }
                val dxyMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1M") }
                val londonDailyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1d") }
                val dxyDailyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1d") }
                val londonRtJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "5m") }
                val dxyRtJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") }
                val targetRtJob = async { if (!preloadedPoints.isNullOrEmpty()) preloadedPoints else GoldRepository.fetchIntradayChart(item.id) }

                val promptText = buildMegaAiPrompt(
                    item = item,
                    londonMonthly = londonMonthlyJob.await(),
                    dxyMonthly = dxyMonthlyJob.await(),
                    londonDaily = londonDailyJob.await(),
                    dxyDaily = dxyDailyJob.await(),
                    londonRt = londonRtJob.await(),
                    dxyRt = dxyRtJob.await(),
                    targetRt = targetRtJob.await()
                )
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成【${item.displayName}】全维度专业提示词！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {}
        }
    }

"""

if "fun copyAiAnalysisPromptForItem" not in content:
    content = content.replace('    private fun copyAiAnalysisPrompt() {', item_prompt_func + '    private fun copyAiAnalysisPrompt() {')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fix applied to MainActivity.kt")
