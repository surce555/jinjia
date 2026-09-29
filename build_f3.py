import re

file_path = 'app/src/main/res/layout/dialog_profit_calculator.xml'
with open(file_path, 'r', encoding='utf-8') as f:
    xml = f.read()

# Add Spread Input
spread_input = """
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="买卖点差/折耗 (元/克): "
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <EditText
                    android:id="@+id/etSpread"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:background="@android:color/transparent"
                    android:inputType="numberDecimal"
                    android:text="0.00"
                    android:textColor="@color/text_primary"
                    android:textSize="15sp"
                    android:textStyle="bold" />
            </LinearLayout>
            
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginVertical="10dp"
                android:background="#E2E8F0" />
"""
if "etSpread" not in xml:
    xml = xml.replace('            <View\n                android:layout_width="match_parent"\n                android:layout_height="1dp"\n                android:layout_marginVertical="10dp"\n                android:background="#E2E8F0" />', spread_input)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(xml)

kt_path = 'app/src/main/java/com/example/jinjia/ui/ProfitCalculator.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Update listener and calc
if "etSpread" not in kt:
    kt = kt.replace('binding.etExpectedPrice.addTextChangedListener(object : TextWatcher {', """
        binding.etExpectedPrice.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { calculateSummary() }
        })
        binding.etSpread.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { calculateSummary() }
        })
        // dummy text watcher to replace old one
        val dummy = object : TextWatcher {""")
    
    kt = kt.replace('val currentPrice = expectedPriceStr.toDoubleOrNull() ?: currentItem.price',
"""val currentPrice = expectedPriceStr.toDoubleOrNull() ?: currentItem.price
        val spread = binding.etSpread.text.toString().toDoubleOrNull() ?: 0.0""")
    
    kt = kt.replace('val marketValue = totalAmount * currentPrice',
"""val avgPrice = totalCost / totalAmount
            val realBreakEven = avgPrice + spread
            val marketValue = totalAmount * (currentPrice - spread)""")
    
    kt = kt.replace('binding.tvAvgPrice.text = String.format("%.2f", avgPrice)',
"""binding.tvAvgPrice.text = String.format("%.2f (保本:%.2f)", avgPrice, realBreakEven)""")

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Feature 3: Spread applied")
