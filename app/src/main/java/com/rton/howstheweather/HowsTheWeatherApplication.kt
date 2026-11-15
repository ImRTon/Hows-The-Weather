package com.rton.howstheweather

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.rton.howstheweather.widget.WeatherDesktopWidgetProvider

class HowsTheWeatherApplication : Application() {
    private val widgetSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WeatherDesktopWidgetProvider.VOLUME_CHANGED_BROADCAST -> {
                    WeatherDesktopWidgetProvider.updateVolumeOnly(context)
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_USER_PRESENT -> {
                    WeatherDesktopWidgetProvider.updateAllWidgets(context)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(WeatherDesktopWidgetProvider.VOLUME_CHANGED_BROADCAST)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(widgetSyncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(widgetSyncReceiver, filter)
            }
        }
    }
}
