package com.rton.howstheweather.data

import com.rton.howstheweather.domain.ForecastPoint
import com.rton.howstheweather.domain.GeoBounds
import com.rton.howstheweather.domain.GeoPoint
import com.rton.howstheweather.domain.WeatherGrid
import com.rton.howstheweather.domain.WeatherUnit
import com.rton.howstheweather.domain.WindObservation
import com.rton.howstheweather.domain.WindGrid
import com.rton.howstheweather.domain.WindProvenance
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A last-known-good parsed cache. It never stores API URLs or credentials. */
class WeatherSnapshotCache(
    private val directory: File,
    private val maxAge: Duration = Duration.ofHours(6),
) {
    private val cacheFile get() = File(directory, FILE_NAME)

    suspend fun read(now: Instant = Instant.now()): WeatherSnapshot? = withContext(Dispatchers.IO) {
        val file = cacheFile.takeIf(File::isFile) ?: return@withContext null
        runCatching {
            DataInputStream(GZIPInputStream(BufferedInputStream(file.inputStream()))).use { input ->
                require(input.readInt() == MAGIC) { "Weather cache magic mismatch" }
                require(input.readInt() == VERSION) { "Weather cache version mismatch" }
                readSnapshot(input).takeIf { snapshot ->
                    val age = Duration.between(snapshot.issuedAt, now)
                    age >= MAX_FUTURE_SKEW.negated() && age <= maxAge
                }
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    suspend fun write(snapshot: WeatherSnapshot) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temporary = File(directory, "$FILE_NAME.tmp")
        runCatching {
            DataOutputStream(GZIPOutputStream(BufferedOutputStream(temporary.outputStream()))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                writeSnapshot(output, snapshot)
            }
            if (cacheFile.exists() && !cacheFile.delete()) error("Unable to replace weather cache")
            check(temporary.renameTo(cacheFile)) { "Unable to publish weather cache" }
        }.onFailure { temporary.delete() }.getOrThrow()
    }

    private fun writeSnapshot(output: DataOutputStream, snapshot: WeatherSnapshot) {
        writeGrid(output, snapshot.radar)
        output.writeBoolean(snapshot.radarRegional != null)
        snapshot.radarRegional?.let { writeGrid(output, it) }
        writeList(output, snapshot.radarFrames) { writeGrid(output, it) }
        writeList(output, snapshot.radarRegionalFrames) { writeGrid(output, it) }
        writeList(output, snapshot.rainForecast) { writeGrid(output, it) }
        writeList(output, snapshot.quantitativeForecastFrames) { writeGrid(output, it) }
        writeList(output, snapshot.cloudFrames) { writeGrid(output, it) }
        writeList(output, snapshot.cloudRegionalFrames) { writeGrid(output, it) }
        writeList(output, snapshot.forecastAtTarget) {
            output.writeInt(it.minutesFromNow)
            output.writeBoolean(it.millimetersPerHour != null)
            it.millimetersPerHour?.let(output::writeFloat)
        }
        output.writeBoolean(snapshot.windGrid != null)
        snapshot.windGrid?.let { writeWindGrid(output, it) }
        writeList(output, snapshot.winds) {
            output.writeUTF(it.stationName)
            output.writeDouble(it.coordinate.latitude)
            output.writeDouble(it.coordinate.longitude)
            output.writeFloat(it.speedMetersPerSecond)
            output.writeFloat(it.directionDegrees)
            output.writeLong(it.observedAt.epochSecond)
        }
        output.writeInt(snapshot.windProvenance.ordinal)
        output.writeLong(snapshot.issuedAt.epochSecond)
        output.writeBoolean(snapshot.hourlyAccumulationAtTarget != null)
        snapshot.hourlyAccumulationAtTarget?.let(output::writeFloat)
    }

    private fun readSnapshot(input: DataInputStream): WeatherSnapshot {
        val radar = readGrid(input)
        val radarRegional = if (input.readBoolean()) readGrid(input) else null
        val radarFrames = readList(input) { readGrid(input) }
        val radarRegionalFrames = readList(input) { readGrid(input) }
        val forecasts = readList(input) { readGrid(input) }
        require(forecasts.isNotEmpty()) { "Cached forecast is empty" }
        val quantitativeForecasts = readList(input) { readGrid(input) }
        val clouds = readList(input) { readGrid(input) }
        val regionalClouds = readList(input) { readGrid(input) }
        val points = readList(input) {
            ForecastPoint(input.readInt(), if (input.readBoolean()) input.readFloat() else null)
        }
        val windGrid = if (input.readBoolean()) readWindGrid(input) else null
        val winds = readList(input) {
            WindObservation(
                stationName = input.readUTF(),
                coordinate = GeoPoint(input.readDouble(), input.readDouble()),
                speedMetersPerSecond = input.readFloat(),
                directionDegrees = input.readFloat(),
                observedAt = Instant.ofEpochSecond(input.readLong()),
            )
        }
        val windProvenance = WindProvenance.entries.getOrNull(input.readInt())
            ?: error("Unknown wind provenance")
        val issuedAt = Instant.ofEpochSecond(input.readLong())
        val hourlyAmount = if (input.readBoolean()) input.readFloat() else null
        return WeatherSnapshot(
            radar = radar,
            radarRegional = radarRegional,
            radarFrames = radarFrames,
            radarRegionalFrames = radarRegionalFrames,
            rainForecast = forecasts,
            quantitativeForecastFrames = quantitativeForecasts,
            cloudFrames = clouds,
            cloudRegionalFrames = regionalClouds,
            forecastAtTarget = points,
            windGrid = windGrid,
            winds = winds,
            windProvenance = windProvenance,
            issuedAt = issuedAt,
            hourlyAccumulationAtTarget = hourlyAmount,
        )
    }

    private fun writeGrid(output: DataOutputStream, grid: WeatherGrid) {
        output.writeInt(grid.width)
        output.writeInt(grid.height)
        output.writeInt(grid.unit.ordinal)
        output.writeDouble(grid.bounds.south)
        output.writeDouble(grid.bounds.west)
        output.writeDouble(grid.bounds.north)
        output.writeDouble(grid.bounds.east)
        output.writeDouble(grid.resolutionKm)
        output.writeLong(grid.validAt.epochSecond)
        output.writeUTF(grid.sourceId)
        grid.values.forEach(output::writeFloat)
    }

    private fun readGrid(input: DataInputStream): WeatherGrid {
        val width = input.readInt()
        val height = input.readInt()
        require(width in 2..MAX_GRID_DIMENSION && height in 2..MAX_GRID_DIMENSION)
        val valueCount = Math.multiplyExact(width, height)
        require(valueCount <= MAX_GRID_VALUES)
        val unit = WeatherUnit.entries.getOrNull(input.readInt()) ?: error("Unknown weather unit")
        val bounds = GeoBounds(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
        require(bounds.south.isFinite() && bounds.west.isFinite() && bounds.north.isFinite() && bounds.east.isFinite())
        require(bounds.south < bounds.north && bounds.west < bounds.east)
        val resolutionKm = input.readDouble()
        require(resolutionKm.isFinite() && resolutionKm > 0.0)
        val validAt = Instant.ofEpochSecond(input.readLong())
        val sourceId = input.readUTF().also { require(it.isNotBlank()) }
        return WeatherGrid(
            width = width,
            height = height,
            values = FloatArray(valueCount) { input.readFloat() },
            unit = unit,
            bounds = bounds,
            resolutionKm = resolutionKm,
            validAt = validAt,
            sourceId = sourceId,
        )
    }

    private fun writeWindGrid(output: DataOutputStream, grid: WindGrid) {
        output.writeInt(grid.width)
        output.writeInt(grid.height)
        output.writeDouble(grid.earthRadiusMeters)
        output.writeDouble(grid.latitudeOfOriginDegrees)
        output.writeDouble(grid.centralLongitudeDegrees)
        output.writeDouble(grid.firstStandardParallelDegrees)
        output.writeDouble(grid.secondStandardParallelDegrees)
        output.writeDouble(grid.firstPointX)
        output.writeDouble(grid.firstPointY)
        output.writeDouble(grid.spacingXMeters)
        output.writeDouble(grid.spacingYMeters)
        output.writeLong(grid.validAt.epochSecond)
        output.writeUTF(grid.sourceId)
        grid.eastMetersPerSecond.forEach(output::writeFloat)
        grid.northMetersPerSecond.forEach(output::writeFloat)
    }

    private fun readWindGrid(input: DataInputStream): WindGrid {
        val width = input.readInt()
        val height = input.readInt()
        require(width in 2..MAX_GRID_DIMENSION && height in 2..MAX_GRID_DIMENSION)
        val valueCount = Math.multiplyExact(width, height)
        require(valueCount <= MAX_GRID_VALUES)
        val earthRadius = input.readDouble()
        val latitudeOfOrigin = input.readDouble()
        val centralLongitude = input.readDouble()
        val firstParallel = input.readDouble()
        val secondParallel = input.readDouble()
        val firstPointX = input.readDouble()
        val firstPointY = input.readDouble()
        val spacingX = input.readDouble()
        val spacingY = input.readDouble()
        val validAt = Instant.ofEpochSecond(input.readLong())
        val sourceId = input.readUTF().also { require(it.isNotBlank()) }
        return WindGrid(
            width = width,
            height = height,
            eastMetersPerSecond = FloatArray(valueCount) { input.readFloat() },
            northMetersPerSecond = FloatArray(valueCount) { input.readFloat() },
            earthRadiusMeters = earthRadius,
            latitudeOfOriginDegrees = latitudeOfOrigin,
            centralLongitudeDegrees = centralLongitude,
            firstStandardParallelDegrees = firstParallel,
            secondStandardParallelDegrees = secondParallel,
            firstPointX = firstPointX,
            firstPointY = firstPointY,
            spacingXMeters = spacingX,
            spacingYMeters = spacingY,
            validAt = validAt,
            sourceId = sourceId,
        )
    }

    private inline fun <T> writeList(output: DataOutputStream, values: List<T>, write: (T) -> Unit) {
        require(values.size <= MAX_LIST_SIZE)
        output.writeInt(values.size)
        values.forEach(write)
    }

    private inline fun <T> readList(input: DataInputStream, read: () -> T): List<T> {
        val size = input.readInt()
        require(size in 0..MAX_LIST_SIZE)
        return List(size) { read() }
    }

    private companion object {
        const val FILE_NAME = "last-known-good.bin.gz"
        const val MAGIC = 0x48545731 // HTW1
        // Version 9 removes synthetic-data provenance fields. Older caches are
        // discarded so no previously cached demonstration wind can survive.
        const val VERSION = 9
        // O-A0001-001 currently contains roughly 900 stations. Keep the bound
        // finite for corrupt-cache protection while allowing a complete
        // official observation set to survive process restarts.
        const val MAX_LIST_SIZE = 2_048
        const val MAX_GRID_DIMENSION = 4096
        const val MAX_GRID_VALUES = 8_000_000
        val MAX_FUTURE_SKEW: Duration = Duration.ofHours(2)
    }
}
