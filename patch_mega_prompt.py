import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

new_logic = """
    private fun copyAiAnalysisPrompt() {
        val item = selectedTargetItem ?: allTargetsList.firstOrNull() ?: return
        binding.btnCopyAiPrompt.isEnabled = false
        binding.btnCopyAiPrompt.text = "⏳ 正在构建全维度多周期数据..."
        binding.btnDashboardAiPrompt.isEnabled = false
        binding.btnDashboardAiPrompt.text = "⏳ 分析中"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val londonMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1M") }
                val dxyMonthlyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1M") }
                
                val londonDailyJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "1d") }
                val dxyDailyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "1d") }
                
                val londonRtJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", "5m") }
                val dxyRtJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") }
                
                val targetRtJob = async { GoldRepository.fetchIntradayChart(item.id) }

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
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gold AI Analysis Prompt", promptText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已生成【宏观->微观】多维度专业研报提示词！", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "copyAiAnalysisPrompt error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "拉取大周期数据提示: ${t.message}", Toast.LENGTH_SHORT).show()
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

    private fun formatKLinePoints(londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>, maxCount: Int, isMonth: Boolean): String {
        val sampled = if (londonPts.size > maxCount) londonPts.takeLast(maxCount) else londonPts
        val sampledDxy = if (dxyPts.size > maxCount) dxyPts.takeLast(maxCount) else dxyPts
        val sb = java.lang.StringBuilder()
        val formatStr = if (isMonth) "yyyy-MM" else "MM-dd"
        val dateSdf = java.text.SimpleDateFormat(formatStr, java.util.Locale.getDefault())
        
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            val date = dateSdf.format(java.util.Date(tMillis))
            val matchDxy = sampledDxy.minByOrNull { kotlin.math.abs(it.timestamp - pt.timestamp) }
            val dxyPriceStr = if (matchDxy != null) String.format("%.2f", matchDxy.price) else "N/A"
            sb.append("- $date: 黄金[开$${String.format("%.2f", pt.open)} 高$${String.format("%.2f", pt.high)} 低$${String.format("%.2f", pt.low)} 收$${String.format("%.2f", pt.price)}] | 美指[收$dxyPriceStr]\\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildMegaAiPrompt(
        item: GoldItem,
        londonMonthly: List<ChartPoint>, dxyMonthly: List<ChartPoint>,
        londonDaily: List<ChartPoint>, dxyDaily: List<ChartPoint>,
        londonRt: List<ChartPoint>, dxyRt: List<ChartPoint>,
        targetRt: List<ChartPoint>
    ): String {
        val monthlyStr = formatKLinePoints(londonMonthly, dxyMonthly, 15, true)
        val dailyStr = formatKLinePoints(londonDaily, dxyDaily, 20, false)
        
        val sampledRt = sampleChartPoints(targetRt, 15)
        val rtSb = java.lang.StringBuilder()
        val timeSdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sampledRt.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            rtSb.append("- ${timeSdf.format(java.util.Date(tMillis))}: ${String.format("%.2f", pt.price)}\\n")
        }
        
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        
        return \"\"\"
# Role
你是一位拥有20年实战经验的华尔街大宗商品首席宏观策略师与贵金属量化交易专家。你精通“宏观大趋势研判 -> 中期波段结构 -> 日内微观盘口”的自上而下(Top-Down)工程化分析框架，并熟练运用美元指数(DXY)作为黄金定价的核心锚点进行跨资产联动定性。

# Task
请基于我刚刚抓取的多周期（月K、日K、今日分时）数据，为我出具一份“自上而下、分步递进”的黄金行情综合研报。当前重点操作标的为：【${item.displayName}】。

# Context (数据提取时间：$dateStr)
## 第一梯队：宏观大趋势 (最近15个月的 月K线 黄金与美指收盘对比)
$monthlyStr

## 第二梯队：中期波段形态 (最近20个交易日 日K线 黄金与美指对比)
$dailyStr

## 第三梯队：日内微观盘口 (【${item.displayName}】今日分时抽样，单位：${item.unit})
${rtSb.toString().trimEnd()}
*(参考：今日外盘伦敦金最新价 $${String.format("%.2f", londonRt.lastOrNull()?.price ?: 0.0)}/盎司，美元指数最新 ${String.format("%.2f", dxyRt.lastOrNull()?.price ?: 0.0)})*

# Output Format (请严格按照以下步骤分段输出，展现专业性)
### 第一步：大趋势定调 (Macro Trend)
通过月K线级别的黄金与美元指数负相关性钝化/强化程度，判断当前黄金处于宏观上的什么周期（如：战略性主升浪、高位宽幅洗盘、熊市下跌通道），为整体操作方向定调（战略看多/看空）。

### 第二步：中期形态与关键位 (Medium-Term Structure)
通过日K线走势，识别当前是否出现了关键的反转或中继形态（如楔形突破、吞没形态等）。给出下方铁底支撑区间与上方强阻力位。

### 第三步：今日日内博弈研判 (Intraday Micro-Action)
结合【${item.displayName}】的分时走势、日内振幅以及今日外盘/美指的最新状态，判断今天的多空力量对比（例如：窄幅震荡、单边逼空、诱多杀跌）。

### 第四步：实操交易指令 (Actionable Plan)
1. **长线现货/实物囤金者**：当前是否为左侧定投/重仓买入的绝佳节点？
2. **中短线/日内杠杆交易者**：给出明确的右侧跟进策略、做单点位（入场价）、防守底线（止损价）及目标利润空间（止盈价）。
\"\"\".trimIndent()
    }

    private fun sampleChartPoints(points: List<ChartPoint>, targetCount: Int = 36): List<ChartPoint> {
        if (points.size <= targetCount) return points
        val result = mutableListOf<ChartPoint>()
        val step = points.size.toDouble() / (targetCount - 1)
        for (i in 0 until targetCount - 1) {
            val index = (i * step).toInt().coerceIn(0, points.size - 1)
            result.add(points[index])
        }
        result.add(points.last())
        return result
    }
"""

content = re.sub(
    r'private fun copyAiAnalysisPrompt.*?fun showDisplaySettingsDialog',
    new_logic.strip() + '\n\n    private fun showDisplaySettingsDialog',
    content,
    flags=re.DOTALL
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt for Mega Prompt")
