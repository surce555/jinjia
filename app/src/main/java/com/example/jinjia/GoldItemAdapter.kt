package com.example.jinjia

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.jinjia.databinding.ItemGoldPriceBinding

/**
 * 金价列表适配器
 * 仅在【⚡ 实时机构】模块中展示趋势图折叠/展开功能，银行金条/品牌金店/大盘行情/黄金回收模块中隐藏趋势图
 */
class GoldItemAdapter(
    private val onSelectAsTarget: (GoldItem) -> Unit,
    private val onCopyAiPrompt: (GoldItem, List<ChartPoint>?) -> Unit,
    private val onFetchChart: (GoldItem, (List<ChartPoint>) -> Unit) -> Unit
) : ListAdapter<GoldItem, GoldItemAdapter.ViewHolder>(DiffCallback) {

    private val expandedItemIds = mutableSetOf<String>()
    private val chartCache = mutableMapOf<String, List<ChartPoint>>()

    inner class ViewHolder(val binding: ItemGoldPriceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GoldItem) {
            binding.tvItemTitle.text = item.title
            binding.tvItemSubtitle.text = item.subtitle
            binding.tvItemPrice.text = item.priceDisplay
            binding.tvItemTime.text = if (item.updateTime.isNotBlank()) "更新: ${item.updateTime}" else ""

            // 仅实时监控机构保留专属走势图，大盘、银行、金店、回收模块删除趋势图
            val isRealtimeCategory = item.category == GoldDataParser.CAT_REALTIME

            if (isRealtimeCategory) {
                binding.btnToggleChart.visibility = View.VISIBLE
                val isExpanded = expandedItemIds.contains(item.id)
                if (isExpanded) {
                    binding.layoutChartContainer.visibility = View.VISIBLE
                    binding.btnToggleChart.text = "📈 收起"

                    val points = chartCache[item.id]
                    if (points != null) {
                        binding.chartItemTrend.setChartData(points, item.unit)
                    } else {
                        binding.chartItemTrend.setChartData(emptyList(), item.unit)
                        onFetchChart(item) { fetched ->
                            chartCache[item.id] = fetched
                            val targetPos = currentList.indexOfFirst { it.id == item.id }
                            if (targetPos != -1) {
                                notifyItemChanged(targetPos)
                            }
                        }
                    }
                } else {
                    binding.layoutChartContainer.visibility = View.GONE
                    binding.btnToggleChart.text = "📈 走势"
                }

                // 折叠/展开走势图表
                binding.btnToggleChart.setOnClickListener {
                    val targetPos = currentList.indexOfFirst { it.id == item.id }
                    if (targetPos == -1) return@setOnClickListener

                    if (expandedItemIds.contains(item.id)) {
                        expandedItemIds.remove(item.id)
                    } else {
                        expandedItemIds.add(item.id)
                    }
                    notifyItemChanged(targetPos)
                }

                // 专属 AI 量化分析复制
                binding.btnItemAiPrompt.setOnClickListener {
                    onCopyAiPrompt(item, chartCache[item.id])
                }
            } else {
                binding.btnToggleChart.visibility = View.GONE
                binding.layoutChartContainer.visibility = View.GONE
            }

            // 设为盯盘标的
            binding.btnSetMonitor.setOnClickListener {
                onSelectAsTarget(item)
            }
            binding.root.setOnClickListener {
                onSelectAsTarget(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGoldPriceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<GoldItem>() {
        override fun areItemsTheSame(oldItem: GoldItem, newItem: GoldItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: GoldItem, newItem: GoldItem): Boolean {
            return oldItem == newItem
        }
    }
}
