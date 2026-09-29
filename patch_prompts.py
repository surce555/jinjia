import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

new_prompts = """
    private fun buildRealtimeAiPrompt(item: GoldItem, targetPts: List<ChartPoint>, londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>): String {
        val sampled = sampleChartPoints(targetPts, 25)
        val sbPoints = java.lang.StringBuilder()
        val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            sbPoints.append("- ${timeSdf.format(java.util.Date(tMillis))}: ${String.format("%.2f", pt.price)} ${item.unit}\\n")
        }
        
        val extInfo = if (londonPts.isNotEmpty() && dxyPts.isNotEmpty()) {
            "\\n【跨市场实时参考】\\n- 伦敦金 (XAUUSD): $${String.format("%.2f", londonPts.lastOrNull()?.price ?: 0.0)}/盎司\\n- 美元指数 (DXY): ${String.format("%.2f", dxyPts.lastOrNull()?.price ?: 0.0)}"
        } else ""

        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return \"\"\"
# Role
你是一位拥有20年实战经验的顶尖全球宏观黄金分析师与量化交易策略师。

# Task
请基于我刚刚抓取的【${item.displayName}】高频实时走势数据，为我提供一份专业、详尽、具备实操价值的“日内微观交易研报”。

# Context
## 标的基础信息
- **监控标的**: ${item.displayName}
- **当前最新价**: ${String.format("%.2f", targetPts.lastOrNull()?.price ?: item.price)} ${item.unit}
- **数据提取时间**: $dateStr$extInfo

## 日内分时抽样数据 (时间 -> 价格)
${sbPoints.toString().trimEnd()}

# Output Format (请严格按照以下工程化结构输出研报)
1. 📊 **微观盘面拆解**：基于上述分时价格序列，计算日内振幅，判断当前的日内动量方向（例如：窄幅震荡、单边逼空、瀑布式下杀）。
2. 🔗 **跨市场联动定性**：结合给定的伦敦金与美元指数最新报价，分析内外盘溢价/折价情况，以及美元指数日内强弱对该黄金标的构成的支撑或压制效力。
3. 🎯 **关键支撑与阻力位**：通过高低点测算，给出日内防守位（支撑位）和进攻位（阻力位）。
4. 💡 **交易策略建议**：
   - 针对【纸黄金/投资金条】长线持有者：当前是否为绝佳的买入/分批建仓节点？
   - 针对【高频日内】交易者：给出明确的做多/做空倾向，以及严格的止盈止损空间预期。
\"\"\".trimIndent()
    }

    private fun buildKLineAiPrompt(timeframeLabel: String, londonPts: List<ChartPoint>, dxyPts: List<ChartPoint>): String {
        val sampleSize = 30
        val sampled = if (londonPts.size > sampleSize) londonPts.takeLast(sampleSize) else londonPts
        val sampledDxy = if (dxyPts.size > sampleSize) dxyPts.takeLast(sampleSize) else dxyPts
        
        val sbPoints = java.lang.StringBuilder()
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        
        // 尝试对齐 DXY 数据
        sampled.forEach { pt ->
            val tMillis = if (pt.timestamp < 100_000_000_000L) pt.timestamp * 1000L else pt.timestamp
            val date = dateSdf.format(java.util.Date(tMillis))
            val matchDxy = sampledDxy.minByOrNull { kotlin.math.abs(it.timestamp - pt.timestamp) }
            val dxyPriceStr = if (matchDxy != null) String.format("%.2f", matchDxy.price) else "N/A"
            sbPoints.append("- $date: 黄金[开$${String.format("%.2f", pt.open)} 高$${String.format("%.2f", pt.high)} 低$${String.format("%.2f", pt.low)} 收$${String.format("%.2f", pt.price)}] | 美指[收$dxyPriceStr]\\n")
        }
        
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return \"\"\"
# Role
你是一位主攻大宗商品及贵金属领域的华尔街首席宏观策略师，精通技术形态学（Price Action）与跨资产量化相关性分析。

# Task
请基于以下最新获取的伦敦金(XAUUSD)与美元指数(DXY)的【$timeframeLabel】历史级别 K 线数据，输出一份深度的“中长线宏观趋势研报”。

# Context
## 数据基础
- **分析级别**: $timeframeLabel
- **数据更新时间**: $dateStr

## 历史 K 线序列 (近期 ${sampled.size} 根 K 线，包含黄金 OHLC 与 同期美元指数收盘价)
${sbPoints.toString().trimEnd()}

# Output Format (请严格按照以下工程化结构输出研报)
1. 📈 **趋势与形态学诊断**：
   - 根据黄金的 OHLC 序列，识别当前的主力趋势（上升通道、下降楔形、高位横盘等）。
   - 指出近期是否出现了关键的 K 线反转形态（如吞没形态、长下影线探底、双顶/双底等）。
2. 🔄 **金美跷跷板效应分析**：
   - 深度对比给出的黄金与同期美元指数（DXY）走势序列，评估两者的负相关性是否出现钝化或背离（例如：美元涨黄金也涨的极端避险情绪）。
3. 🧱 **宏观筹码密集区**：
   - 框定未来的强阻力区间与铁底支撑区间（给出具体的整数关口或技术位）。
4. 💰 **中长线资产配置指令**：
   - 对于【现货囤金/实物金】投资者，给出未来 1~3 个周期的资产配置比例建议。
   - 对于【黄金期货/纸黄金】波段交易者，制定一份左侧摸顶或右侧追随的详细交易计划，包含仓位管理建议。
\"\"\".trimIndent()
    }
"""

content = re.sub(
    r'private fun buildRealtimeAiPrompt.*?\}\n\n    private fun buildKLineAiPrompt.*?\}\n',
    new_prompts + '\n',
    content,
    flags=re.DOTALL
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt for AI Prompts")
