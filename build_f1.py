import os
import re

def ensure_dir(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)

# 1. Feature: App Widget
widget_layout = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#DD1E293B"
    android:orientation="vertical"
    android:padding="12dp"
    android:gravity="center_vertical">
    
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="London Gold"
        android:textColor="#94A3B8"
        android:textSize="12sp" />
        
    <TextView
        android:id="@+id/widgetTvLondon"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="--.--"
        android:textColor="#F8FAFC"
        android:textSize="18sp"
        android:textStyle="bold" />
        
    <TextView
        android:id="@+id/widgetTvTargetName"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="My Target"
        android:textColor="#94A3B8"
        android:textSize="12sp" />
        
    <TextView
        android:id="@+id/widgetTvTargetPrice"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="--.--"
        android:textColor="#FBBF24"
        android:textSize="18sp"
        android:textStyle="bold" />
</LinearLayout>
"""
ensure_dir('app/src/main/res/layout/widget_gold.xml')
with open('app/src/main/res/layout/widget_gold.xml', 'w', encoding='utf-8') as f:
    f.write(widget_layout)

widget_info = """<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:initialLayout="@layout/widget_gold"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen" />
"""
ensure_dir('app/src/main/res/xml/gold_widget_info.xml')
with open('app/src/main/res/xml/gold_widget_info.xml', 'w', encoding='utf-8') as f:
    f.write(widget_info)

widget_provider = """package com.example.jinjia.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.ComponentName
import android.widget.RemoteViews
import com.example.jinjia.R

class GoldWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Will be updated from GoldPriceService
    }
    
    companion object {
        fun updateWidget(context: Context, londonPrice: String, targetName: String, targetPrice: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, GoldWidgetProvider::class.java))
            
            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_gold)
                views.setTextViewText(R.id.widgetTvLondon, londonPrice)
                views.setTextViewText(R.id.widgetTvTargetName, targetName)
                views.setTextViewText(R.id.widgetTvTargetPrice, targetPrice)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
"""
ensure_dir('app/src/main/java/com/example/jinjia/widget/GoldWidgetProvider.kt')
with open('app/src/main/java/com/example/jinjia/widget/GoldWidgetProvider.kt', 'w', encoding='utf-8') as f:
    f.write(widget_provider)

# Update AndroidManifest.xml
with open('app/src/main/AndroidManifest.xml', 'r', encoding='utf-8') as f:
    manifest = f.read()
if "GoldWidgetProvider" not in manifest:
    manifest = manifest.replace('</application>', """
        <receiver android:name=".widget.GoldWidgetProvider" android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/gold_widget_info" />
        </receiver>
    </application>""")
    with open('app/src/main/AndroidManifest.xml', 'w', encoding='utf-8') as f:
        f.write(manifest)

print("Widget files generated")
