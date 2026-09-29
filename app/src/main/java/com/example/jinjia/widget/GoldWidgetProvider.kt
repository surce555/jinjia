package com.example.jinjia.widget

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
