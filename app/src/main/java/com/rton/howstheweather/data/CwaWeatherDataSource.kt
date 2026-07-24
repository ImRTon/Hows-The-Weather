package com.rton.howstheweather.data

import android.graphics.BitmapFactory
import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.CurrentWeatherObservation
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindGrid
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CwaWeatherDataSource(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
    private val parser: CwaJsonGridParser = CwaJsonGridParser(),
    private val radarImageParser: CwaRadarImageParser = CwaRadarImageParser(),
    private val quantitativeForecastParser: CwaQuantitativeForecastImageParser = CwaQuantitativeForecastImageParser(),
    private val satelliteImageParser: CwaSatelliteImageParser = CwaSatelliteImageParser(),
    private val historyParser: CwaHistoryMetadataParser = CwaHistoryMetadataParser(),
    private val animationFrameParser: CwaAnimationFrameParser = CwaAnimationFrameParser(),
    private val cloudGridHarmonizer: CloudGridHarmonizer = CloudGridHarmonizer(),
    private val windParser: CwaWindObservationParser = CwaWindObservationParser(),
    private val currentWeatherParser: CwaCurrentWeatherParser = CwaCurrentWeatherParser(),
    private val gribWindParser: CwaGrib2WindParser = CwaGrib2WindParser(),
    private val areaForecastParser: CwaAreaForecastParser = CwaAreaForecastParser(),
) : WeatherDataSource {
    private val historyLoadSemaphore = Semaphore(HISTORY_DOWNLOAD_CONCURRENCY)
    private val quantitativeLoadSemaphore = Semaphore(QUANTITATIVE_DOWNLOAD_CONCURRENCY)
    private val animationCacheMutex = Mutex()
    private val countyForecastLocationsMutex = Mutex()
    @Volatile
    private var countyForecastLocations: List<CwaForecastLocation>? = null

    init { require(apiKey.isNotBlank()) }

    override suspend fun load(target: GeoPoint): WeatherSnapshot {
        val radar = fetchRadarFrame(WIDE_RADAR_DATASET_ID, RADAR_WIDE_SAMPLE_SIZE)
        return WeatherSnapshot(
            radar = radar,
            radarRegional = null,
            rainForecast = emptyList(),
            cloudFrames = emptyList(),
            cloudRegionalFrames = emptyList(),
            forecastAtTarget = emptyList(),
            winds = emptyList(),
            windProvenance = WindProvenance.UNAVAILABLE,
            issuedAt = radar.validAt,
        )
    }

    override suspend fun loadCurrentWeather(target: GeoPoint): CurrentWeatherObservation? {
        val nearbyStationIds = CwaObservationStationIndex
            .nearest(target, CURRENT_WEATHER_CANDIDATE_COUNT)
            .map(CwaObservationStation::id)
        if (nearbyStationIds.isEmpty()) return null
        val url = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$CURRENT_WEATHER_DATASET_ID".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", "JSON")
            .addQueryParameter("StationId", nearbyStationIds.joinToString(","))
            .addQueryParameter("WeatherElement", CURRENT_WEATHER_ELEMENTS)
            .addQueryParameter("GeoInfo", "Coordinates")
            .build()

        // Enqueue this small, user-visible request before dispatching the body
        // decode so radar, cloud, and area products cannot take its queue slot.
        val body = request(url.toString())
        val observations = try {
            withContext(Dispatchers.IO) { currentWeatherParser.parse(body.string()) }
        } finally {
            body.close()
        }
        return observations
            .minByOrNull { areaForecastParser.distanceKm(target, it.coordinate) }
            ?.takeIf {
                areaForecastParser.distanceKm(target, it.coordinate) <= MAX_CURRENT_WEATHER_DISTANCE_KM
            }
    }

    override suspend fun loadDecisionForecast(target: GeoPoint): WeatherDecisionForecast {
        val forecast = fetchGrid("F-B0046-001", WeatherUnit.MILLIMETERS_ONE_HOUR)
        val hourlyAmount = forecast.sample(target)
        return WeatherDecisionForecast(
            grids = listOf(forecast),
            forecastAtTarget = listOf(ForecastPoint(60, hourlyAmount)),
            issuedAt = forecast.validAt,
            hourlyAccumulationAtTarget = hourlyAmount,
        )
    }

    override suspend fun loadRegionalRadar(): WeatherGrid =
        fetchRadarFrame(LOCAL_RADAR_DATASET_ID, RADAR_LOCAL_SAMPLE_SIZE)

    override suspend fun loadAreaForecast(target: GeoPoint): AreaForecast? = coroutineScope {
        val counties = forecastCountyLocations()
        val candidates = counties
            .mapNotNull { county ->
                COUNTY_THREE_DAY_DATASET_IDS[county.name]?.let { datasetId -> Triple(county, datasetId, areaForecastParser.distanceKm(target, county.coordinate)) }
            }
            .sortedBy { it.third }
            .take(COUNTY_CANDIDATE_COUNT)
            .filter { it.third <= MAX_COUNTY_DISTANCE_KM }
        if (candidates.isEmpty()) return@coroutineScope null

        val results = candidates.map { (county, datasetId) ->
            async(Dispatchers.IO) {
                catchingCancellable {
                    areaForecastParser.nearestForecast(
                        json = fetchForecastJson(datasetId),
                        target = target,
                        sourceId = datasetId,
                        countyName = county.name,
                    )
                }
            }
        }.awaitAll()
        resolveAreaForecastResults(
            results = results,
            target = target,
            parser = areaForecastParser,
            failureMessage = "CWA 近期鄉鎮預報載入失敗",
        )
    }

    override suspend fun loadWeeklyForecast(target: GeoPoint): AreaForecast? = coroutineScope {
        val counties = forecastCountyLocations()
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

        val results = candidates.map { (county, datasetId) ->
            async(Dispatchers.IO) {
                catchingCancellable {
                    areaForecastParser.nearestWeeklyForecast(
                        json = fetchForecastJson(datasetId),
                        target = target,
                        sourceId = datasetId,
                        countyName = county.name,
                    )
                }
            }
        }.awaitAll()
        resolveAreaForecastResults(
            results = results,
            target = target,
            parser = areaForecastParser,
            failureMessage = "CWA 一週鄉鎮預報載入失敗",
        )
    }

    private suspend fun forecastCountyLocations(): List<CwaForecastLocation> {
        countyForecastLocations?.let { return it }
        return countyForecastLocationsMutex.withLock {
            countyForecastLocations ?: withContext(Dispatchers.IO) {
                areaForecastParser.locations(fetchForecastJson(NATIONAL_THREE_DAY_FORECAST_ID))
            }.also { countyForecastLocations = it }
        }
    }

    override fun enrichmentUpdates(snapshot: WeatherSnapshot, target: GeoPoint): Flow<WeatherSnapshot> = channelFlow {
        val stateMutex = Mutex()
        var latest = snapshot

        suspend fun publish(transform: (WeatherSnapshot) -> WeatherSnapshot) {
            val update = stateMutex.withLock {
                transform(latest).also { latest = it }
            }
            send(update)
        }

        launch {
            val result = catchingCancellable { fetchQuantitativeForecastFrames() }
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
            val taiwanResult = catchingCancellable {
                fetchSatelliteFrame(TAIWAN_CLOUD_DATASET_ID, CLOUD_LOCAL_MAX_DIMENSION)
            }
            val taiwanCurrent = taiwanResult.getOrNull()?.let(::listOf)
                ?: snapshot.cloudRegionalFrames
            publish { current ->
                current.copy(
                    cloudRegionalFrames = taiwanResult.getOrNull()?.let(::listOf)
                        ?: current.cloudRegionalFrames,
                    notice = current.notice.appendFailures(
                        taiwanResult.exceptionOrNull()?.let {
                            val fallbackState = if (current.cloudRegionalFrames.isNotEmpty()) {
                                "保留上次官方資料"
                            } else {
                                "此圖層暫不可用"
                            }
                            "台灣衛星雲圖失敗：${it.message ?: "未知錯誤"} · $fallbackState"
                        },
                    ),
                )
            }

            val eastAsiaResult = catchingCancellable {
                fetchSatelliteFrame(EAST_ASIA_CLOUD_DATASET_ID, CLOUD_EAST_ASIA_MAX_DIMENSION)
            }
            val eastAsiaCurrent = eastAsiaResult.getOrNull()?.let(::listOf)
                ?: snapshot.cloudFrames
            publish { current ->
                current.copy(
                    cloudFrames = eastAsiaResult.getOrNull()?.let(::listOf)
                        ?: current.cloudFrames,
                    notice = current.notice.appendFailures(
                        eastAsiaResult.exceptionOrNull()?.let {
                            val fallbackState = if (current.cloudFrames.isNotEmpty()) {
                                "保留上次官方資料"
                            } else {
                                "此圖層暫不可用"
                            }
                            "東亞衛星雲圖失敗：${it.message ?: "未知錯誤"} · $fallbackState"
                        },
                    ),
                )
            }

            if (taiwanCurrent.isNotEmpty() && eastAsiaCurrent.isNotEmpty()) {
                val harmonizedTaiwan = withContext(PREPROCESSING_DISPATCHER) {
                    cloudGridHarmonizer.harmonize(taiwanCurrent, eastAsiaCurrent)
                }
                publish { current ->
                    current.copy(cloudRegionalFrames = harmonizedTaiwan)
                }
            }
        }
    }

    override suspend fun loadWind(): WeatherWindData = windUpdates().last()

    override fun windUpdates(): Flow<WeatherWindData> = channelFlow {
        val completions = Channel<WindLoadResult>(capacity = 2)
        launch {
            completions.send(
                WindLoadResult.Observations(catchingCancellable { fetchWindObservations() }),
            )
        }
        launch {
            completions.send(
                WindLoadResult.Model(catchingCancellable { fetchModelWindGrid() }),
            )
        }
        var observationError: Throwable? = null
        var modelError: Throwable? = null
        var modelWasPublished = false
        repeat(2) {
            when (val completed = completions.receive()) {
                is WindLoadResult.Observations -> completed.result.fold(
                    onSuccess = { observations ->
                        if (!modelWasPublished) {
                            send(
                                WeatherWindData(
                                    winds = observations,
                                    provenance = WindProvenance.OBSERVATION,
                                ),
                            )
                        }
                    },
                    onFailure = { observationError = it },
                )
                is WindLoadResult.Model -> completed.result.fold(
                    onSuccess = { model ->
                        modelWasPublished = true
                        send(
                            WeatherWindData(
                                windGrid = model,
                                provenance = WindProvenance.MODEL,
                            ),
                        )
                    },
                    onFailure = { modelError = it },
                )
            }
        }
        completions.close()
        val finalObservationError = observationError
        val finalModelError = modelError
        if (finalObservationError != null && finalModelError != null) {
            finalModelError.addSuppressed(finalObservationError)
            throw IllegalStateException(
                "CWA 風場載入失敗：模式與測站資料皆不可用",
                finalModelError,
            )
        }
    }

    override fun historyUpdates(snapshot: WeatherSnapshot, kind: WeatherHistoryKind): Flow<WeatherSnapshot> =
        channelFlow {
            when (kind) {
                WeatherHistoryKind.RADAR -> {
                    val timeline = ObservedWeatherTimeline()
                    val updatesMutex = Mutex()
                    var wideFrames = snapshot.radarFrames
                    var localFrames = snapshot.radarRegionalFrames
                    val wideRequest = async {
                        catchingCancellable {
                            fetchRadarHistory(
                                snapshot.radar,
                                WIDE_RADAR_DATASET_ID,
                                WIDE_RADAR_FILE_PREFIX,
                                RADAR_WIDE_SAMPLE_SIZE,
                            ) { frame ->
                                updatesMutex.withLock {
                                    wideFrames = timeline.merge(snapshot.radar, wideFrames + frame)
                                        .filter { it.validAt < snapshot.radar.validAt }
                                    send(snapshot.copy(radarFrames = wideFrames, radarRegionalFrames = localFrames))
                                }
                            }
                        }
                    }
                    val localRequest = async {
                        snapshot.radarRegional?.let { regional ->
                            catchingCancellable {
                                fetchRadarHistory(
                                    regional,
                                    LOCAL_RADAR_DATASET_ID,
                                    LOCAL_RADAR_FILE_PREFIX,
                                    RADAR_LOCAL_SAMPLE_SIZE,
                                ) { frame ->
                                    updatesMutex.withLock {
                                        localFrames = timeline.merge(regional, localFrames + frame)
                                            .filter { it.validAt < regional.validAt }
                                        send(snapshot.copy(radarFrames = wideFrames, radarRegionalFrames = localFrames))
                                    }
                                }
                            }
                        } ?: Result.success(emptyList())
                    }
                    val wideResult = wideRequest.await()
                    val localResult = localRequest.await()
                    send(
                        snapshot.copy(
                            radarFrames = wideResult.getOrElse { snapshot.radarFrames },
                            radarRegionalFrames = localResult.getOrElse { snapshot.radarRegionalFrames },
                            notice = snapshot.notice.appendFailures(
                                wideResult.exceptionOrNull()?.let {
                                    "廣域雷達歷史失敗：${it.message ?: "未知錯誤"}"
                                },
                                localResult.exceptionOrNull()?.let {
                                    "台灣雷達歷史失敗：${it.message ?: "未知錯誤"}"
                                },
                            ),
                        ),
                    )
                }

                WeatherHistoryKind.CLOUD -> {
                    val taiwanCurrent = snapshot.cloudRegionalFrames.maxByOrNull(WeatherGrid::validAt)
                    val eastAsiaCurrent = snapshot.cloudFrames.maxByOrNull(WeatherGrid::validAt)
                    if (taiwanCurrent == null && eastAsiaCurrent == null) {
                        send(snapshot)
                        return@channelFlow
                    }
                    val timeline = ObservedWeatherTimeline()
                    val updatesMutex = Mutex()
                    var taiwanFrames = snapshot.cloudRegionalFrames
                    var eastAsiaFrames = snapshot.cloudFrames
                    suspend fun publishCloudUpdate() {
                        val harmonizedTaiwan = if (taiwanFrames.isNotEmpty() && eastAsiaFrames.isNotEmpty()) {
                            withContext(PREPROCESSING_DISPATCHER) {
                                cloudGridHarmonizer.harmonize(taiwanFrames, eastAsiaFrames)
                            }
                        } else {
                            taiwanFrames
                        }
                        send(
                            snapshot.copy(
                                cloudFrames = eastAsiaFrames,
                                cloudRegionalFrames = harmonizedTaiwan,
                            ),
                        )
                    }
                    val taiwanRequest = async {
                        taiwanCurrent?.let {
                            catchingCancellable {
                                fetchSatelliteHistory(
                                    it,
                                    TAIWAN_CLOUD_DATASET_ID,
                                    TAIWAN_CLOUD_FILE_PREFIX,
                                    CLOUD_LOCAL_MAX_DIMENSION,
                                ) { frame ->
                                    updatesMutex.withLock {
                                        taiwanFrames = timeline.merge(it, taiwanFrames + frame)
                                        publishCloudUpdate()
                                    }
                                }
                            }
                        }
                    }
                    val eastAsiaRequest = async {
                        eastAsiaCurrent?.let {
                            catchingCancellable {
                                fetchSatelliteHistory(
                                    it,
                                    EAST_ASIA_CLOUD_DATASET_ID,
                                    EAST_ASIA_CLOUD_FILE_PREFIX,
                                    CLOUD_EAST_ASIA_MAX_DIMENSION,
                                ) { frame ->
                                    updatesMutex.withLock {
                                        eastAsiaFrames = timeline.merge(it, eastAsiaFrames + frame)
                                        publishCloudUpdate()
                                    }
                                }
                            }
                        }
                    }
                    val taiwanResult = taiwanRequest.await()
                    val eastAsiaResult = eastAsiaRequest.await()
                    eastAsiaFrames = eastAsiaResult?.getOrElse { snapshot.cloudFrames }
                        ?: snapshot.cloudFrames
                    taiwanFrames = taiwanResult?.getOrElse { snapshot.cloudRegionalFrames }
                        ?: snapshot.cloudRegionalFrames
                    val harmonizedTaiwan = if (taiwanFrames.isNotEmpty() && eastAsiaFrames.isNotEmpty()) {
                        withContext(PREPROCESSING_DISPATCHER) {
                            cloudGridHarmonizer.harmonize(taiwanFrames, eastAsiaFrames)
                        }
                    } else {
                        taiwanFrames
                    }
                    send(
                        snapshot.copy(
                            cloudFrames = eastAsiaFrames,
                            cloudRegionalFrames = harmonizedTaiwan,
                            notice = snapshot.notice.appendFailures(
                                taiwanResult?.exceptionOrNull()?.let {
                                    "台灣衛星歷史失敗：${it.message ?: "未知錯誤"}"
                                },
                                eastAsiaResult?.exceptionOrNull()?.let {
                                    "東亞衛星歷史失敗：${it.message ?: "未知錯誤"}"
                                },
                            ),
                        ),
                    )
                }
            }
        }

    private suspend fun fetchModelWindGrid(): WindGrid = withContext(Dispatchers.IO) {
        val response = execute(Request.Builder().url(MODEL_WIND_URL).head().build())
        val contentLength = response.use {
            check(it.isSuccessful) { "CWA M-A0064 回應 ${it.code}" }
            it.header("Content-Length")?.toLongOrNull()
                ?.takeIf { length -> length in 1..MAX_GRIB_FILE_BYTES }
                ?: error("CWA M-A0064 檔案長度無效")
        }
        suspend fun decodeRanges(ranges: ModelWindRanges): WindGrid = coroutineScope {
            check(ranges.east.last < contentLength && ranges.north.last < contentLength)
            val eastBytesRequest = async { fetchRange(ranges.east) }
            val northBytesRequest = async { fetchRange(ranges.north) }
            val eastBytes = eastBytesRequest.await()
            val northBytes = northBytesRequest.await()
            val processingContext = currentCoroutineContext()
            val eastRequest = async(PREPROCESSING_DISPATCHER) {
                gribWindParser.decode(eastBytes, WIND_DECODE_BOUNDS) {
                    processingContext.ensureActive()
                }
            }
            val northRequest = async(PREPROCESSING_DISPATCHER) {
                gribWindParser.decode(northBytes, WIND_DECODE_BOUNDS) {
                    processingContext.ensureActive()
                }
            }
            gribWindParser.combine(eastRequest.await(), northRequest.await())
        }
        val preferredRanges = cachedModelWindRanges ?: STABLE_MODEL_WIND_RANGES
        catchingCancellable { decodeRanges(preferredRanges) }.getOrNull()?.let { return@withContext it }

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

    private suspend fun fetchRange(range: LongRange): ByteArray {
        val request = Request.Builder()
            .url(MODEL_WIND_URL)
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()
        return execute(request).use { response ->
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
        onFrame: suspend (WeatherGrid) -> Unit,
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
                historyLoadSemaphore.withPermit {
                    val frame = catchingCancellable {
                        val metadata = radarMetadataForHistory(record, currentMetadata)
                        decodeRadarImage(metadata, datasetId, sampleSize)
                    }
                        .getOrNull()
                        ?.takeIf(WeatherGrid::isObservedFrame)
                        ?.takeIf { it.validAt < current.validAt }
                    frame?.let { onFrame(it) }
                    frame
                }
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
        onFrame: suspend (WeatherGrid) -> Unit,
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
                historyLoadSemaphore.withPermit {
                    val frame = catchingCancellable {
                        val metadata = satelliteMetadataForHistory(record, currentMetadata)
                        decodeSatelliteImage(metadata, datasetId, maxGridDimension)
                    }
                        .getOrNull()
                        ?.takeIf(WeatherGrid::isObservedFrame)
                        ?.takeIf { it.validAt < current.validAt }
                    frame?.let { onFrame(it) }
                    frame
                }
            }
        }.awaitAll().filterNotNull()
        (historical + current)
            .filter(WeatherGrid::isObservedFrame)
            .filter { it.validAt <= current.validAt }
            .distinctBy(WeatherGrid::validAt)
            .sortedBy { it.validAt }
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT)
    }

    private suspend fun radarMetadataForHistory(
        record: CwaHistoryRecord,
        current: CwaRadarImageMetadata,
    ): CwaRadarImageMetadata = if (record.url.substringBefore('?').endsWith(".png", ignoreCase = true)) {
        current.copy(observedAt = record.observedAt ?: current.observedAt, productUrl = record.url)
    } else {
        request(record.url).use { radarImageParser.parseMetadata(it.string()) }
    }

    private suspend fun satelliteMetadataForHistory(
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
            val processingContext = currentCoroutineContext()
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
                    metadata,
                    image.width,
                    image.height,
                    pixels,
                    datasetId,
                    maxGridDimension,
                ) {
                    processingContext.ensureActive()
                }
            } finally {
                image.recycle()
            }
        }
    }

    private suspend fun fetchQuantitativeForecastFrames(): List<WeatherGrid> = coroutineScope {
        QUANTITATIVE_FORECAST_DATASET_IDS.mapIndexed { index, datasetId ->
            async {
                quantitativeLoadSemaphore.withPermit {
                    val (metadata, bytes) = withContext(Dispatchers.IO) {
                        val loadedMetadata = fetchBody(datasetId, "JSON").use {
                            quantitativeForecastParser.parseMetadata(it.string())
                        }
                        loadedMetadata to request(loadedMetadata.productUrl).use { it.bytes() }
                    }
                    withContext(PREPROCESSING_DISPATCHER) {
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
            }
        }.awaitAll()
    }

    private fun decodeSampleSize(width: Int, height: Int, targetDimension: Int): Int {
        var sampleSize = 1
        val largestDimension = maxOf(width, height)
        while (largestDimension / (sampleSize * 2) >= targetDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private suspend fun fetchHistoryRecords(datasetId: String, currentAt: java.time.Instant): List<CwaHistoryRecord> {
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
        return catchingCancellable { request(url.toString()).use { historyParser.parse(it.string()) } }
            .getOrDefault(emptyList())
            .filter { record -> record.observedAt?.let { it < currentAt } != false }
            .takeLast(OBSERVATION_HISTORY_FRAME_COUNT - 1)
    }

    private suspend fun fetchAnimationRecords(
        listUrl: String,
        filePrefix: String,
        baseUrl: String,
        datasetId: String,
        currentAt: java.time.Instant,
    ): List<CwaHistoryRecord> {
        val animationRecords = catchingCancellable {
            val cached = animationScriptCache[listUrl]
            val script = cached ?: animationCacheMutex.withLock {
                animationScriptCache[listUrl] ?: request(listUrl).use { it.string() }
                    .also { animationScriptCache[listUrl] = it }
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
        suspend fun fetch(elementFilter: Boolean): List<WindObservation> {
            val builder = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$WIND_DATASET_ID".toHttpUrl()
                .newBuilder()
                .addQueryParameter("Authorization", apiKey)
                .addQueryParameter("format", "JSON")
            if (elementFilter) {
                builder.addQueryParameter("elementName", "WindDirection,WindSpeed")
            }
            return request(builder.build().toString()).use { windParser.parse(it.string()) }
        }

        catchingCancellable { fetch(elementFilter = true) }
            .getOrElse { filteredError ->
                catchingCancellable { fetch(elementFilter = false) }
                    .getOrElse { unfilteredError ->
                        unfilteredError.addSuppressed(filteredError)
                        throw unfilteredError
                    }
            }
    }

    private suspend fun fetchBody(datasetId: String, format: String): okhttp3.ResponseBody {
        val url = "https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", format)
            .build()
        return request(url.toString())
    }

    private suspend fun fetchForecastJson(datasetId: String): String {
        val url = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$datasetId".toHttpUrl()
            .newBuilder()
            .addQueryParameter("Authorization", apiKey)
            .addQueryParameter("format", "JSON")
            .build()
        return request(url.toString()).use { it.string() }
    }

    private suspend fun request(url: String): okhttp3.ResponseBody {
        val request = Request.Builder().url(url).build()
        val response = execute(request)
        if (!response.isSuccessful) {
            response.close()
            error("CWA 回應 ${response.code}")
        }
        return response.body
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            },
        )
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        const val WIND_DATASET_ID = "O-A0001-001"
        const val CURRENT_WEATHER_DATASET_ID = "O-A0003-001"
        const val CURRENT_WEATHER_ELEMENTS = "Weather,AirTemperature"
        const val CURRENT_WEATHER_CANDIDATE_COUNT = 8
        const val MAX_CURRENT_WEATHER_DISTANCE_KM = 100.0
        const val MODEL_WIND_URL = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Model/M-A0064-000.grb2"
        const val GRIB_HEADER_RANGE_BYTES = 256L
        const val MAX_GRIB_MESSAGES = 512
        const val MAX_GRIB_FILE_BYTES = 512L * 1024L * 1024L
        val WIND_DECODE_BOUNDS = com.rton.howstheweather.domain.GeoBounds(20.0, 117.5, 27.0, 124.5)
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
        const val EAST_ASIA_CLOUD_FILE_PREFIX = "LCC_IR1_Gray_1000-"
        const val TAIWAN_CLOUD_FILE_PREFIX = "TWI_IR1_Gray_800-"
        const val RADAR_WIDE_SAMPLE_SIZE = 8
        const val RADAR_LOCAL_SAMPLE_SIZE = 4
        const val CLOUD_EAST_ASIA_MAX_DIMENSION = 320
        const val CLOUD_LOCAL_MAX_DIMENSION = 400
        const val SATELLITE_DECODE_OVERSAMPLE = 2
        const val HISTORY_DOWNLOAD_CONCURRENCY = 3
        const val QUANTITATIVE_DOWNLOAD_CONCURRENCY = 2
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
        val PREPROCESSING_DISPATCHER = Dispatchers.Default.limitedParallelism(2)
    }
}

private data class ModelWindRanges(val east: LongRange, val north: LongRange)

private sealed interface WindLoadResult {
    data class Observations(val result: Result<List<WindObservation>>) : WindLoadResult
    data class Model(val result: Result<WindGrid>) : WindLoadResult
}

private fun String?.appendFailures(vararg failures: String?): String? {
    val detail = failures.filterNotNull().takeIf(List<String>::isNotEmpty)?.joinToString("；") ?: return this
    return listOfNotNull(this, "CWA $detail").joinToString("；")
}

private suspend fun <T> catchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

internal fun resolveAreaForecastResults(
    results: List<Result<AreaForecast?>>,
    target: GeoPoint,
    parser: CwaAreaForecastParser,
    failureMessage: String,
): AreaForecast? {
    results.mapNotNull { it.getOrNull() }.minByOrNull {
        parser.distanceKm(target, it.areaCoordinate)
    }?.let { return it }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    if (results.isNotEmpty() && failures.size == results.size) {
        val primary = failures.first()
        failures.drop(1).filter { it !== primary }.forEach(primary::addSuppressed)
        throw IllegalStateException(failureMessage, primary)
    }
    return null
}
