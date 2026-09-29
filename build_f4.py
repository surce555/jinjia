import re

xml_path = 'app/src/main/res/layout/activity_main.xml'
with open(xml_path, 'r', encoding='utf-8') as f:
    xml = f.read()

calendar_xml = """
        <!-- Feature 4: 宏观核弹数据日历 -->
        <com.google.android.material.card.MaterialCardView
            android:id="@+id/cardMacroCalendar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            app:cardBackgroundColor="@color/card_background"
            app:cardCornerRadius="22dp"
            app:cardElevation="3dp"
            app:strokeColor="@color/card_stroke"
            app:strokeWidth="1dp">
            
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:padding="16dp">
                
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📅 宏观数据预警"
                    android:textColor="@color/text_primary"
                    android:textSize="14sp"
                    android:textStyle="bold" />
                    
                <Space
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1" />
                    
                <TextView
                    android:id="@+id/tvMacroEvent"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="计算中..."
                    android:textColor="#EF4444"
                    android:textSize="13sp"
                    android:textStyle="bold" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- 监控设置与 AI 辅助分析核心操作卡片 -->
"""
if "cardMacroCalendar" not in xml:
    xml = xml.replace('        <!-- 监控设置与 AI 辅助分析核心操作卡片 (22dp 毛玻璃) -->', calendar_xml)

with open(xml_path, 'w', encoding='utf-8') as f:
    f.write(xml)

kt_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

macro_kt = """    private fun updateMacroCalendar() {
        // Find next Non-Farm Payrolls (NFP) - Usually 1st Friday of the month
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.FRIDAY) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 20)
        cal.set(java.util.Calendar.MINUTE, 30)
        cal.set(java.util.Calendar.SECOND, 0)
        
        if (cal.timeInMillis < now) {
            cal.add(java.util.Calendar.MONTH, 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.FRIDAY) {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        
        val diffDays = (cal.timeInMillis - now) / (1000 * 60 * 60 * 24)
        val eventStr = if (diffDays == 0L) "🔥大非农 今晚20:30公布!" else "距大非农还有 $diffDays 天"
        binding.tvMacroEvent.text = eventStr
    }
"""
if "updateMacroCalendar" not in kt:
    kt = kt.replace('    private fun initTabs() {', macro_kt + '\n    private fun initTabs() {')
    kt = kt.replace('initTabs()', 'initTabs()\n        updateMacroCalendar()')

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Feature 4: Macro Calendar applied")
