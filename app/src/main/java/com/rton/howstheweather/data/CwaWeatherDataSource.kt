package com.rton.howstheweather.data

import android.graphics.BitmapFactory
import com.rton.howstheweather.diagnostics.StartupDiagnostics
import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.AreaForecast
import com.rton.howstheweather.domain.CurrentWeatherObservation
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.TropicalCyclone
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
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CwaWeatherDataSource(
    private val apiKey: String,
    client: OkHttpClient = defaultClient(),
    /** Disk cache for ETag/Last-Modified products (fileapi files on S3, product images). */
    httpCacheDirectory: File? = null,
    /** TTL cache for REST products that carry no HTTP validators. */
    responseCacheDirectory: File? = null,
    private val parser: CwaJsonGridParser = CwaJsonGridParser(),
    private val radarImageParser: CwaRadarImageParser = CwaRadarImageParser(),
    private val quantitativeForecastParser: CwaQuantitativeForecastImageParser = CwaQuantitativeForecastImageParser(),
    satelliteImageParser: CwaSatelliteImageParser = CwaSatelliteImageParser(),
    private val historyParser: CwaHistoryMetadataParser = CwaHistoryMetadataParser(),
    private val animationFrameParser: CwaAnimationFrameParser = CwaAnimationFrameParser(),
    private val cloudGridHarmonizer: CloudGridHarmonizer = CloudGridHarmonizer(),
    private val windParser: CwaWindObservationParser = CwaWindObservationParser(),
    private val currentWeatherParser: CwaCurrentWeatherParser = CwaCurrentWeatherParser(),
    private val gribWindParser: CwaGrib2WindParser = CwaGrib2WindParser(),
    private val areaForecastParser: CwaAreaForecastParser = CwaAreaForecastParser(),
    private val tropicalCycloneParser: CwaTropicalCycloneParser = CwaTropicalCycloneParser(),
    satelliteImageParserFactory: () -> CwaSatelliteImageParser = { satelliteImageParser },
    private val clock: Clock = Clock.systemUTC(),
) : WeatherDataSource {
    // First accessed by satellite acquisition on IO, never during ViewModel construction.
    private val satelliteImageParser by lazy(satelliteImageParserFactory)
    /**
     * The key travels in the Authorization header, never in the URL, so it is not
     * written into the HTTP cache journal. OkHttp drops this header itself when
     * fileapi redirects to the public S3 host.
     */
    private val client: OkHttpClient = client.newBuilder()
        .apply {
            interceptors().add(0) { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.url.host == CWA_OPENDATA_HOST && request.header("Authorization") == null) {
                        request.newBuilder().header("Authorization", apiKey).build()
                    } else {
                        request
                    },
                )
            }
        }
        .apply { httpCacheDirectory?.let { cache(Cache(it, HTTP_CACHE_MAX_BYTES)) } }
        .build()

    /** REST bodies have no validators, so storing them in the HTTP cache would only churn the disk. */
    private val uncachedClient: OkHttpClient = this.client.newBuilder().cache(null).build()
    private val responseCache = CwaResponseCache(responseCacheDirectory)
    private val historyLoadSemaphore = Semaphore(HISTORY_DOWNLOAD_CONCURRENCY)
    private val quantitativeLoadSemaphore = Semaphore(QUANTITATIVE_DOWNLOAD_CONCURRENCY)
    private val animationCacheMutex = Mutex()
    private val animationScriptCache = ConcurrentHashMap<String, AnimationScript>()
    private val countyForecastLocationsMutex = Mutex()
    @Volatile
    private var countyForecastLocations: List<CwaForecastLocation>? = null

    init { require(apiKey.isNotBlank()) }

    override suspend fun load(target: GeoPoint): WeatherSnapshot {
        val radar = StartupDiagnostics.measureSuspending("weather.radar.load") {
            fetchRadarFrame(WIDE_RADAR_DATASET_ID, RADAR_WIDE_SAMPLE_SIZE)
        }
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

    override suspend fun loadTropicalCyclones(): List<TropicalCyclone> =
        fetchRestJson(CwaTropicalCycloneParser.DATASET_ID, TROPICAL_CYCLONE_MAX_AGE, tropicalCycloneParser::parse)
            .filter { it.isActiveAt(clock.instant()) }

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
                    fetchRestJson(datasetId, AREA_FORECAST_MAX_AGE) { json ->
                        areaForecastParser.nearestForecast(
                            json = json,
                            target = target,
                            sourceId = datasetId,
                            countyName = county.name,
                        )
                    }
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
                    fetchRestJson(datasetId, WEEKLY_FORECAST_MAX_AGE) { json ->
                        areaForecastParser.nearestWeeklyForecast(
                            json = json,
                            target = target,
                            sourceId = datasetId,
                            countyName = county.name,
                        )
                    }
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
            countyForecastLocations ?: fetchRestJson(
                NATIONAL_THREE_DAY_FORECAST_ID,
                COUNTY_LOCATIONS_MAX_AGE,
                areaForecastParser::locations,
            ).also { countyForecastLocations = it }
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
                fetchSatelliteFrame(CwaSatelliteProduct.TAIWAN)
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
                fetchSatelliteFrame(CwaSatelliteProduct.EAST_ASIA)
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

                // Latest-only clouds: never enqueue historical image downloads.
                WeatherHistoryKind.CLOUD -> send(snapshot.copy(
                    cloudFrames = listOfNotNull(snapshot.cloudFrames.maxByOrNull(WeatherGrid::validAt)),
                    cloudRegionalFrames = listOfNotNull(snapshot.cloudRegionalFrames.maxByOrNull(WeatherGrid::validAt)),
                ))
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
        StartupDiagnostics.measureSuspending("weather.grid.load source=$datasetId") {
            val body = fetchBody(datasetId, "JSON")
            body.use { parser.parse(it.string(), unit, datasetId) }
        }
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
        product: CwaSatelliteProduct,
    ): WeatherGrid {
        return decodeSatelliteImage(fetchSatelliteMetadata(product), product)
    }

    internal suspend fun fetchSatelliteMetadata(
        product: CwaSatelliteProduct,
    ): CwaSatelliteImageMetadata = withContext(Dispatchers.IO) {
        StartupDiagnostics.measureSuspending("satellite.metadata source=${product.datasetId}") {
            fetchBody(product.datasetId, "JSON").use { satelliteImageParser.parseMetadata(it.string()) }
        }
    }

    private suspend fun radarMetadataForHistory(
        record: CwaHistoryRecord,
        current: CwaRadarImageMetadata,
    ): CwaRadarImageMetadata = if (record.url.substringBefore('?').endsWith(".png", ignoreCase = true)) {
        current.copy(observedAt = record.observedAt ?: current.observedAt, productUrl = record.url)
    } else {
        request(record.url).use { radarImageParser.parseMetadata(it.string()) }
    }

    private suspend fun decodeRadarImage(
        metadata: CwaRadarImageMetadata,
        datasetId: String,
        sampleSize: Int,
    ): WeatherGrid {
        val bytes = StartupDiagnostics.measureSuspending("weather.radar.body source=$datasetId") {
            withContext(Dispatchers.IO) { request(metadata.productUrl).use { it.bytes() } }
        }
        return withContext(PREPROCESSING_DISPATCHER) {
            StartupDiagnostics.measure("weather.radar.decode source=$datasetId") {
                // Full-resolution decode + block aggregation (sampleSize = 8 -> 8x8, 4 -> 4x4)
                // preserves thin bands and small convective cells that BitmapFactory.inSampleSize
                // point-sampling would drop or flicker across history frames.
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inScaled = false
                }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: error("CWA 雷達影像解碼失敗")
                try {
                    radarImageParser.toGrid(image, metadata, datasetId, sampleSize)
                } finally {
                    image.recycle()
                }
            }
        }
    }

    private suspend fun decodeSatelliteImage(
        metadata: CwaSatelliteImageMetadata,
        product: CwaSatelliteProduct,
    ): WeatherGrid {
        val bytes = withContext(Dispatchers.IO) { request(metadata.productUrl).use { it.bytes() } }
        return withContext(PREPROCESSING_DISPATCHER) {
            val processingContext = currentCoroutineContext()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth > 1 && bounds.outHeight > 1) { "CWA 衛星影像尺寸無效" }
            product.validateDimensions(bounds.outWidth, bounds.outHeight)
            // Presentation lines must be removed at native resolution before any
            // decoder downsampling can mix their white pixels into real cloud pixels.
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
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
                    product.sourceId,
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

    private suspend fun fetchHistoryRecords(datasetId: String, currentAt: java.time.Instant): List<CwaHistoryRecord> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(TAIPEI_ZONE)
        val url = "https://opendata.cwa.gov.tw/historyapi/v1/getMetadata/$datasetId".toHttpUrl()
            .newBuilder()
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
            animationFrameParser.parse(fetchAnimationScript(listUrl), filePrefix, baseUrl)
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

    /** Share one inventory between regions, but never pin "latest" for the process lifetime. */
    private suspend fun fetchAnimationScript(listUrl: String): String = withContext(Dispatchers.IO) {
        fun cached(): String? = animationScriptCache[listUrl]
            ?.takeIf { clock.instant().isBefore(it.expiresAt) }?.script
        cached() ?: animationCacheMutex.withLock {
            cached() ?: request(listUrl, ANIMATION_LIST_CACHE_CONTROL).use { it.string() }.also {
                animationScriptCache[listUrl] = AnimationScript(it, clock.instant().plus(ANIMATION_LIST_MAX_AGE))
            }
        }
    }

    private data class AnimationScript(val script: String, val expiresAt: Instant)

    private suspend fun fetchWindObservations() = withContext(Dispatchers.IO) {
        suspend fun fetch(elementFilter: Boolean): List<WindObservation> {
            val builder = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/$WIND_DATASET_ID".toHttpUrl()
                .newBuilder()
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
            .addQueryParameter("format", format)
            .build()
        return request(url.toString())
    }

    /**
     * Downloads a REST datastore product, or reuses the copy cached within
     * [maxAge]. Main-safe: the body is read on the IO dispatcher.
     */
    private suspend fun <T> fetchRestJson(datasetId: String, maxAge: Duration, parse: (String) -> T): T =
        responseCache.getOrFetch(
            key = datasetId,
            maxAge = maxAge,
            fetch = {
                val url = "https://$CWA_OPENDATA_HOST/api/v1/rest/datastore/$datasetId".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("format", "JSON")
                    .build()
                withContext(Dispatchers.IO) { request(url.toString()).use { it.string() } }
            },
            parse = parse,
        )

    /** Response bodies must be read off the main thread; callers own that dispatch. */
    private suspend fun request(url: String, cacheControl: CacheControl? = null): okhttp3.ResponseBody {
        val request = Request.Builder().url(url)
            .apply { cacheControl?.let { cacheControl(it) } }.build()
        val response = execute(request)
        if (!response.isSuccessful) {
            response.close()
            error("CWA 回應 ${response.code}")
        }
        return response.body
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        // Never log URLs, query strings, headers, or bodies (which may contain credentials).
        val product = request.url.pathSegments.lastOrNull()
            ?.takeIf { it.matches(Regex("[A-Z]-[A-Z0-9]{5}-[0-9]{3}")) } ?: "asset"
        val stage = "weather.http.headers host=${request.url.host} product=$product"
        val started = StartupDiagnostics.begin(stage)
        val path = request.url.encodedPath
        val restRequest = request.url.host == CWA_OPENDATA_HOST &&
            (path.startsWith("/api/") || path.startsWith("/historyapi/"))
        val call = (if (restRequest) uncachedClient else client).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    StartupDiagnostics.end(stage, started)
                    StartupDiagnostics.event("$stage failed type=${e.javaClass.simpleName}")
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    StartupDiagnostics.end(stage, started)
                    StartupDiagnostics.event(
                        "$stage status=${response.code} cache=${response.cacheResponse != null} " +
                            "network=${response.networkResponse?.code ?: 0}",
                    )
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

        const val CWA_OPENDATA_HOST = "opendata.cwa.gov.tw"
        const val HTTP_CACHE_MAX_BYTES = 48L * 1024L * 1024L
        // CWA issues township forecasts every few hours and typhoon bulletins
        // every 3–6 hours, so these bounds keep data current without polling.
        val AREA_FORECAST_MAX_AGE: Duration = Duration.ofMinutes(30)
        val WEEKLY_FORECAST_MAX_AGE: Duration = Duration.ofHours(1)
        val TROPICAL_CYCLONE_MAX_AGE: Duration = Duration.ofMinutes(30)
        val COUNTY_LOCATIONS_MAX_AGE: Duration = Duration.ofDays(7)

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
        val QUANTITATIVE_FORECAST_DATASET_IDS = listOf(
            "F-C0035-015", "F-C0035-017", "F-C0035-023", "F-C0035-024",
        )
        const val NATIONAL_THREE_DAY_FORECAST_ID = "F-D0047-089"
        const val RADAR_ANIMATION_LIST_URL = "https://www.cwa.gov.tw/Data/js/obs_img/Observe_radar.js"
        const val RADAR_ANIMATION_BASE_URL = "https://www.cwa.gov.tw/Data/radar"
        const val WIDE_RADAR_FILE_PREFIX = "CV1_3600_"
        const val LOCAL_RADAR_FILE_PREFIX = "CV1_TW_3600_"
        const val RADAR_WIDE_SAMPLE_SIZE = 8
        const val RADAR_LOCAL_SAMPLE_SIZE = 4
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
        val ANIMATION_LIST_MAX_AGE: Duration = Duration.ofMinutes(1)
        val ANIMATION_LIST_CACHE_CONTROL: CacheControl = CacheControl.Builder().maxAge(1, TimeUnit.MINUTES).build()
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
