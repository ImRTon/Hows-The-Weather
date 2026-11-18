package com.rton.howstheweather.widget

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.CurrentWeatherObservation
import com.rton.howstheweather.domain.ForecastDecision
import com.rton.howstheweather.domain.RainState
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class WidgetWeatherSummary(
    val locationName: String = "Taipei",
    val conditionEnglish: String? = null,
    val weatherIcon: String = "",
    val currentTempCelsius: Float? = null,
    val highTempCelsius: Int? = null,
    val lowTempCelsius: Int? = null,
    val rainProbabilityPercent: Int? = null,
    val rainState: RainState = RainState.UNAVAILABLE,
    val hourlyRainMillimeters: Float? = null,
    val updatedAtEpochMillis: Long = 0L,
)

object WidgetWeatherFormatter {
    const val GRADIENT_START_HEX: Int = 0xFFFF0844.toInt()
    const val ACCENT_COLOR_HEX: Int = 0xFFFF1E56.toInt()
    const val GRADIENT_END_HEX: Int = 0xFFFF3868.toInt()

    const val DEFAULT_WEEKDAY_TEXT_SIZE_SP: Float = 50f
    const val MIN_WEEKDAY_TEXT_SIZE_SP: Float = 22f
    const val DEFAULT_AVAILABLE_WEEKDAY_WIDTH_DP: Float = 268f
    private const val MIN_AVAILABLE_WEEKDAY_WIDTH_DP: Float = 96f
    private const val WIDGET_HORIZONTAL_PADDING_DP: Float = 52f

    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)
    private val time12Formatter = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)
    private val time24Formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun formatWeekday(now: ZonedDateTime): String = "${weekdayFormatter.format(now)}."

    fun resolveAvailableWeekdayWidthDp(minWidthDp: Int): Float {
        if (minWidthDp <= 0) return DEFAULT_AVAILABLE_WEEKDAY_WIDTH_DP
        return (minWidthDp - WIDGET_HORIZONTAL_PADDING_DP)
            .coerceAtLeast(MIN_AVAILABLE_WEEKDAY_WIDTH_DP)
    }

    /**
     * Estimates the horizontal advance width (in `em` units) of a weekday headline
     * such as `"Wednesday."` in bold sans-serif.
     */
    internal fun estimateWeekdayWidthEm(weekdayText: String): Float {
        if (weekdayText.isEmpty()) return 0f
        var rawEm = 0f
        for (ch in weekdayText) {
            rawEm += when (ch) {
                'W' -> 0.91f
                'M' -> 0.87f
                'T', 'S' -> 0.62f
                'F' -> 0.56f
                'h', 'u', 'n', 'd', 'o' -> 0.57f
                'a', 'e' -> 0.545f
                's', 'y' -> 0.52f
                'r' -> 0.395f
                't' -> 0.37f
                'i', '.' -> 0.29f
                ' ' -> 0.28f
                else -> if (ch.isUpperCase()) 0.68f else 0.55f
            }
        }
        return rawEm
    }

    /**
     * Computes a single-line text size in `sp` for the weekday headline so long weekdays
     * (such as `"Wednesday."`) or narrower widget sizes resize down instead of wrapping.
     */
    fun computeWeekdayTextSizeSp(
        weekdayText: String,
        availableWidthDp: Float = DEFAULT_AVAILABLE_WEEKDAY_WIDTH_DP,
        fontScale: Float = 1f,
        measuredWidthEm: Float? = null,
        maxTextSizeSp: Float = DEFAULT_WEEKDAY_TEXT_SIZE_SP,
        minTextSizeSp: Float = MIN_WEEKDAY_TEXT_SIZE_SP,
    ): Float {
        if (weekdayText.isBlank()) return maxTextSizeSp
        val safeFontScale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeAvailableWidthSp = availableWidthDp.coerceAtLeast(MIN_AVAILABLE_WEEKDAY_WIDTH_DP) / safeFontScale
        val widthEm = maxOf(
            estimateWeekdayWidthEm(weekdayText),
            measuredWidthEm?.takeIf { it.isFinite() && it > 0f } ?: 0f,
        )
        if (widthEm <= 0f) return maxTextSizeSp
        val fittedSp = safeAvailableWidthSp / widthEm
        return fittedSp.coerceIn(minTextSizeSp, maxTextSizeSp)
    }

    /**
     * Renders the weekday headline (e.g. "Sunday.") with a subtle character-by-character
     * horizontal gradient from deep ruby (#FF0844) to rich crimson-rose (#FF3868).
     */
    fun formatWeekdaySpanned(now: ZonedDateTime): Spanned {
        val text = formatWeekday(now)
        val builder = SpannableStringBuilder(text)
        applyCharacterGradient(builder, 0, text.length)
        return builder
    }

    fun formatDateOnly(now: ZonedDateTime): String = dateFormatter.format(now)

    fun formatLocationBadge(locationName: String?): String {
        val englishLocation = translateLocationName(locationName) ?: return ""
        return "· $englishLocation"
    }

    fun formatDateLine(now: ZonedDateTime, locationName: String?): String {
        val dateText = formatDateOnly(now)
        val englishLocation = translateLocationName(locationName)
        return if (englishLocation.isNullOrBlank()) {
            dateText
        } else {
            "$dateText · $englishLocation"
        }
    }

    fun greetingForHour(hourOfDay: Int): String = when (hourOfDay) {
        in 5..11 -> "Good Morning!"
        in 12..16 -> "Good Afternoon!"
        in 17..21 -> "Good Evening!"
        else -> "Good Night!"
    }

    fun fromAppState(
        targetDisplayName: String,
        currentWeather: CurrentWeatherObservation?,
        areaForecast: AreaForecast?,
        weeklyForecast: AreaForecast?,
        decision: ForecastDecision,
        previous: WidgetWeatherSummary? = null,
        now: Instant = Instant.now(),
    ): WidgetWeatherSummary {
        val activePeriod = areaForecast?.periods?.firstOrNull()
        val weeklyPeriod = weeklyForecast?.periods?.firstOrNull()
        val conditionCode = activePeriod?.weatherCode ?: weeklyPeriod?.weatherCode
        val rawCondition = currentWeather?.weatherDescription
            ?.takeIf { it.isNotBlank() }
            ?: activePeriod?.weatherDescription?.takeIf { it.isNotBlank() }
            ?: weeklyPeriod?.weatherDescription?.takeIf { it.isNotBlank() }

        val conditionEnglish = translateWeatherCondition(
            weatherCode = conditionCode,
            description = rawCondition,
            rainState = decision.state,
        ) ?: previous?.conditionEnglish

        val candidateTemps = buildList {
            currentWeather?.temperatureCelsius?.roundToInt()?.let(::add)
            areaForecast?.periods?.take(4)?.forEach { period ->
                period.maximumTemperatureCelsius?.let(::add)
                period.minimumTemperatureCelsius?.let(::add)
            }
            weeklyForecast?.periods?.take(2)?.forEach { period ->
                period.maximumTemperatureCelsius?.let(::add)
                period.minimumTemperatureCelsius?.let(::add)
            }
        }
        val highTemp = weeklyForecast?.periods?.take(2)?.mapNotNull { it.maximumTemperatureCelsius }?.maxOrNull()
            ?: areaForecast?.periods?.take(4)?.mapNotNull { it.maximumTemperatureCelsius }?.maxOrNull()
            ?: candidateTemps.maxOrNull()
            ?: previous?.highTempCelsius
        val lowTemp = weeklyForecast?.periods?.take(2)?.mapNotNull { it.minimumTemperatureCelsius }?.minOrNull()
            ?: areaForecast?.periods?.take(4)?.mapNotNull { it.minimumTemperatureCelsius }?.minOrNull()
            ?: candidateTemps.minOrNull()
            ?: previous?.lowTempCelsius

        val pop = activePeriod?.precipitationProbabilityPercent
            ?: weeklyPeriod?.precipitationProbabilityPercent
            ?: areaForecast?.periods?.firstNotNullOfOrNull { it.precipitationProbabilityPercent }
            ?: weeklyForecast?.periods?.firstNotNullOfOrNull { it.precipitationProbabilityPercent }
            ?: previous?.rainProbabilityPercent

        val hourlyMm = decision.series.firstOrNull()?.millimetersPerHour
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: previous?.hourlyRainMillimeters

        val resolvedRainState = if (decision.state != RainState.UNAVAILABLE) {
            decision.state
        } else {
            previous?.rainState ?: RainState.UNAVAILABLE
        }

        val rawLocation = areaForecast?.countyName?.takeIf { it.isNotBlank() }
            ?: weeklyForecast?.countyName?.takeIf { it.isNotBlank() }
            ?: targetDisplayName

        return WidgetWeatherSummary(
            locationName = translateLocationName(rawLocation) ?: previous?.locationName ?: "Taipei",
            conditionEnglish = conditionEnglish,
            currentTempCelsius = currentWeather?.temperatureCelsius
                ?: activePeriod?.maximumTemperatureCelsius?.toFloat()
                ?: previous?.currentTempCelsius,
            highTempCelsius = highTemp,
            lowTempCelsius = lowTemp,
            rainProbabilityPercent = pop,
            rainState = resolvedRainState,
            hourlyRainMillimeters = hourlyMm,
            updatedAtEpochMillis = now.toEpochMilli(),
        )
    }

    /**
     * Builds a [Spanned] format string suitable for [android.widget.TextClock.setFormat12Hour]
     * or [android.widget.TextClock.setFormat24Hour].
     */
    fun buildTextClockPattern(
        now: ZonedDateTime,
        summary: WidgetWeatherSummary?,
        use24Hour: Boolean,
    ): Spanned {
        val timeToken = if (use24Hour) "HH:mm" else "h:mm"
        return buildSummaryInternal(
            greeting = greetingForHour(now.hour),
            timeSegment = timeToken,
            summary = summary,
            forDateFormatPattern = true,
        )
    }

    /**
     * Builds the immediately rendered [Spanned] summary text for the given [now] timestamp.
     */
    fun buildRenderedSummary(
        now: ZonedDateTime,
        summary: WidgetWeatherSummary?,
        use24Hour: Boolean,
    ): Spanned {
        val timeText = (if (use24Hour) time24Formatter else time12Formatter).format(now)
        return buildSummaryInternal(
            greeting = greetingForHour(now.hour),
            timeSegment = timeText,
            summary = summary,
            forDateFormatPattern = false,
        )
    }

    data class SummarySegment(
        val text: String,
        val isAccent: Boolean,
        val isRawTimeToken: Boolean = false,
    )

    internal fun buildSummarySegments(
        greeting: String,
        timeSegment: String,
        summary: WidgetWeatherSummary?,
        forDateFormatPattern: Boolean = false,
    ): List<SummarySegment> {
        val segments = mutableListOf<SummarySegment>()

        fun sanitize(text: String): String = text.replace("'", "’")

        fun appendQuoteDelimiter() {
            if (forDateFormatPattern) {
                segments += SummarySegment("'", isAccent = false)
            }
        }

        fun appendPlain(text: String) {
            if (text.isEmpty()) return
            segments += SummarySegment(sanitize(text), isAccent = false)
        }

        fun appendAccent(text: String, isRawTimeToken: Boolean = false) {
            if (text.isEmpty()) return
            val content = if (isRawTimeToken) text else sanitize(text)
            segments += SummarySegment(content, isAccent = true, isRawTimeToken = isRawTimeToken)
        }

        appendQuoteDelimiter()
        appendPlain("$greeting\nThe time is ")
        appendQuoteDelimiter()

        appendAccent(timeSegment, isRawTimeToken = forDateFormatPattern)

        appendQuoteDelimiter()

        val hasWeather = summary != null && (
            summary.conditionEnglish != null ||
                summary.currentTempCelsius != null ||
                summary.highTempCelsius != null ||
                summary.lowTempCelsius != null ||
                summary.rainState != RainState.UNAVAILABLE
            )

        if (summary == null || !hasWeather) {
            appendPlain(" and weather data is ")
            appendAccent("syncing")
            appendPlain(".")
            appendQuoteDelimiter()
            return segments
        }

        val condition = summary.conditionEnglish ?: "calm"
        val currentTempText = summary.currentTempCelsius
            ?.takeIf { it.isFinite() }
            ?.let { "${it.roundToInt()}°C" }

        appendPlain(" and the weather outside is ")
        if (currentTempText != null) {
            appendAccent("$condition ($currentTempText)")
        } else {
            appendAccent(condition)
        }

        val high = summary.highTempCelsius
        val low = summary.lowTempCelsius
        val rainTail = compactRainTail(summary)

        when {
            high != null && low != null && rainTail != null -> {
                appendPlain(" with a high of ")
                appendAccent("$high°C")
                appendPlain(", a low of ")
                appendAccent("$low°C")
                appendPlain(", and ")
                appendAccent(rainTail.first)
                if (rainTail.second.isNotEmpty()) appendPlain(rainTail.second)
            }
            high != null && low != null -> {
                appendPlain(" with a high of ")
                appendAccent("$high°C")
                appendPlain(" and a low of ")
                appendAccent("$low°C")
            }
            high != null && rainTail != null -> {
                appendPlain(" with a high of ")
                appendAccent("$high°C")
                appendPlain(" and ")
                appendAccent(rainTail.first)
                if (rainTail.second.isNotEmpty()) appendPlain(rainTail.second)
            }
            high != null -> {
                appendPlain(" with a high of ")
                appendAccent("$high°C")
            }
            rainTail != null -> {
                appendPlain(" with ")
                appendAccent(rainTail.first)
                if (rainTail.second.isNotEmpty()) appendPlain(rainTail.second)
            }
        }

        appendPlain(".")
        appendQuoteDelimiter()

        return segments
    }

    /**
     * Returns (accentedText, plainTrailingText) so rain probability is always included.
     */
    private fun compactRainTail(summary: WidgetWeatherSummary): Pair<String, String>? {
        val mmText = summary.hourlyRainMillimeters
            ?.takeIf { it.isFinite() && it >= 0.1f }
            ?.let { if (it < 10f) String.format(Locale.ENGLISH, "%.1f mm", it) else String.format(Locale.ENGLISH, "%.0f mm", it) }
        val pop = summary.rainProbabilityPercent

        return when (summary.rainState) {
            RainState.LIGHT,
            RainState.MODERATE,
            RainState.HEAVY,
            RainState.EXTREME -> {
                val label = when (summary.rainState) {
                    RainState.LIGHT -> "light rain"
                    RainState.MODERATE -> "moderate rain"
                    RainState.HEAVY -> "heavy rain"
                    RainState.EXTREME -> "intense rain"
                    RainState.DRY,
                    RainState.UNAVAILABLE -> "rain"
                }
                val detail = buildList {
                    if (pop != null) add("$pop%")
                    if (mmText != null) add(mmText)
                }.joinToString(" · ")
                val accent = if (detail.isNotEmpty()) "$label ($detail)" else label
                accent to " in 1h"
            }
            RainState.DRY -> {
                val resolvedPop = pop ?: 0
                "$resolvedPop%" to " rain chance"
            }
            RainState.UNAVAILABLE -> {
                if (pop != null) "$pop%" to " rain chance" else null
            }
        }
    }

    private fun buildSummaryInternal(
        greeting: String,
        timeSegment: String,
        summary: WidgetWeatherSummary?,
        forDateFormatPattern: Boolean,
    ): Spanned {
        val segments = buildSummarySegments(
            greeting = greeting,
            timeSegment = timeSegment,
            summary = summary,
            forDateFormatPattern = forDateFormatPattern,
        )
        val builder = SpannableStringBuilder()
        val accentRanges = mutableListOf<Triple<Int, Int, Boolean>>()

        for (segment in segments) {
            val start = builder.length
            builder.append(segment.text)
            val end = builder.length
            if (segment.isAccent && end > start) {
                accentRanges += Triple(start, end, segment.isRawTimeToken)
            }
        }

        for ((start, end, _) in accentRanges) {
            builder.setSpan(
                ForegroundColorSpan(ACCENT_COLOR_HEX),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    private fun applyCharacterGradient(builder: SpannableStringBuilder, start: Int, end: Int) {
        val length = end - start
        if (length <= 0) return
        if (length == 1) {
            builder.setSpan(
                ForegroundColorSpan(ACCENT_COLOR_HEX),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return
        }
        for (index in 0 until length) {
            val fraction = index.toFloat() / (length - 1).toFloat()
            val color = interpolateGradientColor(fraction)
            builder.setSpan(
                ForegroundColorSpan(color),
                start + index,
                start + index + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    internal fun interpolateGradientColor(fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val from = if (t < 0.5f) GRADIENT_START_HEX else ACCENT_COLOR_HEX
        val to = if (t < 0.5f) ACCENT_COLOR_HEX else GRADIENT_END_HEX
        val localT = if (t < 0.5f) t * 2f else (t - 0.5f) * 2f

        val r1 = (from ushr 16) and 0xFF
        val g1 = (from ushr 8) and 0xFF
        val b1 = from and 0xFF

        val r2 = (to ushr 16) and 0xFF
        val g2 = (to ushr 8) and 0xFF
        val b2 = to and 0xFF

        val r = (r1 + (r2 - r1) * localT).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * localT).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * localT).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun weatherIconFor(
        weatherCode: String?,
        description: String?,
        rainState: RainState = RainState.UNAVAILABLE,
    ): String = ""

    fun translateWeatherCondition(
        weatherCode: String?,
        description: String?,
        rainState: RainState = RainState.UNAVAILABLE,
    ): String? {
        val codeInt = weatherCode?.trim()?.toIntOrNull()
        if (codeInt != null) {
            when (codeInt) {
                1 -> return "clear"
                2, 3 -> return "mostly clear"
                4 -> return "partly cloudy"
                5, 6 -> return "mostly cloudy"
                7 -> return "overcast"
                8, 9, 10, 11, 12, 13, 14 -> return "showers"
                15, 16, 17, 18 -> return "thunderstorms"
                19, 20 -> return "showers"
                21, 22 -> return "thundershowers"
                23 -> return "sleet"
                24, 25, 26, 27, 28 -> return "foggy"
                29, 30, 31, 32 -> return "rainy"
                33, 34, 35, 36 -> return "thunderstorms"
                37, 38, 39, 41 -> return "rainy"
                42 -> return "snowy"
            }
        }

        val raw = description?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            if (raw.all { it.code < 128 }) {
                return raw.lowercase(Locale.ENGLISH)
            }
            return when {
                "雷" in raw && "雨" in raw -> "thunderstorms"
                "豪雨" in raw || "大雨" in raw -> "heavy rain"
                "陣雨" in raw || "短暫雨" in raw -> "showers"
                "雨" in raw -> "rainy"
                "雪" in raw -> "snowy"
                "霧" in raw || "靄" in raw -> "foggy"
                "陰" in raw && "多雲" in raw -> "mostly cloudy"
                "陰" in raw -> "overcast"
                "多雲" in raw && "晴" in raw -> "partly cloudy"
                "多雲" in raw || "多云" in raw -> "mostly cloudy"
                "晴" in raw -> "clear"
                else -> "cloudy"
            }
        }

        return when (rainState) {
            RainState.DRY -> "clear"
            RainState.LIGHT -> "light rain"
            RainState.MODERATE -> "moderate rain"
            RainState.HEAVY -> "heavy rain"
            RainState.EXTREME -> "intense rain"
            RainState.UNAVAILABLE -> null
        }
    }

    fun translateLocationName(rawName: String?): String? {
        val cleaned = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned.all { it.code < 128 }) return cleaned
        val normalized = cleaned.replace("台", "臺")
        for ((prefix, english) in COUNTY_MAP) {
            if (normalized.startsWith(prefix) || normalized.contains(prefix)) {
                return english
            }
        }
        return when {
            "目前位置" in normalized -> "Current"
            "地圖選取" in normalized -> "Pinned"
            else -> "Taiwan"
        }
    }

    private val COUNTY_MAP = listOf(
        "臺北市" to "Taipei",
        "新北市" to "New Taipei",
        "基隆市" to "Keelung",
        "桃園市" to "Taoyuan",
        "新竹市" to "Hsinchu",
        "新竹縣" to "Hsinchu",
        "苗栗縣" to "Miaoli",
        "臺中市" to "Taichung",
        "彰化縣" to "Changhua",
        "南投縣" to "Nantou",
        "雲林縣" to "Yunlin",
        "嘉義市" to "Chiayi",
        "嘉義縣" to "Chiayi",
        "臺南市" to "Tainan",
        "高雄市" to "Kaohsiung",
        "屏東縣" to "Pingtung",
        "宜蘭縣" to "Yilan",
        "花蓮縣" to "Hualien",
        "臺東縣" to "Taitung",
        "澎湖縣" to "Penghu",
        "金門縣" to "Kinmen",
        "連江縣" to "Matsu",
        "臺北" to "Taipei",
    )
}
