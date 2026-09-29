import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

realtime_inject = """                    var cachedDxy = emptyList<ChartPoint>()
                    val updateRealtimeCache = {
                        if (cachedDxy.isNotEmpty() && mainDashboardChartPoints != null && mainDashboardLondonPoints != null) {
                            binding.chartMainDashboard.setCompareChartData(
                                primaryPoints = mainDashboardChartPoints!!,
                                primaryTitle = cleanTargetName,
                                primaryUnit = target.unit,
                                secondaryPoints = mainDashboardLondonPoints!!,
                                secondaryTitle = "伦敦金",
                                secondaryUnit = "美元/盎司",
                                thirdPoints = cachedDxy,
                                thirdTitle = "美元指数",
                                thirdUnit = "",
                                chartMode = 0
                            )
                        }
                    }

                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "5m") { pts ->
                        cachedDxy = pts
                        // Note: target and london might not be ready yet, so it will wait until they are.
                    } }"""

content = re.sub(
    r'val dxyJob = async \{ GoldRepository\.fetchBiquoteOHLC\("DXY", "5m"\) \}',
    realtime_inject,
    content
)

kline_inject = """                    var cachedLondon = emptyList<ChartPoint>()
                    var cachedDxy = emptyList<ChartPoint>()
                    
                    val updateKlineCache = {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = emptyList(),
                            primaryTitle = "",
                            primaryUnit = "",
                            secondaryPoints = cachedLondon,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = cachedDxy,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 1
                        )
                    }

                    val londonJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", timeframeStr) { pts -> 
                        cachedLondon = pts; updateKlineCache()
                    } }
                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", timeframeStr) { pts -> 
                        cachedDxy = pts; updateKlineCache()
                    } }"""

content = re.sub(
    r'val londonJob = async \{ GoldRepository\.fetchBiquoteOHLC\("XAUUSD", timeframeStr\) \}\n\s+val dxyJob = async \{ GoldRepository\.fetchBiquoteOHLC\("DXY", timeframeStr\) \}',
    kline_inject,
    content
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt")
