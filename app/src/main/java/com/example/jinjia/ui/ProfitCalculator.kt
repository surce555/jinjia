package com.example.jinjia.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.jinjia.GoldItem
import com.example.jinjia.GoldPriceService
import com.example.jinjia.databinding.DialogProfitCalculatorBinding
import com.example.jinjia.databinding.ItemPositionBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PositionRecord(
    val id: String,
    val price: Double,
    val amount: Double,
    val targetId: String
)

class PositionAdapter(
    private val records: MutableList<PositionRecord>,
    private val onDelete: (PositionRecord) -> Unit
) : RecyclerView.Adapter<PositionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPositionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPositionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = records[position]
        holder.binding.tvPosPrice.text = String.format("%.2f", item.price)
        holder.binding.tvPosAmount.text = String.format("%.2f", item.amount)
        holder.binding.tvPosCost.text = String.format("%.2f", item.price * item.amount)
        holder.binding.btnPosDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = records.size
}

class ProfitCalculatorDialog(private val context: Context, private val currentItem: GoldItem) {

    private val dialog = BottomSheetDialog(context)
    private val binding = DialogProfitCalculatorBinding.inflate(LayoutInflater.from(context))
    private val prefs = context.getSharedPreferences(GoldPriceService.PREFS_NAME, Context.MODE_PRIVATE)
    
    private val allRecords = mutableListOf<PositionRecord>()
    private val currentTargetRecords = mutableListOf<PositionRecord>()
    private lateinit var adapter: PositionAdapter
    
    init {
        dialog.setContentView(binding.root)
        
        binding.tvCalcTargetName.text = "当前监控: ${currentItem.displayName}"
        binding.btnCloseCalc.setOnClickListener { dialog.dismiss() }
        
        loadRecords()
        
        adapter = PositionAdapter(currentTargetRecords) { record ->
            deleteRecord(record)
        }
        binding.rvPositions.layoutManager = LinearLayoutManager(context)
        binding.rvPositions.adapter = adapter
        
        binding.etExpectedPrice.setText(String.format("%.2f", currentItem.price))
        binding.etExpectedPrice.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculateSummary()
            }
        })
        
        binding.btnAddPosition.setOnClickListener {
            val priceStr = binding.etAddPrice.text.toString()
            val amountStr = binding.etAddAmount.text.toString()
            val price = priceStr.toDoubleOrNull()
            val amount = amountStr.toDoubleOrNull()
            
            if (price != null && amount != null && price > 0 && amount > 0) {
                val newRecord = PositionRecord(UUID.randomUUID().toString(), price, amount, currentItem.id)
                allRecords.add(newRecord)
                currentTargetRecords.add(newRecord)
                adapter.notifyItemInserted(currentTargetRecords.size - 1)
                saveRecords()
                calculateSummary()
                binding.etAddPrice.text?.clear()
                binding.etAddAmount.text?.clear()
            } else {
                Toast.makeText(context, "请输入有效的买入价和数量", Toast.LENGTH_SHORT).show()
            }
        }
        
        calculateSummary()
    }
    
    fun show() {
        dialog.show()
    }
    
    private fun loadRecords() {
        allRecords.clear()
        currentTargetRecords.clear()
        val jsonStr = prefs.getString("profit_calc_records", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val record = PositionRecord(
                    id = obj.getString("id"),
                    price = obj.getDouble("price"),
                    amount = obj.getDouble("amount"),
                    targetId = obj.getString("targetId")
                )
                allRecords.add(record)
                if (record.targetId == currentItem.id) {
                    currentTargetRecords.add(record)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun saveRecords() {
        try {
            val arr = JSONArray()
            for (record in allRecords) {
                val obj = JSONObject()
                obj.put("id", record.id)
                obj.put("price", record.price)
                obj.put("amount", record.amount)
                obj.put("targetId", record.targetId)
                arr.put(obj)
            }
            prefs.edit().putString("profit_calc_records", arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun deleteRecord(record: PositionRecord) {
        val idx = currentTargetRecords.indexOf(record)
        if (idx >= 0) {
            currentTargetRecords.removeAt(idx)
            adapter.notifyItemRemoved(idx)
        }
        allRecords.removeAll { it.id == record.id }
        saveRecords()
        calculateSummary()
    }
    
    private fun calculateSummary() {
        val expectedPriceStr = binding.etExpectedPrice.text.toString()
        val currentPrice = expectedPriceStr.toDoubleOrNull() ?: currentItem.price
        
        var totalAmount = 0.0
        var totalCost = 0.0
        
        for (record in currentTargetRecords) {
            totalAmount += record.amount
            totalCost += (record.price * record.amount)
        }
        
        if (totalAmount > 0) {
            val avgPrice = totalCost / totalAmount
            val marketValue = totalAmount * currentPrice
            val profitAmount = marketValue - totalCost
            val profitRate = (profitAmount / totalCost) * 100
            
            binding.tvAvgPrice.text = String.format("%.2f", avgPrice)
            binding.tvTotalAmount.text = String.format("%.2f", totalAmount)
            binding.tvTotalCost.text = String.format("%.2f", totalCost)
            binding.tvMarketValue.text = String.format("%.2f", marketValue)
            
            val sign = if (profitAmount > 0) "+" else ""
            binding.tvProfitAmount.text = String.format("%s%.2f", sign, profitAmount)
            binding.tvProfitRate.text = String.format("(%s%.2f%%)", sign, profitRate)
            
            val color = if (profitAmount >= 0) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
            binding.tvProfitAmount.setTextColor(color)
            binding.tvProfitRate.setTextColor(color)
        } else {
            binding.tvAvgPrice.text = "0.00"
            binding.tvTotalAmount.text = "0.00"
            binding.tvTotalCost.text = "0.00"
            binding.tvMarketValue.text = "0.00"
            binding.tvProfitAmount.text = "0.00"
            binding.tvProfitRate.text = "(0.00%)"
            binding.tvProfitAmount.setTextColor(Color.parseColor("#94A3B8"))
            binding.tvProfitRate.setTextColor(Color.parseColor("#94A3B8"))
        }
    }
}
