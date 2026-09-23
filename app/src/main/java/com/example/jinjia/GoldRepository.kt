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
 * 彻底移除 code/status/resultCode 校验，HTTP 200 + 有效 JSON 即视为成功
 */
object GoldRepository {
    private const val TAG = "GoldRepository"

    private const val API_URL = "https://tmini.net/api/gold-price"
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
     * HTTP 200 且解析出 JSON 即视为成功，无任何非 0 字段拦截
     */
    suspend fun fetchGoldData(): Pair<List<GoldItem>, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        val code = response.code
        val bodyString = response.body?.string()

        if (!response.isSuccessful) {
            throw Exception("HTTP $code 网络响应异常")
        }

        if (bodyString.isNullOrBlank()) {
            throw Exception("接口返回空响应内容")
        }

        // 解析 JSON 数据
        val items = parseJsonToGoldItems(bodyString)
        return@withContext Pair(items, bodyString)
    }

    /**
     * 健壮解析逻辑：
     * 1. 兼容根对象或者 data 嵌套包裹
     * 2. 严禁校验 code/status/resultCode，只要有数组就解析
     * 3. 严格使用 toString().trim().toDoubleOrNull() ?: 0.0 防闪退
     */
    fun parseJsonToGoldItems(jsonString: String): List<GoldItem> {
        val list = mutableListOf<GoldItem>()
        if (jsonString.isBlank()) return list

        val rawObj = JSONObject(jsonString)

        // 兼容外层嵌套 data
        val root: JSONObject = if (rawObj.has("data") && rawObj.opt("data") is JSONObject) {
            rawObj.getJSONObject("data")
        } else {
            rawObj
        }

        // 1. metals (大盘)
        val metalsArray = root.optJSONArray("metals")
        if (metalsArray != null) {
            for (i in 0 until metalsArray.length()) {
                val item = metalsArray.optJSONObject(i) ?: continue
                val name = item.optString("name", "大盘金价").trim()
                val priceStr = (item.opt("sell_price") ?: item.opt("today_price") ?: item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "metals_${name}",
                        category = GoldDataParser.CAT_METALS,
                        title = "[大盘] $name",
                        subtitle = "大盘现货/卖出价",
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 2. banks (银行)
        val banksArray = root.optJSONArray("banks")
        if (banksArray != null) {
            for (i in 0 until banksArray.length()) {
                val item = banksArray.optJSONObject(i) ?: continue
                val bank = item.optString("bank", "银行").trim()
                val product = item.optString("product", "投资金条").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "banks_${bank}_${product}",
                        category = GoldDataParser.CAT_BANKS,
                        title = "[银行] $bank - $product",
                        subtitle = product,
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 3. stores (金店)
        val storesArray = root.optJSONArray("stores")
        if (storesArray != null) {
            for (i in 0 until storesArray.length()) {
                val item = storesArray.optJSONObject(i) ?: continue
                val brand = item.optString("brand", "金店品牌").trim()
                val product = item.optString("product", "黄金").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "stores_${brand}_${product}",
                        category = GoldDataParser.CAT_STORES,
                        title = "[金店] $brand - $product",
                        subtitle = product,
                        price = price,
                        unit = unit,
                        updateTime = updated
                    )
                )
            }
        }

        // 4. recycle (回收)
        val recycleArray = root.optJSONArray("recycle")
        if (recycleArray != null) {
            for (i in 0 until recycleArray.length()) {
                val item = recycleArray.optJSONObject(i) ?: continue
                val type = item.optString("type", "黄金回收").trim()
                val purity = item.optString("purity", "").trim()
                val priceStr = (item.opt("price") ?: "").toString().trim()
                val price = priceStr.toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue
                val unit = item.optString("unit", "元/克").ifEmpty { "元/克" }
                val updated = (item.opt("updated") ?: item.opt("time") ?: "").toString().trim()

                list.add(
                    GoldItem(
                        id = "recycle_${type}",
                        category = GoldDataParser.CAT_RECYCLE,
                        title = "[回收] $type",
                        subtitle = if (purity.isNotBlank()) "成色: $purity" else "正规回收",
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
