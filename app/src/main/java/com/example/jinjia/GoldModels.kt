package com.example.jinjia

/**
 * 统一金价单项条目模型
 */
data class GoldItem(
    val id: String,          // 唯一标识，如 "banks_工商银行_如意金条"
    val category: String,    // 分类：metals (大盘), banks (银行), stores (金店), recycle (回收)
    val title: String,       // 格式化展示名称，如 "[银行] 工商银行 - 如意金条"
    val subtitle: String,    // 产品/子名称，如 "如意金条"
    val price: Double,       // 当前最新价格
    val unit: String,        // 价格单位，如 "元/克"
    val updateTime: String   // 更新时间，如 "2026-09-23 11:09:41"
) {
    val displayName: String
        get() = title
}

object GoldDataParser {
    const val CAT_METALS = "metals"
    const val CAT_BANKS = "banks"
    const val CAT_STORES = "stores"
    const val CAT_RECYCLE = "recycle"

    // 默认兜底常用预置列表（即使无网或未拉取，Spinner 与页面也绝不为空白）
    val DEFAULT_TARGETS = listOf(
        GoldItem("metals_今日金价", CAT_METALS, "[大盘] 今日金价", "大盘现货", 936.0, "元/克", "预置"),
        GoldItem("metals_黄金价格", CAT_METALS, "[大盘] 黄金价格", "大盘现货", 936.0, "元/克", "预置"),
        GoldItem("banks_工商银行_如意金条", CAT_BANKS, "[银行] 工商银行 - 如意金条", "如意金条", 959.05, "元/克", "预置"),
        GoldItem("banks_中国银行_投资金条", CAT_BANKS, "[银行] 中国银行 - 投资金条", "投资金条", 953.53, "元/克", "预置"),
        GoldItem("banks_建设银行_投资金条", CAT_BANKS, "[银行] 建设银行 - 投资金条", "投资金条", 958.50, "元/克", "预置"),
        GoldItem("stores_周大福_黄金", CAT_STORES, "[金店] 周大福 - 黄金", "饰品金条", 1310.0, "元/克", "预置"),
        GoldItem("stores_老凤祥_黄金", CAT_STORES, "[金店] 老凤祥 - 黄金", "饰品金条", 1317.0, "元/克", "预置"),
        GoldItem("recycle_黄金回收", CAT_RECYCLE, "[回收] 黄金回收", "高价回收", 921.0, "元/克", "预置")
    )

    fun parseJson(jsonString: String): List<GoldItem> {
        return try {
            GoldRepository.parseJsonToGoldItems(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
