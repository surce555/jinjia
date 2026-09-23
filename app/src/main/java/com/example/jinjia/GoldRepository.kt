package com.example.jinjia

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 统一网络请求与数据解析仓储类
 * 供 MainActivity 与 GoldPriceService 共享调用
 */
object GoldRepository {
    private const val TAG = "GoldRepository"

    private const val API_URL_PRIMARY = "https://tmini.net/api/gold-price"
    private const val API_URL_SECONDARY = "https://tmini.net/api/gold-price?type=json"

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/120.0.0.0"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 发起网络请求并解析金价数据
     * @return Pair<金价列表, 原始响应JSON字符串>
     * @throws Exception 当网络失败、限流、或数据为空时抛出具体异常
     */
    suspend fun fetchGoldData(): Pair<List<GoldItem>, String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (url in listOf(API_URL_PRIMARY, API_URL_SECONDARY)) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val code = response.code
                val bodyString = response.body?.string()

                if (!response.isSuccessful) {
                    throw Exception("HTTP $code 错误: ${response.message}")
                }

                if (bodyString.isNullOrBlank()) {
                    throw Exception("接口返回空内容")
                }

                val items = parseJsonToGoldItems(bodyString)
                if (items.isNotEmpty()) {
                    return@withContext Pair(items, bodyString)
                } else {
                    throw Exception("解析后无有效行情条目")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Request to $url failed: ${e.message}")
            }
        }

        throw lastException ?: Exception("网络请求失败")
    }

    /**
     * 严谨健壮的 JSON 解析逻辑（双重判断包裹 + 纯字符串转换数值防闪退）
     */
    fun parseJsonToGoldItems(jsonString: String): List<GoldItem> {
        val list = mutableListOf<GoldItem>()
        if (jsonString.isBlank()) return list

        val rawObj = JSONObject(jsonString)

        // 检查业务错误码（如 219 频率超限）
        if (rawObj.has("code") && rawObj.optInt("code") != 200) {
            val msg = rawObj.optString("msg", "业务异常")
            throw Exception("接口提示: $msg")
        }

        // 双重判断：是否被包裹在 data 节点内部
        val root: JSONObject = if (rawObj.has("data") && rawObj.opt("data") is JSONObject) {
            rawObj.getJSONObject("data")
        } else {
            rawObj
        }

        // 1. metals (大盘贵金属)
        val metalsArray = root.optJSONArray("metals")
        if (metalsArray != null) {
            for (i in 0 until metalsArray.length()) {
                val item = metalsArray.optJSONObject(i) ?: continue
                val name = item.optString("name", "大盘金价").trim()
                val priceStr = (item.opt("sell_price") ?: item.opt("today_price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "metals_${name}",
                        category = GoldDataParser.CAT_METALS,
                        title = name,
                        subtitle = "大盘卖出价",
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 2. banks (各大银行)
        val banksArray = root.optJSONArray("banks")
        if (banksArray != null) {
            for (i in 0 until banksArray.length()) {
                val item = banksArray.optJSONObject(i) ?: continue
                val bank = item.optString("bank", "银行").trim()
                val product = item.optString("product", "投资金条").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "banks_${bank}_${product}",
                        category = GoldDataParser.CAT_BANKS,
                        title = bank,
                        subtitle = product,
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 3. stores (品牌金店)
        val storesArray = root.optJSONArray("stores")
        if (storesArray != null) {
            for (i in 0 until storesArray.length()) {
                val item = storesArray.optJSONObject(i) ?: continue
                val brand = item.optString("brand", "金店品牌").trim()
                val product = item.optString("product", "黄金").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "stores_${brand}_${product}",
                        category = GoldDataParser.CAT_STORES,
                        title = brand,
                        subtitle = product,
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 4. recycle (黄金回收)
        val recycleArray = root.optJSONArray("recycle")
        if (recycleArray != null) {
            for (i in 0 until recycleArray.length()) {
                val item = recycleArray.optJSONObject(i) ?: continue
                val type = item.optString("type", "黄金回收").trim()
                val purity = item.optString("purity", "").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "recycle_${type}",
                        category = GoldDataParser.CAT_RECYCLE,
                        title = type,
                        subtitle = if (purity.isNotBlank()) "成色: $purity" else "黄金回收",
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        return list
    }
}
