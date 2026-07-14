package com.rton.howstheweather.data

import android.graphics.BitmapFactory
import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindGrid
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindProvenance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class CwaWeatherDataSource(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: CwaJsonGridParser = CwaJsonGridParser(),
    private val radarImageParser: CwaRadarImageParser = CwaRadarImageParser(),
    private val quantitativeForecastParser: CwaQuantitativeForecastImageParser = CwaQuantitativeForecastImageParser(),
    private val satelliteImageParser: CwaSatelliteImageParser = CwaSatelliteImageParser(),
    private val historyParser: CwaHistoryMetadataParser = CwaHistoryMetadataParser(),
    private val animationFrameParser: CwaAnimationFrameParser = CwaAnimationFrameParser(),
    private val cloudGridHarmonizer: CloudGridHarmonizer = CloudGridHarmonizer(),
    private val windParser: CwaWindObservationParser = CwaWindObservationParser(),
    private val gribWindParser: CwaGrib2WindParser = CwaGrib2WindParser(),
    private val areaForecastParser: CwaAreaForecastParser = CwaAreaForecastParser(),
    private val supplementaryDemoSource: SupplementaryWeatherDataSource = DemoWeatherDataSource(),
) : WeatherDataSource {
    init { require(apiKey.isNotBlank()) }

    override suspend fun load(target: GeoPoint): WeatherSnapshot = coroutineScope {
        val wideRadarRequest = async {
            fetchRadarFrame(WIDE_RADAR_DATASET_ID, RADAR_WIDE_SAMPLE_SIZE)
        }
        val localRadarRequest = async {
            fetchRadarFrame(LOCAL_RADAR_DATASET_ID, RADAR_LOCAL_SAMPLE_SIZE)
        }
        val forecastRequest = async { fetchGrid("F-B0046-001", WeatherUnit.MILLIMETERS_ONE_HOUR) }
        val radar = wideRadarRequest.await()
        val radarRegional = localRadarRequest.await()
        val forecast = forecastRequest.await()
        val hourlyAmount = forecast.sample(target)
        WeatherSnapshot(
            radar = radar,
            radarRegional = radarRegional,
            rainForecast = listOf(forecast),
            cloudFrames = emptyList(),
            cloudRegionalFrames = emptyList(),
            forecastAtTarget = listOf(ForecastPoint(60, hourlyAmount)),
            winds = emptyList(),
            windsAreDemo = false,
            windProvenance = WindProvenance.UNAVAILABLE,
            issuedAt = forecast.validAt,
            isDemo = false,
            hourlyAccumulationAtTarget = hourlyAmount,
        )
    }

    override suspend fun loadAreaForecast(target: GeoPoint): AreaForecast? = coroutineScope {
        val counties = countyForecastLocations ?: withContext(Dispatchers.IO) {
            areaForecastParser.locations(fetchForecastJson(NATIONAL_THREE_DAY_FORECAST_ID))
        }.also { countyForecastLocations = it }
        val candidates = counties
            .mapNotNull { county ->
                COUNTY_THREE_DAY_DATASET_IDS[county.name]?.let { datasetId -> Triple(county, datasetId, areaForecastParser.distanceKm(target, county.coordinate)) }
            }
            .sortedBy { it.third }
            .take(COUNTY_CANDIDATE_COUNT)
            .filter { it.third <= MAX_COUNTY_DISTANCE_KM }
        if (candidates.isEmpty()) return@coroutineScope null

        candidates.map { (county, datasetId) ->
            async(Dispatchers.IO) {
                runCatching {
                    areaForecastParser.nearestForecast(
                        json = fetchForecastJson(datasetId),
                        target = target,
                        sourceId = datasetId,
                        countyName = county.name,
                    )
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().minByOrNull {
            areaForecastParser.distanceKm(target, it.areaCoordinate)
        }
    }

    override suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast? = coroutineScope {
        val counties = countyForecastLocations ?: withContext(Dispatchers.IO) {
            areaForecastParser.locations(fetchForecastJson(NATIONAL_THREE_DAY_FORECAST_ID))
        }.also { countyForecastLocations = it }
        val candidates = counties
            .mapNotNull { county ->
                COUNTY_ONE_WEEK_DATASET_IDS[county.name]?.let { datasetId ->
                    Triple(county, datasetId, areaForecastParser.distanceKm(target, county.coordinate))
                }
            }
            .sortedBy { it.third }
            .take(COUNTY_CANDIDATE_COUNT)
            .filter { it.third <= MAX_COUNTY_DISTANCE_KM }
        if (candidates.isEmpty()) return@coroutineScope null

        candidates.map { (county, datasetId) ->
            async(Dispatchers.IO) {
                runCatching {
                    areaForecastParser.nearestWeeklyForecast(
                        json = fetchForecastJson(datasetId),
                        target = target,
                        sourceId = datasetId,
                        countyName = county.name,
                    )
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().minByOrNull {
            areaForecastParser.distanceKm(target, it.areaCoordinate)
        }
    }

    override fun enrichmentUpdates(snapshot: WeatherSnapshot, target: GeoPoint): Flow<WeatherSnapshot> = channelFlow {
        val stateMutex = Mutex()
        var latest = snapshot
        val demoMutex = Mutex()
        var demo: WeatherSupplements? = null
        val taiwanCloudReady = CompletableDeferred<Unit>()

        suspend fun demoSupplements(): WeatherSupplements = demoMutex.withLock {
            demo ?: supplementaryDemoSource.loadSupplements().also { demo = it }
        }

        suspend fun publish(transform: (WeatherSnapshot) -> WeatherSnapshot) {
            val update = stateMutex.withLock {
                transform(latest).also { latest = it }
            }
            send(update)
        }

        launch {
            val result = runCatching { fetchQuantitativeForecastFrames() }
            publish { current ->
                current.copy(
                    quantitativeForecastFrames = result.getOrElse { current.quantitativeForecastFrames },
                    notice = current.notice.appendFailures(
                        result.exceptionOrNull()?.let {
                            "0–48 小時定量降水失敗：${it.message ?: "未知錯誤"}"
                        },
                    ),
                )
            }
        }

        launch {
            taiwanCloudReady.await()
            val areaForecast = runCatching { loadAreaForecast(target) }.getOrNull()
            publish { current -> current.copy(areaForecast = areaForecast) }
        }

        launch {
            taiwanCloudReady.await()
            val wideRequest = async {
                runCatching {
                    fetchRadarHistory(
                        snapshot.radar, WIDE_RADAR_DATASET_ID, WIDE_RADAR_FILE_PREFIX, RADAR_WIDE_SAMPLE_SIZE,
                    )
                }
            }
            val localRequest = async {
                snapshot.radarRegional?.let { regional ->
                    runCatching {
                        fetchRadarHistory(
                            regional, LOCAL_RADAR_DATASET_ID, LOCAL_RADAR_FILE_PREFIX, RADAR_LOCAL_SAMPLE_SIZE,
                        )
                    }
                } ?: Result.success(emptyList())
            }
            val wideResult = wideRequest.await()
            val localResult = localRequest.await()
            publish { current ->
                current.copy(
                    radarFrames = wideResult.getOrElse { current.radarFrames },
                    radarRegionalFrames = localResult.getOrElse { current.radarRegionalFrames },
                    notice = current.notice.appendFailures(
                        wideResult.exceptionOrNull()?.let { "廣域雷達歷史失敗：${it.message ?: "未知錯誤"}" },
                        localResult.exceptionOrNull()?.let { "台灣雷達歷史失敗：${it.message ?: "未知錯誤"}" },
                    ),
                )
            }
        }

        launch {
            val taiwanResult = runCatching {
                fetchSatelliteFrame(TAIWAN_CLOUD_DATASET_ID, CLOUD_LOCAL_MAX_DIMENSION)
            }
            val taiwanFallback = if (taiwanResult.isFailure) demoSupplements().cloudRegionalFrames else emptyList()
            val taiwanCurrent = taiwanResult.getOrNull()?.let(::listOf) ?: taiwanFallback
            publish { current ->
                current.copy(
                    cloudRegionalFrames = taiwanCurrent,
                    notice = current.notice.appendFailures(
                        taiwanResult.exceptionOrNull()?.let {
                            "台灣衛星雲圖失敗：${it.message ?: "未知錯誤"} · 改用示範雲層"
                        },
                    ),
                )
            }
            taiwanCloudReady.complete(Unit)

            // Once the current Taiwan frame is visible, prioritize the current East Asia
            // frame while Taiwan history downloads in parallel. Historical playback can
            // arrive later without delaying either current observation.
            val eastAsiaRequest = async {
                runCatching {
                    fetchSatelliteFrame(EAST_ASIA_CLOUD_DATASET_ID, CLOUD_EAST_ASIA_MAX_DIMENSION)
                }
            }
            val taiwanHistoryRequest = async {
                if (taiwanResult.isSuccess) {
                    runCatching {
                        fetchSatelliteHistory(
                            taiwanResult.getOrThrow(), TAIWAN_CLOUD_DATASET_ID, TAIWAN_CLOUD_FILE_PREFIX,
                            CLOUD_LOCAL_MAX_DIMENSION,
                        )
                    }
                } else {
                    Result.success(taiwanFallback)
                }
            }

            val eastAsiaResult = eastAsiaRequest.await()
            val eastAsiaFallback = if (eastAsiaResult.isFailure) demoSupplements().cloudFrames else emptyList()
            val eastAsiaCurrent = eastAsiaResult.getOrNull()?.let(::listOf) ?: eastAsiaFallback
            publish { current ->
                current.copy(
                    cloudFrames = eastAsiaCurrent,
                    notice = current.notice.appendFailures(
                        eastAsiaResult.exceptionOrNull()?.let {
                            "東亞衛星雲圖失敗：${it.message ?: "未知錯誤"} · 改用示範雲層"
                        },
                    ),
                )
            }

            val taiwanHistoryResult = taiwanHistoryRequest.await()
            val taiwanHistory = taiwanHistoryResult.getOrElse { taiwanCurrent }
            publish { current ->
                current.copy(
                    cloudRegionalFrames = taiwanHistory,
                    notice = current.notice.appendFailures(
                        taiwanHistoryResult.exceptionOrNull()?.let {
                            "台灣衛星歷史失敗：${it.message ?: "未知錯誤"}"
                        },
                    ),
                )
            }

            if (eastAsiaResult.isSuccess) {
                val harmonizedTaiwan = withContext(PREPROCESSING_DISPATCHER) {
                    cloudGridHarmonizer.harmonize(taiwanHistory, eastAsiaCurrent)
                }
                publish { current ->
                    current.copy(cloudRegionalFrames = harmonizedTaiwan)
                }
                val eastAsiaHistoryResult = runCatching {
                    fetchSatelliteHistory(
                        eastAsiaResult.getOrThrow(), EAST_ASIA_CLOUD_DATASET_ID, EAST_ASIA_CLOUD_FILE_PREFIX,
                        CLOUD_EAST_ASIA_MAX_DIMENSION,
                    )
                }
                publish { current ->
                    current.copy(
                        cloudFrames = eastAsiaHistoryResult.getOrElse { eastAsiaCurrent },
                        notice = current.notice.appendFailures(
                            eastAsiaHistoryResult.exceptionOrNull()?.let {
                                "東亞衛星歷史失敗：${it.message ?: "未知錯誤"}"
                            },
                        ),
                    )
                }
            }
        }

        launch {
            taiwanCloudReady.await()
            val modelResult = runCatching { fetchModelWindGrid() }
            val observationResult = if (modelResult.isFailure) {
                runCatching { fetchWindObservations() }
            } else {
                Result.success(emptyList())
            }
            val useDemoWind = modelResult.isFailure && observationResult.isFailure
            val fallbackWinds = if (useDemoWind) demoSupplements().winds else emptyList()
            val modelFailure = modelResult.exceptionOrNull()?.let { modelError ->
                if (observationResult.isSuccess) {
                    "WRF 3 km 風場失敗：${modelError.message ?: "未知錯誤"} · 改用 CWA 測站觀測"
                } else {
                    "WRF 3 km 風場失敗：${modelError.message ?: "未知錯誤"}"
                }
            }
            val observationFailure = observationResult.exceptionOrNull()?.let { observationError ->
                "測站風場失敗：${observationError.message ?: "未知錯誤"} · 改用示範測站"
            }
            publish { current ->
                current.copy(
                    windGrid = modelResult.getOrNull(),
                    winds = when {
                        modelResult.isSuccess -> emptyList()
                        observationResult.isSuccess -> observationResult.getOrThrow()
                        else -> fallbackWinds
                    },
                    windsAreDemo = useDemoWind,
                    windProvenance = when {
                        modelResult.isSuccess -> WindProvenance.MODEL
                        observationResult.isSuccess -> WindProvenance.OBSERVATION
                        else -> WindProvenance.DEMO
                    },
                    notice = current.notice.appendFailures(modelFailure, observationFailure),
                )
            }
        }
    }

    private suspend fun fetchModelWindGrid(): WindGrid = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(MODEL_WIND_URL).head().build()).execute()
        val contentLength = response.use {
            check(it.isSuccessful) { "CWA M-A0064 回應 ${it.code}" }
            it.header("Content-Length")?.toLongOrNull()
                ?.takeIf { length -> length in 1..MAX_GRIB_FILE_BYTES }
                ?: error("CWA M-A0064 檔案長度無效")
        }
        suspend fun decodeRanges(ranges: ModelWindRanges): WindGrid = coroutineScope {
            check(ranges.east.last < contentLength && ranges.north.last < contentLength)
            val eastRequest = async { gribWindParser.decode(fetchRange(ranges.east)) }
            val northRequest = async { gribWindParser.decode(fetchRange(ranges.north)) }
            gribWindParser.combine(eastRequest.await(), northRequest.await())
        }
        val preferredRanges = cachedModelWindRanges ?: STABLE_MODEL_WIND_RANGES
        runCatching { decodeRanges(preferredRanges) }.getOrNull()?.let { return@withContext it }

        var offset = 0L
        var eastRange: LongRange? = null
        var northRange: LongRange? = null
        var messageCount = 0
        while (offset < contentLength && (eastRange == null || northRange == null)) {
            check(messageCount++ < MAX_GRIB_MESSAGES) { "CWA M-A0064 找不到 10 m U/V 訊息" }
            val end = minOf(offset + GRIB_HEADER_RANGE_BYTES - 1L, contentLength - 1L)
            val prefix = fetchRange(offset..end)
            val descriptor = gribWindParser.describe(prefix)
            check(descriptor.length > 0L && offset + descriptor.length <= contentLength) {
                "CWA M-A0064 訊息索引無效"
            }
            val range = offset until offset + descriptor.length
            when (descriptor.component) {
                WindComponent.EAST -> eastRange = range
                WindComponent.NORTH -> northRange = range
                null -> Unit
            }
            offset += descriptor.length
        }
        val discoveredRanges = ModelWindRanges(
            east = eastRange ?: error("CWA M-A0064 缺少 10 m U 風場"),
            north = northRange ?: error("CWA M-A0064 缺少 10 m V 風場"),
        )
        decodeRanges(discoveredRanges).also { cachedModelWindRanges = discoveredRanges }
    }

    private fun fetchRange(range: LongRange): ByteArray {
        val request = Request.Builder()
            .url(MODEL_WIND_URL)
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.code == 206) { "CWA M-A0064 不支援分段下載（${response.code}）" }
            response.body.bytes().also { bytes ->
                check(bytes.size.toLong() == range.last - range.first + 1L) { "CWA M-A0064 分段下載不完整" }
            }
        }
    }

    private suspend fun fetchGrid(datasetId: String, unit: WeatherUnit) = withContext(Dispatchers.IO) {
        val body = fetchBody(datasetId, "JSON")
        body.use { parser.parse(it.string(), unit, datasetId) }
    }

    private suspend fun fetchRadarFrame(
        datasetId: String,
        sampleSize: Int,
    ): WeatherGrid {
        val metadata = withContext(Dispatchers.IO) {
            fetchBody(datasetId, "JSON").use { radarImageParser.parseMetadata(it.string()) }
        }
        return decodeRadarImage(metadata, datasetId, sampleSize)
    }

    private suspend fun fetchRadarHistory(
        current: WeatherGrid,
        datasetId: String,
        animationFilePrefix: String,
        sampleSize: Int,
    ): List<WeatherGrid> = coroutineScope {
        val currentMetadata = CwaRadarImageMetadata(current.bounds, current.validAt, productUrl = "")
        val records = withContext(Dispatchers.IO) {
            fetchAnimationRecords(
                listUrl = RADAR_ANIMATION_LIST_URL,
                filePrefix = animationFilePrefix,
                baseUrl = RADAR_ANIMATION_BASE_URL,
                datasetId = datasetId,
                currentAt = currentMetadata.observedAt,
            )
        }
        records.map { record ->
            async {
                runCatching {
                    val metadata = withContext(Dispatchers.IO) {
                        radarMetadataForHistory(record, currentMetadata)
                    }
                    decodeRadarImage(metadata, datasetId, sampleSize)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
            .filter(WeatherGrid::isObservedFrame)
            .filter { it.validAt < current.validAt }
            .distinctBy(WeatherGrid::validAt)
            .sortedBy(WeatherGrid::validAt)
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT - 1)
    }

    private suspend fun fetchSatelliteFrame(
        datasetId: String,
        maxGridDimension: Int,
    ): WeatherGrid {
        val metadata = withContext(Dispatchers.IO) {
            fetchBody(datasetId, "JSON").use { satelliteImageParser.parseMetadata(it.string()) }
        }
        return decodeSatelliteImage(metadata, datasetId, maxGridDimension)
    }

    private suspend fun fetchSatelliteHistory(
        current: WeatherGrid,
        datasetId: String,
        animationFilePrefix: String,
        maxGridDimension: Int,
    ): List<WeatherGrid> = coroutineScope {
        val currentMetadata = CwaSatelliteImageMetadata(current.bounds, current.validAt, productUrl = "")
        val records = withContext(Dispatchers.IO) {
            fetchAnimationRecords(
            listUrl = SATELLITE_ANIMATION_LIST_URL,
            filePrefix = animationFilePrefix,
            baseUrl = SATELLITE_ANIMATION_BASE_URL,
            datasetId = datasetId,
            currentAt = currentMetadata.observedAt,
        )
        }
        val historical = records.map { record ->
            async {
                runCatching {
                    val metadata = withContext(Dispatchers.IO) {
                        satelliteMetadataForHistory(record, currentMetadata)
                    }
                    decodeSatelliteImage(metadata, datasetId, maxGridDimension)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        (historical + current)
            .filter(WeatherGrid::isObservedFrame)
            .filter { it.validAt <= current.validAt }
            .distinctBy(WeatherGrid::validAt)
            .sortedBy { it.validAt }
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT)
    }

    private fun radarMetadataForHistory(
        record: CwaHistoryRecord,
        current: CwaRadarImageMetadata,
    ): CwaRadarImageMetadata = if (record.url.substringBefore('?').endsWith(".png", ignoreCase = true)) {
        current.copy(observedAt = record.observedAt ?: current.observedAt, productUrl = record.url)
    } else {
        request(record.url).use { radarImageParser.parseMetadata(it.string()) }
    }

    private fun satelliteMetadataForHistory(
        record: CwaHistoryRecord,
        current: CwaSatelliteImageMetadata,
    ): CwaSatelliteImageMetadata = if (
        record.url.substringBefore('?').let { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
    ) {
        current.copy(observedAt = record.observedAt ?: current.observedAt, productUrl = record.url)
    } else {
        request(record.url).use { satelliteImageParser.parseMetadata(it.string()) }
    }

    private suspend fun decodeRadarImage(
        metadata: CwaRadarImageMetadata,
        datasetId: String,
        sampleSize: Int,
    ): WeatherGrid {
        val bytes = withContext(Dispatchers.IO) { request(metadata.productUrl).use { it.bytes() } }
        return withContext(PREPROCESSING_DISPATCHER) {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: error("CWA 雷達影像解碼失敗")
            try {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                radarImageParser.toGrid(metadata, image.width, image.height, pixels, datasetId)
            } finally {
                image.recycle()
            }
        }
    }

    private suspend fun decodeSatelliteImage(
        metadata: CwaSatelliteImageMetadata,
        datasetId: String,
        maxGridDimension: Int,
    ): WeatherGrid {
        val bytes = withContext(Dispatchers.IO) { request(metadata.productUrl).use { it.bytes() } }
        return withContext(PREPROCESSING_DISPATCHER) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth > 1 && bounds.outHeight > 1) { "CWA 衛星影像尺寸無效" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = decodeSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetDimension = maxGridDimension * SATELLITE_DECODE_OVERSAMPLE,
                )
            }
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: error("CWA 衛星影像解碼失敗")
            try {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                satelliteImageParser.toGrid(
                    metadata, image.width, image.height, pixels, datasetId, maxGridDimension,
                )
            } finally {
                image.recycle()
            }
        }
    }

    private suspend fun fetchQuantitativeForecastFrames(): List<WeatherGrid> = withContext(Dispatchers.IO) {
        QUANTITATIVE_FORECAST_DATASET_IDS.mapIndexed { index, datasetId ->
            val metadata = fetchBody(datasetId, "JSON").use {
                quantitativeForecastParser.parseMetadata(it.string())
            }
            val bytes = request(metadata.productUrl).use { it.bytes() }
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("CWA $datasetId 影像解碼失敗")
            try {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                quantitativeForecastParser.toGrid(
                    metadata = metadata,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    argbPixels = pixels,
                    sourceId = datasetId,
                    endHour = (index + 1) * 12,
                )
            } finally {
                image.recycle()
            }
        }
    }

    private fun decodeSampleSize(width: Int, height: Int, targetDimension: Int): Int {
        var sampleSize = 1
        val largestDimension = maxOf(width, height)
        while (largestDimension / (sampleSize * 2) >= targetDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun fetchHistoryRecords(datasetId: String, currentAt: java.time.Instant): List<CwaHistoryRecord> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(TAIPEI_ZONE)
        val url = "https://opendata.cwa.gov.tw/historyapi/v1/getMetadata/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", "JSON")
            .addQueryParameter("limit", OBSERVATION_HISTORY_FRAME_COUNT.toString())
            .addQueryParameter(
                "timeFrom",
                formatter.format(currentAt.minusSeconds(OBSERVATION_HISTORY_MINUTES * 60L)),
            )
            .addQueryParameter("timeTo", formatter.format(currentAt))
            .build()
        return runCatching { request(url.toString()).use { historyParser.parse(it.string()) } }
            .getOrDefault(emptyList())
            .filter { record -> record.observedAt?.let { it < currentAt } != false }
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT - 1)
    }

    private fun fetchAnimationRecords(
        listUrl: String,
        filePrefix: String,
        baseUrl: String,
        datasetId: String,
        currentAt: java.time.Instant,
    ): List<CwaHistoryRecord> {
        val animationRecords = runCatching {
            val script = animationScriptCache.computeIfAbsent(listUrl) { url ->
                request(url).use { it.string() }
            }
            animationFrameParser.parse(script, filePrefix, baseUrl)
        }.getOrDefault(emptyList())
        val candidates = animationRecords.ifEmpty { fetchHistoryRecords(datasetId, currentAt) }
        return candidates
            .filter { record -> record.observedAt?.let { it < currentAt } != false }
            .filter { record ->
                record.observedAt?.let {
                    java.time.Duration.between(it, currentAt).toMinutes() <= OBSERVATION_HISTORY_MINUTES
                } != false
            }
            .sortedBy { it.observedAt }
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT - 1)
    }

    private suspend fun fetchWindObservations() = withContext(Dispatchers.IO) {
        fun fetch(elementFilter: Boolean): List<WindObservation> {
            val builder = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$WIND_DATASET_ID".toHttpUrl()
                .newBuilder()
                .addQueryParameter("Authorization", apiKey)
                .addQueryParameter("format", "JSON")
            if (elementFilter) {
                builder.addQueryParameter("elementName", "WindDirection,WindSpeed")
            }
            return request(builder.build().toString()).use { windParser.parse(it.string()) }
        }

        runCatching { fetch(elementFilter = true) }
            .getOrElse { filteredError ->
                runCatching { fetch(elementFilter = false) }
                    .getOrElse { unfilteredError ->
                        unfilteredError.addSuppressed(filteredError)
                        throw unfilteredError
                    }
            }
    }

    private fun fetchBody(datasetId: String, format: String): okhttp3.ResponseBody {
        val url = "https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", format)
            .build()
        return request(url.toString())
    }

    private fun fetchForecastJson(datasetId: String): String {
        val url = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", "JSON")
            .build()
        return request(url.toString()).use { it.string() }
    }

    private fun request(url: String): okhttp3.ResponseBody {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            error("CWA 回應 ${response.code}")
        }
        return response.body
    }

    private companion object {
        const val WIND_DATASET_ID = "O-A0001-001"
        const val MODEL_WIND_URL = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Model/M-A0064-000.grb2"
        const val GRIB_HEADER_RANGE_BYTES = 256L
        const val MAX_GRIB_MESSAGES = 512
        const val MAX_GRIB_FILE_BYTES = 512L * 1024L * 1024L
        // CWA keeps the M-A0064 message inventory stable between model cycles.
        // Every candidate is fully validated as a 10 m U/V GRIB message before
        // use; if the inventory changes, the lightweight header scan above
        // discovers and caches the new byte ranges automatically.
        val STABLE_MODEL_WIND_RANGES = ModelWindRanges(
            east = 152_761_896L..155_100_085L,
            north = 155_100_086L..157_438_275L,
        )
        @Volatile var cachedModelWindRanges: ModelWindRanges? = null
        const val WIDE_RADAR_DATASET_ID = "O-A0058-005"
        const val LOCAL_RADAR_DATASET_ID = "O-A0058-006"
        const val EAST_ASIA_CLOUD_DATASET_ID = "O-B0032-003"
        const val TAIWAN_CLOUD_DATASET_ID = "O-C0042-004"
        val QUANTITATIVE_FORECAST_DATASET_IDS = listOf(
            "F-C0035-015", "F-C0035-017", "F-C0035-023", "F-C0035-024",
        )
        const val NATIONAL_THREE_DAY_FORECAST_ID = "F-D0047-089"
        const val RADAR_ANIMATION_LIST_URL = "https://www.cwa.gov.tw/Data/js/obs_img/Observe_radar.js"
        const val RADAR_ANIMATION_BASE_URL = "https://www.cwa.gov.tw/Data/radar"
        const val SATELLITE_ANIMATION_LIST_URL = "https://www.cwa.gov.tw/Data/js/obs_img/Observe_sat.js"
        const val SATELLITE_ANIMATION_BASE_URL = "https://www.cwa.gov.tw/Data/satellite"
        const val WIDE_RADAR_FILE_PREFIX = "CV1_3600_"
        const val LOCAL_RADAR_FILE_PREFIX = "CV1_TW_3600_"
        const val EAST_ASIA_CLOUD_FILE_PREFIX = "FDK_IR1_Gray_1000-"
        const val TAIWAN_CLOUD_FILE_PREFIX = "TWI_IR1_Gray_800-"
        const val RADAR_WIDE_SAMPLE_SIZE = 8
        const val RADAR_LOCAL_SAMPLE_SIZE = 4
        const val CLOUD_EAST_ASIA_MAX_DIMENSION = 320
        const val CLOUD_LOCAL_MAX_DIMENSION = 800
        const val SATELLITE_DECODE_OVERSAMPLE = 2
        const val COUNTY_CANDIDATE_COUNT = 2
        const val MAX_COUNTY_DISTANCE_KM = 180.0
        val COUNTY_THREE_DAY_DATASET_IDS = mapOf(
            "宜蘭縣" to "F-D0047-001",
            "桃園市" to "F-D0047-005",
            "新竹縣" to "F-D0047-009",
            "苗栗縣" to "F-D0047-013",
            "彰化縣" to "F-D0047-017",
            "南投縣" to "F-D0047-021",
            "雲林縣" to "F-D0047-025",
            "嘉義縣" to "F-D0047-029",
            "屏東縣" to "F-D0047-033",
            "臺東縣" to "F-D0047-037",
            "花蓮縣" to "F-D0047-041",
            "澎湖縣" to "F-D0047-045",
            "基隆市" to "F-D0047-049",
            "新竹市" to "F-D0047-053",
            "嘉義市" to "F-D0047-057",
            "臺北市" to "F-D0047-061",
            "高雄市" to "F-D0047-065",
            "新北市" to "F-D0047-069",
            "臺中市" to "F-D0047-073",
            "臺南市" to "F-D0047-077",
            "連江縣" to "F-D0047-081",
            "金門縣" to "F-D0047-085",
        )
        val COUNTY_ONE_WEEK_DATASET_IDS = COUNTY_THREE_DAY_DATASET_IDS.mapValues { (_, datasetId) ->
            val number = datasetId.takeLast(3).toInt() + 2
            datasetId.dropLast(3) + number.toString().padStart(3, '0')
        }
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")
        val animationScriptCache = ConcurrentHashMap<String, String>()
        @Volatile var countyForecastLocations: List<CwaForecastLocation>? = null
        val PREPROCESSING_DISPATCHER = Dispatchers.Default.limitedParallelism(2)
    }
}

private data class ModelWindRanges(val east: LongRange, val north: LongRange)

private fun String?.appendFailures(vararg failures: String?): String? {
    val detail = failures.filterNotNull().takeIf(List<String>::isNotEmpty)?.joinToString("；") ?: return this
    return listOfNotNull(this, "CWA $detail").joinToString("；")
}

class CwaWithDemoFallbackDataSource(
    private val live: WeatherDataSource,
    private val fallback: WeatherDataSource = DemoWeatherDataSource(),
) : WeatherDataSource {
    override suspend fun load(target: GeoPoint): WeatherSnapshot = runCatching { live.load(target) }
        .getOrElse { error ->
            fallback.load(target).copy(
                notice = "CWA 載入失敗：${error.message ?: "未知錯誤"} · 已改用示範格點",
            )
        }

    override suspend fun enrich(snapshot: WeatherSnapshot, target: GeoPoint): WeatherSnapshot =
        if (snapshot.isDemo) snapshot else live.enrich(snapshot, target)

    override suspend fun loadAreaForecast(target: GeoPoint): AreaForecast? = live.loadAreaForecast(target)

    override suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast? = live.loadWeeklyForecast(target)

    override fun enrichmentUpdates(snapshot: WeatherSnapshot, target: GeoPoint): Flow<WeatherSnapshot> =
        if (snapshot.isDemo) flowOf(snapshot) else live.enrichmentUpdates(snapshot, target)
}
