package com.rton.howstheweather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.format.DateFormat
import android.util.TypedValue
import android.widget.RemoteViews
import com.rton.howstheweather.MainActivity
import com.rton.howstheweather.R
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WeatherDesktopWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
        triggerAsyncWeatherRefresh(context, force = false)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val summary = WidgetWeatherStore.read(context)
        val now = ZonedDateTime.now()
        appWidgetIds.forEach { id ->
            val options = runCatching { appWidgetManager.getAppWidgetOptions(id) }.getOrNull()
            val views = buildRemoteViews(context, summary, now = now, options = options)
            appWidgetManager.updateAppWidget(id, views)
        }
        triggerAsyncWeatherRefresh(context, force = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val summary = WidgetWeatherStore.read(context)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildRemoteViews(context, summary, options = newOptions),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WEATHER -> {
                updateAllWidgets(context)
                triggerAsyncWeatherRefresh(context, force = true)
            }
            ACTION_VOLUME_CHANGED,
            VOLUME_CHANGED_BROADCAST -> {
                updateVolumeOnly(context)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_USER_PRESENT -> {
                updateAllWidgets(context)
                triggerAsyncWeatherRefresh(context, force = false)
            }
        }
    }

    private fun triggerAsyncWeatherRefresh(context: Context, force: Boolean) {
        val appContext = context.applicationContext
        val pendingResult = runCatching { goAsync() }.getOrNull()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val refreshed = WidgetWeatherStore.refreshOnDemand(appContext, force = force)
                if (refreshed != null) {
                    updateAllWidgets(appContext, refreshed)
                }
            } finally {
                runCatching { pendingResult?.finish() }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WEATHER = "com.rton.howstheweather.widget.ACTION_REFRESH_WEATHER"
        const val ACTION_VOLUME_CHANGED = "com.rton.howstheweather.widget.ACTION_VOLUME_CHANGED"
        const val VOLUME_CHANGED_BROADCAST = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_FROM_WIDGET = "com.rton.howstheweather.widget.EXTRA_FROM_WIDGET"

        private const val REQUEST_OPEN_APP = 1001
        private const val REQUEST_OPEN_SEARCH = 1002
        private const val REQUEST_OPEN_VOLUME = 1003
        private const val REQUEST_OPEN_LENS = 1004

        fun updateAllWidgets(
            context: Context,
            summary: WidgetWeatherSummary? = WidgetWeatherStore.read(context),
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, WeatherDesktopWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val now = ZonedDateTime.now()
            ids.forEach { id ->
                val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                val views = buildRemoteViews(appContext, summary, now = now, options = options)
                manager.updateAppWidget(id, views)
            }
        }

        fun updateVolumeOnly(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, WeatherDesktopWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val (currentVolume, maxVolume) = readMediaVolume(appContext)
            val partialViews = RemoteViews(appContext.packageName, R.layout.weather_desktop_widget).apply {
                setProgressBar(R.id.widget_volume_bar, maxVolume, currentVolume, false)
            }
            manager.partiallyUpdateAppWidget(ids, partialViews)
        }

        internal fun buildRemoteViews(
            context: Context,
            summary: WidgetWeatherSummary?,
            now: ZonedDateTime = ZonedDateTime.now(),
            options: Bundle? = null,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.weather_desktop_widget)
            val weekdayText = WidgetWeatherFormatter.formatWeekday(now)

            views.setTextViewText(R.id.widget_its_label, context.getString(R.string.widget_its_text))
            configureWeekdayHeadline(context, views, weekdayText, now, options)
            views.setTextViewText(R.id.widget_date_text, WidgetWeatherFormatter.formatDateOnly(now))
            views.setTextViewText(
                R.id.widget_location_text,
                WidgetWeatherFormatter.formatLocationBadge(summary?.locationName),
            )

            val pattern12 = WidgetWeatherFormatter.buildTextClockPattern(
                now = now,
                summary = summary,
                use24Hour = false,
            )
            val pattern24 = WidgetWeatherFormatter.buildTextClockPattern(
                now = now,
                summary = summary,
                use24Hour = true,
            )
            val renderedNow = WidgetWeatherFormatter.buildRenderedSummary(
                now = now,
                summary = summary,
                use24Hour = DateFormat.is24HourFormat(context),
            )
            views.setCharSequence(R.id.widget_summary_clock, "setFormat12Hour", pattern12)
            views.setCharSequence(R.id.widget_summary_clock, "setFormat24Hour", pattern24)
            views.setTextViewText(R.id.widget_summary_clock, renderedNow)

            val (currentVolume, maxVolume) = readMediaVolume(context)
            views.setProgressBar(R.id.widget_volume_bar, maxVolume, currentVolume, false)

            // 1. Tap weather / time area -> Open How's the Weather MainActivity
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_WIDGET, true)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_weather_container, openAppPendingIntent)

            // 2. Tap search pill -> Launch Google Search app via trampoline activity
            val searchIntent = Intent(context, WidgetSearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val searchPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_SEARCH,
                searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_search_bar, searchPendingIntent)

            val lensIntent = Intent(context, WidgetSearchActivity::class.java).apply {
                action = WidgetSearchActivity.ACTION_OPEN_LENS
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_lens_button,
                PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN_LENS,
                    lensIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            // 3. Tap media volume bar -> Open translucent draggable volume slider overlay
            val volumeIntent = Intent(context, WidgetVolumeDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val volumePendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_VOLUME,
                volumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_volume_touch_area, volumePendingIntent)

            return views
        }

        private fun configureWeekdayHeadline(
            context: Context,
            views: RemoteViews,
            weekdayText: String,
            now: ZonedDateTime,
            options: Bundle?,
        ) {
            val spannedWeekday = WidgetWeatherFormatter.formatWeekdaySpanned(now)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // XML configures uniform auto-sizing (22sp..50sp, 1px step granularity) across match_parent
                views.setTextViewText(R.id.widget_weekday_text, spannedWeekday)
            } else {
                val weekdayTextSizeSp = resolveWeekdayTextSizeSp(context, weekdayText, options)
                views.setTextViewTextSize(
                    R.id.widget_weekday_text,
                    TypedValue.COMPLEX_UNIT_SP,
                    weekdayTextSizeSp,
                )
                views.setTextViewText(R.id.widget_weekday_text, spannedWeekday)
            }
        }

        private fun resolveWeekdayTextSizeSp(
            context: Context,
            weekdayText: String,
            options: Bundle?,
        ): Float {
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val availableWidthDp = WidgetWeatherFormatter.resolveAvailableWeekdayWidthDp(minWidthDp)
            val fontScale = context.resources.configuration.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val measuredEm = measureWeekdayWidthEm(weekdayText)
            return WidgetWeatherFormatter.computeWeekdayTextSizeSp(
                weekdayText = weekdayText,
                availableWidthDp = availableWidthDp,
                fontScale = fontScale,
                measuredWidthEm = measuredEm,
            )
        }

        private fun measureWeekdayWidthEm(weekdayText: String): Float? {
            if (weekdayText.isEmpty()) return null
            val paint = runCatching {
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textSize = 100f
                }
            }.getOrNull() ?: return null
            val widthPx = runCatching { paint.measureText(weekdayText) }.getOrNull() ?: return null
            if (!widthPx.isFinite() || widthPx <= 0f) return null
            return widthPx / 100f
        }

        private fun readMediaVolume(context: Context): Pair<Int, Int> {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return 40 to 100
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            return current to max
        }
    }
}
