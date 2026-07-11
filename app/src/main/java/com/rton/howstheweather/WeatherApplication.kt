package com.rton.howstheweather

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WeatherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL,
                    "降雨提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "依目前 CWA 預報排程的一次性提醒" },
            )
        }
    }

    companion object { const val REMINDER_CHANNEL = "rain_reminders" }
}
