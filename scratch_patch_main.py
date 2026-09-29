import re

def main():
    file_path = r"d:\Portable software\ai\jinjia\app\src\main\java\com\example\jinjia\MainActivity.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Update updateMainDashboardChart
    old_update_chart = """    private fun updateMainDashboardChart(target: GoldItem) {
        val cleanTargetName = target.title
            .removePrefix("[实时] ")
            .removePrefix("[银行] ")
            .removePrefix("[金店] ")
            .removePrefix("[大盘] ")
            .removePrefix("[回收] ")

        binding.tvLegendTarget.text = "● $cleanTargetName"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 并发拉取当前选中的盯盘标的与基准国际伦敦金分时走势
                val targetJob = async { GoldRepository.fetchIntradayChart(target.id) }
                val londonJob = async { GoldRepository.fetchIntradayChart("realtime_gj") }
                val targetPoints = targetJob.await()
                val londonPoints = londonJob.await()

                mainDashboardChartPoints = targetPoints
                mainDashboardLondonPoints = londonPoints

                withContext(Dispatchers.Main) {
                    binding.chartMainDashboard.setCompareChartData(
                        primaryPoints = targetPoints,
                        primaryTitle = cleanTargetName,
                        primaryUnit = target.unit,
                        secondaryPoints = londonPoints,
                        secondaryTitle = "国际伦敦金",
                        secondaryUnit = "美元/盎司"
                    )
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "updateMainDashboardChart error: ${e.message}")
            }
        }
    }"""
    
    new_update_chart = """    private fun updateMainDashboardChart(target: GoldItem) {
        val cleanTargetName = target.title
            .removePrefix("[实时] ")
            .removePrefix("[银行] ")
            .removePrefix("[金店] ")
            .removePrefix("[大盘] ")
            .removePrefix("[回收] ")

        val tabIndex = binding.tabChartTimeframe.selectedTabPosition
        val isRealtime = tabIndex == 0
        val chartMode = if (isRealtime) 0 else 1

        val timeframeStr = when (tabIndex) {
            1 -> "D1"
            2 -> "W1"
            3 -> "M1"
            else -> "M5"
        }

        binding.tvLegendTarget.visibility = if (isRealtime) View.VISIBLE else View.GONE
        binding.tvLegendTarget.text = "● $cleanTargetName"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isRealtime) {
                    val targetJob = async { GoldRepository.fetchIntradayChart(target.id) }
                    val londonJob = async { GoldRepository.fetchIntradayChart("realtime_gj") }
                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", "M5") }
                    
                    val targetPoints = targetJob.await()
                    val londonPoints = londonJob.await()
                    val dxyPoints = dxyJob.await()

                    mainDashboardChartPoints = targetPoints
                    mainDashboardLondonPoints = londonPoints

                    withContext(Dispatchers.Main) {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = targetPoints,
                            primaryTitle = cleanTargetName,
                            primaryUnit = target.unit,
                            secondaryPoints = londonPoints,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = dxyPoints,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 0
                        )
                    }
                } else {
                    val londonJob = async { GoldRepository.fetchBiquoteOHLC("XAUUSD", timeframeStr) }
                    val dxyJob = async { GoldRepository.fetchBiquoteOHLC("DXY", timeframeStr) }
                    
                    val londonPoints = londonJob.await()
                    val dxyPoints = dxyJob.await()

                    withContext(Dispatchers.Main) {
                        binding.chartMainDashboard.setCompareChartData(
                            primaryPoints = emptyList(),
                            primaryTitle = "",
                            primaryUnit = "",
                            secondaryPoints = londonPoints,
                            secondaryTitle = "伦敦金",
                            secondaryUnit = "美元/盎司",
                            thirdPoints = dxyPoints,
                            thirdTitle = "美元指数",
                            thirdUnit = "",
                            chartMode = 1
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "updateMainDashboardChart error: ${e.message}")
            }
        }
    }"""
    if old_update_chart in content:
        content = content.replace(old_update_chart, new_update_chart)
    else:
        print("Could not find old_update_chart!")

    # 2. Add tabChartTimeframe listener in initViews()
    if "binding.tabChartTimeframe.addOnTabSelectedListener" not in content:
        target_str = "binding.btnResetChartZoom.setOnClickListener {\n            binding.chartMainDashboard.resetZoom()\n        }"
        listener_code = """binding.btnResetChartZoom.setOnClickListener {
            binding.chartMainDashboard.resetZoom()
        }

        binding.tabChartTimeframe.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val target = selectedTargetItem ?: allTargetsList.firstOrNull()
                if (target != null) {
                    updateMainDashboardChart(target)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                val target = selectedTargetItem ?: allTargetsList.firstOrNull()
                if (target != null) {
                    updateMainDashboardChart(target)
                }
            }
        })"""
        content = content.replace(target_str, listener_code)

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)
        
    print("MainActivity patched successfully.")

if __name__ == "__main__":
    main()
