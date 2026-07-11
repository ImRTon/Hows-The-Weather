package com.rton.howstheweather

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: "雨勢更新"
        val forecastTime = inputData.getString(KEY_ISSUED_AT).orEmpty()
        val notification = NotificationCompat.Builder(applicationContext, WeatherApplication.REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("依 $forecastTime 發布的預報排程，出門前請再確認最新資料。")
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(1001, notification)
        return Result.success()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_ISSUED_AT = "issued_at"
    }
}
