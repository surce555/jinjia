import re

def main():
    file_path = r"d:\Portable software\ai\jinjia\app\src\main\java\com\example\jinjia\MainActivity.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # We need to replace the entire copyAiAnalysisPrompt and copyAiAnalysisPromptForItem functions,
    # and add the two builder functions.
    
    # 1. Regex to find the block to replace
    pattern = re.compile(r'    private fun copyAiAnalysisPrompt\(\) \{.*?(?=    /\*\*.*?对高频走势点进行均匀抽样)', re.DOTALL)
    
    new_methods = """    private fun copyAiAnalysisPrompt() {
        val item = selectedTargetItem ?: allTargetsList.firstOrNull() ?: return
        binding.btnCopyAiPrompt.isEnabled = false
        binding.btnCopyAiPrompt.text = "⏳ 正在抓取走势数据..."
        binding.btnDashboardAiPrompt.isEnabled = false
        binding.btnDashboardAiPrompt.text = "⏳ 分析中"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tabIndex = binding.tabChartTimeframe.selectedTabPosition
                val isRealtime = tabIndex == 0
                val timeframeStr = when (tabIndex) {
                    1 -> "1d"
                    2 -> "1w"
                    3 -> "1M"
                    else -> "5m"
                }
                
                val promptText = if (isRealtime) {
                    val targetPts = if (!mainDashboardChartPoints.isNullOrEmpty()) mainDashboardChartPoints!! else GoldRepository.fetchIntradayChart(item.id)
                    val londonPts = if (!mainDashboardLondonPoints.isNullOrEmpty()) mainDashboardLondonPoints!! else GoldRepository.fetchIntradayChart("realtime_gj")
                    val dxyPts = GoldRepository.fetchBiquoteOHLC("DXY", "5m")
                    buildRealtimeAiPrompt(item, targetPts, londonPts, dxyPts)
                } else {
                    val londonPts = GoldRepository.fetchBiquoteOHLC("XAUUSD", timeframeStr)
                    val dxyPts = GoldRepository.fetchBiquoteOHLC("DXY", timeframeStr)
                    val tfLabel = when(tabIndex) { 1->"日K线"; 2->"周K线"; 3->"月K线"; else->"K线" }
                    buildKLineAiPrompt(tfLabel, londonPts, dxyPts)
                }

                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成专业AI多维行情分析提示词，可直接去对话框粘贴！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "copyAiAnalysisPrompt error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "走势拉取提示: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnCopyAiPrompt.isEnabled = true
                    binding.btnCopyAiPrompt.text = "🤖 复制走势给 AI 分析"
                    binding.btnDashboardAiPrompt.isEnabled = true
                    binding.btnDashboardAiPrompt.text = "🤖 AI分析"
                }
            }
        }
    }

    private fun copyAiAnalysisPromptForItem(item: GoldItem, preloadedPoints: List<ChartPoint>?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val chartPoints = if (!preloadedPoints.isNullOrEmpty()) preloadedPoints else GoldRepository.fetchIntradayChart(item.id)
                val promptText = buildRealtimeAiPrompt(item, chartPoints, emptyList(), emptyList())
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成【${item.displayName}】单源提示词！", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "拉取提示: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun buildRealtimeAiPrompt(item: GoldItem, targetPts: List<ChartPoint>, londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>): String {
        val sampled = sampleChartPoints(targetPts, 25)
        val sbPoints = java.lang.StringBuilder()
        val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            sbPoints.append("- %s: %.2f %s\\n".format(timeSdf.format(java.util.Date(tMillis)), pt.price, item.unit))
        }
        
        val extInfo = if (londonPts.isNotEmpty() && dxyPts.isNotEmpty()) {
            "\\n【外盘联动参考】\\n- 伦敦金最新价：$%.2f/盎司\\n- 美元指数最新价：%.2f".format(
                londonPts.lastOrNull()?.price ?: 0.0,
                dxyPts.lastOrNull()?.price ?: 0.0
            )
        } else ""

        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return \"\"\"
你是一名拥有15年经验的贵金属量化交易专家。请根据以下我刚从实盘抓取的【${item.displayName}】今日高频分时走势数据，进行专业技术面剖析与行情预测：

【盘口概况】
- 标的名称：${item.displayName}
- 当前最新价：%.2f %s
- 数据更新时间：$dateStr
$extInfo

【日内分时抽样数据 (时间 -> 价格)】
${sbPoints.toString().trimEnd()}

【请从以下 4 个维度给出深度分析报告】：
1. 短期均线与动量：当前处于拉升、阴跌还是窄幅蓄势震荡？
2. 关键点位研判：测算日内关键的支撑位（买点）与阻力位（压力位）。
3. 盘口多空情绪与风险评估：是否存在诱多/诱空或加速见顶信号？结合美元指数表现进行跨品种分析。
4. 具体实操策略建议：给出明确的激进/稳健做单点位、止损防守位与止盈目标。
\"\"\".trimIndent().format(targetPts.lastOrNull()?.price ?: item.price, item.unit)
    }

    private fun buildKLineAiPrompt(timeframeLabel: String, londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>): String {
        val sampled = if (londonPts.size > 20) londonPts.takeLast(20) else londonPts
        val sbPoints = java.lang.StringBuilder()
        val dateSdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            sbPoints.append("- %s: 开$%.2f 高$%.2f 低$%.2f 收$%.2f\\n".format(
                dateSdf.format(java.util.Date(tMillis)), pt.open, pt.high, pt.low, pt.price
            ))
        }
        
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return \"\"\"
你是一名资深的全球宏观黄金交易策略师。请根据以下我刚从 Biquote 实盘抓取的【伦敦金(XAUUSD)】最近 $timeframeLabel 历史数据，并结合【美元指数(DXY)】进行大周期跨市场研判：

【周期与概况】
- 分析级别：$timeframeLabel
- 数据更新时间：$dateStr
- 美元指数最新节点收盘价：%.2f

【伦敦金 K线序列 (时间 -> 开、高、低、收)】
${sbPoints.toString().trimEnd()}

【请重点从以下维度输出大周期分析报告】：
1. 趋势与形态分析：基于K线实体与影线，判断当前的长期趋势（多头排列/空头压制/箱体宽幅震荡）及潜在反转形态（如双底、头肩顶、启明星）。
2. 宏观美元跷跷板效应：结合当前美元指数的位置，推演未来资金对黄金避险属性或抗通胀属性的定价倾斜。
3. 关键结构性位置：指出具有大级别阻力的“高压区”或“强支撑区”。
4. 中长线布局建议：如果是现货/实物金投资者，当前是否是建仓良机？如果是杠杆交易者，应采取顺势加仓还是逢高沽空的策略？
\"\"\".trimIndent().format(dxyPts.lastOrNull()?.price ?: 0.0)
    }
"""
    
    # We apply the replacement
    new_content = pattern.sub(new_methods, content)
    
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
        
    print("MainActivity AI prompt logic patched successfully.")

if __name__ == "__main__":
    main()
