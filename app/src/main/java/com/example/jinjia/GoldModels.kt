package com.example.jinjia

/**
 * 统一金价单项条目模型
 */
data class GoldItem(
    val id: String,          // 唯一标识，如 "banks_工商银行_如意金条"
    val category: String,    // 分类：metals (大盘), banks (银行), stores (金店), recycle (回收)
    val title: String,       // 机构/主名称，如 "工商银行"
    val subtitle: String,    // 产品/子名称，如 "如意金条"
    val price: Double,       // 当前最新价格
    val unit: String,        // 价格单位，如 "元/克"
    val updateTime: String   // 更新时间，如 "2026-09-23 11:09:41"
) {
    val displayName: String
        get() = if (subtitle.isNotBlank() && subtitle != title) "$title - $subtitle" else title
}

object GoldDataParser {
    const val CAT_METALS = "metals"
    const val CAT_BANKS = "banks"
    const val CAT_STORES = "stores"
    const val CAT_RECYCLE = "recycle"

    fun parseJson(jsonString: String): List<GoldItem> {
        return try {
            GoldRepository.parseJsonToGoldItems(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
