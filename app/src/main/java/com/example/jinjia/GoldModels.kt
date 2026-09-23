package com.example.jinjia

import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * 解析 tmini.net 金价 API 返回的完整 JSON 数据
     */
    fun parseJson(jsonString: String): List<GoldItem> {
        val list = mutableListOf<GoldItem>()
        if (jsonString.isBlank()) return list

        try {
            val root = JSONObject(jsonString)

            // 1. 大盘贵金属 (metals)
            val metalsArray = root.optJSONArray("metals")
            if (metalsArray != null) {
                for (i in 0 until metalsArray.length()) {
                    val item = metalsArray.optJSONObject(i) ?: continue
                    val name = item.optString("name", "大盘金价").trim()
                    val priceStr = item.optString("sell_price").ifEmpty {
                        item.optString("today_price")
                    }
                    val price = priceStr.toDoubleOrNull() ?: continue
                    val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                    val updated = item.optString("updated", "").ifEmpty {
                        item.optString("time", "")
                    }

                    list.add(
                        GoldItem(
                            id = "metals_${name}",
                            category = CAT_METALS,
                            title = name,
                            subtitle = "大盘现货/卖出价",
                            price = price,
                            unit = unit,
                            updateTime = updated
                        )
                    )
                }
            }

            // 2. 银行投资金条 (banks)
            val banksArray = root.optJSONArray("banks")
            if (banksArray != null) {
                for (i in 0 until banksArray.length()) {
                    val item = banksArray.optJSONObject(i) ?: continue
                    val bank = item.optString("bank", "银行").trim()
                    val product = item.optString("product", "投资金条").trim()
                    val priceStr = item.optString("price")
                    val price = priceStr.toDoubleOrNull() ?: continue
                    val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                    val updated = item.optString("updated", "").ifEmpty {
                        item.optString("time", "")
                    }

                    list.add(
                        GoldItem(
                            id = "banks_${bank}_${product}",
                            category = CAT_BANKS,
                            title = bank,
                            subtitle = product,
                            price = price,
                            unit = unit,
                            updateTime = updated
                        )
                    )
                }
            }

            // 3. 品牌金店金价 (stores)
            val storesArray = root.optJSONArray("stores")
            if (storesArray != null) {
                for (i in 0 until storesArray.length()) {
                    val item = storesArray.optJSONObject(i) ?: continue
                    val brand = item.optString("brand", "金店品牌").trim()
                    val product = item.optString("product", "黄金").trim()
                    val priceStr = item.optString("price")
                    val price = priceStr.toDoubleOrNull() ?: continue
                    val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                    val updated = item.optString("updated", "").ifEmpty {
                        item.optString("time", "")
                    }

                    list.add(
                        GoldItem(
                            id = "stores_${brand}_${product}",
                            category = CAT_STORES,
                            title = brand,
                            subtitle = product,
                            price = price,
                            unit = unit,
                            updateTime = updated
                        )
                    )
                }
            }

            // 4. 黄金回收 (recycle)
            val recycleArray = root.optJSONArray("recycle")
            if (recycleArray != null) {
                for (i in 0 until recycleArray.length()) {
                    val item = recycleArray.optJSONObject(i) ?: continue
                    val type = item.optString("type", "黄金回收").trim()
                    val purity = item.optString("purity", "").trim()
                    val priceStr = item.optString("price")
                    val price = priceStr.toDoubleOrNull() ?: continue
                    val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                    val updated = item.optString("updated", "").ifEmpty {
                        item.optString("time", "")
                    }

                    list.add(
                        GoldItem(
                            id = "recycle_${type}",
                            category = CAT_RECYCLE,
                            title = type,
                            subtitle = if (purity.isNotBlank()) "纯度: $purity" else "正规回收",
                            price = price,
                            unit = unit,
                            updateTime = updated
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
