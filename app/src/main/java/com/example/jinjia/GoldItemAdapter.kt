package com.example.jinjia

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.jinjia.databinding.ItemGoldPriceBinding

/**
 * 金价列表适配器
 */
class GoldItemAdapter(
    private val onSelectAsTarget: (GoldItem) -> Unit
) : ListAdapter<GoldItem, GoldItemAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemGoldPriceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GoldItem) {
            binding.tvItemTitle.text = item.title
            binding.tvItemSubtitle.text = item.subtitle
            binding.tvItemPrice.text = item.priceDisplay
            binding.tvItemTime.text = if (item.updateTime.isNotBlank()) "更新: ${item.updateTime}" else ""

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
