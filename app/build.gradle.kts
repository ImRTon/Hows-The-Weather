import java.util.Properties
import java.io.DataOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

abstract class GenerateEastAsiaProjectionLookup : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val imageWidth = 800
        val imageHeight = 800
        val gridWidth = 320
        val gridHeight = 320
        val south = 0.0
        val west = 102.0
        val north = 50.0
        val east = 155.0

        val radius = 6_371_000.0
        val latitudeOfOrigin = Math.toRadians(0.0)
        val centralLongitude = Math.toRadians(128.5)
        val firstParallel = Math.toRadians(30.0)
        val secondParallel = Math.toRadians(60.0)
        val coneConstant = ln(cos(firstParallel) / cos(secondParallel)) /
            ln(tan(PI / 4.0 + secondParallel / 2.0) / tan(PI / 4.0 + firstParallel / 2.0))
        val factor = cos(firstParallel) *
            tan(PI / 4.0 + firstParallel / 2.0).pow(coneConstant) / coneConstant
        val originRadius = radius * factor /
            tan(PI / 4.0 + latitudeOfOrigin / 2.0).pow(coneConstant)

        fun forward(latitudeDegrees: Double, longitudeDegrees: Double): Pair<Double, Double> {
            val latitude = Math.toRadians(latitudeDegrees.coerceIn(-89.999999, 89.999999))
            val longitude = Math.toRadians(longitudeDegrees)
            val radialDistance = radius * factor /
                tan(PI / 4.0 + latitude / 2.0).pow(coneConstant)
            var longitudeDelta = longitude - centralLongitude
            while (longitudeDelta > PI) longitudeDelta -= 2.0 * PI
            while (longitudeDelta < -PI) longitudeDelta += 2.0 * PI
            val theta = coneConstant * longitudeDelta
            return radialDistance * sin(theta) to originRadius - radialDistance * cos(theta)
        }

        val lowerLeft = forward(-1.503, 102.111)
        val upperRight = forward(48.589, 155.270)
        val indices = IntArray(gridWidth * gridHeight) { index ->
            val x = index % gridWidth
            val y = index / gridWidth
            val latitude = north - y.toDouble() / (gridHeight - 1) * (north - south)
            val longitude = west + x.toDouble() / (gridWidth - 1) * (east - west)
            val projected = forward(latitude, longitude)
            val normalizedX = (projected.first - lowerLeft.first) / (upperRight.first - lowerLeft.first)
            val normalizedY = (projected.second - lowerLeft.second) / (upperRight.second - lowerLeft.second)
            if (normalizedX !in 0.0..1.0 || normalizedY !in 0.0..1.0) {
                -1
            } else {
                val pixelX = (normalizedX * (imageWidth - 1)).toInt()
                val pixelY = ((1.0 - normalizedY) * (imageHeight - 1)).toInt()
                if (pixelX in 0 until imageWidth && pixelY in 0 until imageHeight) {
                    pixelY * imageWidth + pixelX
                } else {
                    -1
                }
            }
        }

        val rawDirectory = outputDirectory.get().dir("raw").asFile.apply { mkdirs() }
        DataOutputStream(rawDirectory.resolve("east_asia_projection_lookup.bin").outputStream().buffered()).use { output ->
            output.writeInt(0x45415031) // EAP1
            output.writeInt(1)
            output.writeInt(imageWidth)
            output.writeInt(imageHeight)
            output.writeInt(gridWidth)
            output.writeInt(gridHeight)
            output.writeDouble(south)
            output.writeDouble(west)
            output.writeDouble(north)
            output.writeDouble(east)
            output.writeInt(indices.size)
            indices.forEach(output::writeInt)
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun configValue(key: String, default: String = ""): String =
    localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val mapProvider = when (val value = configValue("MAP_PROVIDER", "google").lowercase()) {
    "google", "google_maps", "googlemaps" -> "google"
    "osm", "openstreetmap", "open_street_map" -> "osm"
    else -> throw GradleException("Unsupported MAP_PROVIDER '$value'. Use 'google' or 'osm'.")
}

android {
    namespace = "com.rton.howstheweather"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rton.howstheweather"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "CWA_API_KEY", buildConfigString(configValue("CWA_API_KEY")))
        buildConfigField("String", "MAP_ID", buildConfigString(configValue("MAP_ID", "DEMO_MAP_ID")))
        buildConfigField("String", "MOENV_API_KEY", buildConfigString(configValue("MOENV_API_KEY")))
        buildConfigField("String", "MAP_PROVIDER", buildConfigString(mapProvider))
        buildConfigField(
            "String",
            "OSM_TILE_URL_LIGHT",
            buildConfigString(
                configValue("OSM_TILE_URL_LIGHT", "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png"),
            ),
        )
        buildConfigField(
            "String",
            "OSM_TILE_URL_DARK",
            buildConfigString(
                configValue("OSM_TILE_URL_DARK", "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"),
            ),
        )
        buildConfigField(
            "String",
            "OSM_TILE_ATTRIBUTION",
            buildConfigString(configValue("OSM_TILE_ATTRIBUTION", "© OpenStreetMap contributors © CARTO")),
        )
        manifestPlaceholders["MAPS_API_KEY"] = configValue("MAPS_API_KEY")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalizedName = variant.name.replaceFirstChar { it.uppercase() }
        val generator = tasks.register<GenerateEastAsiaProjectionLookup>(
            "generate${capitalizedName}EastAsiaProjectionLookup",
        ) {
            outputDirectory.set(
                layout.buildDirectory.dir("generated/res/eastAsiaProjection/${variant.name}"),
            )
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            generator,
            GenerateEastAsiaProjectionLookup::outputDirectory,
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("com.google.maps.android:maps-compose:6.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
