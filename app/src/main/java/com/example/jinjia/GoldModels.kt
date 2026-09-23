package com.example.jinjia

import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一金价单项条目模型
 */
data class GoldItem(
    val id: String,          // 唯一标识，如 "realtime_icbc", "banks_工商银行_如意金条"
    val category: String,    // 分类：realtime, banks, stores, metals, recycle
    val title: String,       // 格式化展示名称，如 "[实时] 工商银行", "[银行] 工商银行 - 如意金条"
    val subtitle: String,    // 产品/子名称，如 "高频实时源", "如意金条"
    val price: Double,       // 当前最新价格
    val unit: String,        // 价格单位，如 "元/克", "美元/盎司"
    val updateTime: String   // 更新时间，如 "17:08:22", "2026-09-23 11:09:41"
) {
    val displayName: String
        get() = title

    val priceDisplay: String
        get() {
            val symbol = if (unit.contains("美元") || unit.contains("$") || id == "realtime_gj") "$" else "¥"
            return "$symbol %.2f %s".format(price, unit)
        }
}

/**
 * 实时金融机构配置模型
 */
data class RealtimeBankConfig(
    val code: String,
    val name: String,
    val defaultPrice: Double,
    val unit: String = "元/克",
    val symbol: String = ""
)

object GoldDataParser {
    const val CAT_REALTIME = "realtime"
    const val CAT_BANKS = "banks"
    const val CAT_STORES = "stores"
    const val CAT_METALS = "metals"
    const val CAT_RECYCLE = "recycle"

    // 7家高频金融机构清单
    val REALTIME_BANKS = listOf(
        RealtimeBankConfig("icbc", "工商银行", 932.33, "元/克", "ICBC"),
        RealtimeBankConfig("zs", "浙商银行", 932.64, "元/克", "CZB"),
        RealtimeBankConfig("ms", "民生银行", 932.17, "元/克", "CMBC"),
        RealtimeBankConfig("cgb", "广发银行", 932.20, "元/克", "CGB"),
        RealtimeBankConfig("cib", "兴业银行", 932.77, "元/克", "CIB"),
        RealtimeBankConfig("jd", "京东黄金", 932.64, "元/克", "JD"),
        RealtimeBankConfig("gj", "国际伦敦金", 4318.27, "美元/盎司", "XAU")
    )

    // 默认高频实时银行预置列表（排在最前列）
    val DEFAULT_REALTIME_BANKS = REALTIME_BANKS.map { config ->
        GoldItem(
            id = "realtime_${config.code}",
            category = CAT_REALTIME,
            title = "[实时] ${config.name}",
            subtitle = "高频实时源 (${config.symbol})",
            price = config.defaultPrice,
            unit = config.unit,
            updateTime = "实时预置"
        )
    }

    // 默认大盘、金店、回收预置列表
    val DEFAULT_MARKET_TARGETS = listOf(
        GoldItem("metals_今日金价", CAT_METALS, "[大盘] 今日金价", "大盘现货", 936.0, "元/克", "预置"),
        GoldItem("metals_黄金价格", CAT_METALS, "[大盘] 黄金价格", "大盘现货", 936.0, "元/克", "预置"),
        GoldItem("banks_工商银行_如意金条", CAT_BANKS, "[银行] 工商银行 - 如意金条", "如意金条", 959.05, "元/克", "预置"),
        GoldItem("banks_中国银行_投资金条", CAT_BANKS, "[银行] 中国银行 - 投资金条", "投资金条", 953.53, "元/克", "预置"),
        GoldItem("banks_建设银行_投资金条", CAT_BANKS, "[银行] 建设银行 - 投资金条", "投资金条", 958.50, "元/克", "预置"),
        GoldItem("stores_周大福_黄金", CAT_STORES, "[金店] 周大福 - 黄金", "饰品金条", 1310.0, "元/克", "预置"),
        GoldItem("stores_老凤祥_黄金", CAT_STORES, "[金店] 老凤祥 - 黄金", "饰品金条", 1317.0, "元/克", "预置"),
        GoldItem("recycle_黄金回收", CAT_RECYCLE, "[回收] 黄金回收", "高价回收", 921.0, "元/克", "预置")
    )

    // 默认兜底常用预置列表（高频实时源置顶，保证无网时首项必为工商银行实时源）
    val DEFAULT_TARGETS = DEFAULT_REALTIME_BANKS + DEFAULT_MARKET_TARGETS

    /**
     * 将 GoldItem 列表序列化为 JSON 字符串
     */
    fun serializeItems(items: List<GoldItem>): String {
        return try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("category", item.category)
                obj.put("title", item.title)
                obj.put("subtitle", item.subtitle)
                obj.put("price", item.price)
                obj.put("unit", item.unit)
                obj.put("updateTime", item.updateTime)
                array.put(obj)
            }
            array.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 将 JSON 字符串反序列化为 GoldItem 列表
     */
    fun deserializeItems(jsonString: String): List<GoldItem> {
        if (jsonString.isBlank()) return emptyList()
        val list = mutableListOf<GoldItem>()
        try {
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    list.add(
                        GoldItem(
                            id = id,
                            category = obj.optString("category", CAT_METALS),
                            title = obj.optString("title", id),
                            subtitle = obj.optString("subtitle", ""),
                            price = obj.optDouble("price", 0.0),
                            unit = obj.optString("unit", "元/克"),
                            updateTime = obj.optString("updateTime", "")
                        )
                    )
                }
            } else if (trimmed.startsWith("{")) {
                // 兼容 tmini 原始返回格式
                return GoldRepository.parseJsonToGoldItems(trimmed)
            }
        } catch (_: Exception) {}
        return list
    }

    fun parseJson(jsonString: String): List<GoldItem> {
        val deserialized = deserializeItems(jsonString)
        if (deserialized.isNotEmpty()) return deserialized
        return try {
            GoldRepository.parseJsonToGoldItems(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
