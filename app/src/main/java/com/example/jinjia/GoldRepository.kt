package com.example.jinjia

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 统一网络请求与数据解析仓储类
 * 接入双数据源：
 * 1. 数据源 A（各大银行与金融机构高频实时源）：https://jin.20021002.xyz/api.php?type={code}
 * 2. 数据源 B（大盘贵金属与金店/回收日更参考）：https://tmini.net/api/gold-price
 */
object GoldRepository {
    private const val TAG = "GoldRepository"

    private const val SOURCE_A_BASE_URL = "https://jin.20021002.xyz/api.php?type="
    private const val SOURCE_B_URL = "https://tmini.net/api/gold-price"
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/120.0.0.0"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ==================== 数据源 A：高频金融机构实时源 ====================

    /**
     * 单独拉取指定机构的高频实时行情（毫秒级轻量接口，用于高频定向监控）
     */
    suspend fun fetchRealtimeBank(code: String): GoldItem? = withContext(Dispatchers.IO) {
        val url = "$SOURCE_A_BASE_URL$code"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchRealtimeBank $code HTTP error: ${response.code}")
                return@withContext null
            }

            val bodyString = response.body?.string() ?: return@withContext null
            return@withContext parseRealtimeBankJson(code, bodyString)
        } catch (t: Throwable) {
            Log.e(TAG, "fetchRealtimeBank $code exception: ${t.message}", t)
            return@withContext null
        }
    }

    /**
     * 并发拉取所有启用的实时金融机构数据
     */
    suspend fun fetchAllRealtimeBanks(enabledCodes: List<String>): List<GoldItem> = withContext(Dispatchers.IO) {
        if (enabledCodes.isEmpty()) return@withContext emptyList()
        coroutineScope {
            enabledCodes.map { code ->
                async {
                    fetchRealtimeBank(code)
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * 健壮解析实时机构接口返回的数据
     * 支持提取 price, sell_price, buy_price, gold_price 或其他数值型字段
     */
    fun parseRealtimeBankJson(code: String, jsonString: String): GoldItem? {
        return try {
            val root = JSONObject(jsonString)
            val dataObj = if (root.has("data") && root.opt("data") is JSONObject) {
                root.getJSONObject("data")
            } else {
                root
            }

            val config = GoldDataParser.REALTIME_BANKS.find { it.code.equals(code, ignoreCase = true) }
            val name = dataObj.optString("name", config?.name ?: code).trim()
            val currency = dataObj.optString("currency", if (code == "gj") "$" else "¥").trim()
            val unit = if (currency == "$" || code == "gj") "美元/盎司" else "元/克"

            // 容错读取价格字段
            val priceRaw = (dataObj.opt("price")
                ?: dataObj.opt("sell_price")
                ?: dataObj.opt("buy_price")
                ?: dataObj.opt("gold_price")
                ?: "").toString().trim()

            val price = priceRaw.toDoubleOrNull() ?: config?.defaultPrice ?: 0.0
            if (price <= 0.0) return null

            val updateTime = dataObj.optString("update_time", "").ifBlank {
                dataObj.optString("time", "").ifBlank {
                    dataObj.optString("updated", "")
                }
            }

            val change = dataObj.optDouble("change", 0.0)
            val changePct = dataObj.optDouble("change_pct", 0.0)
            val subtitle = if (change != 0.0) {
                val sign = if (change > 0) "+" else ""
                "实时报价 (${sign}%.2f / ${sign}%.2f%%)".format(change, changePct)
            } else {
                "高频实时源 (${config?.symbol ?: code.uppercase()})"
            }

            GoldItem(
                id = "realtime_$code",
                category = GoldDataParser.CAT_REALTIME,
                title = "[实时] $name",
                subtitle = subtitle,
                price = price,
                unit = unit,
                updateTime = updateTime.ifBlank { "实时" }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "parseRealtimeBankJson for $code failed: ${t.message}", t)
            null
        }
    }

    // ==================== 数据源 B：综合大盘/金店/回收参考源 ====================

    /**
     * 发起网络请求并解析综合金价数据
     * HTTP 200 且解析出 JSON 即视为成功，无任何非 0 字段拦截
     */
    suspend fun fetchGoldData(): Pair<List<GoldItem>, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SOURCE_B_URL)
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
