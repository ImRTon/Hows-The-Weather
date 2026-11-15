package com.rton.howstheweather.data

import com.rton.howstheweather.domain.GeoBounds

/** Latest-only fileapi products, with masks tied to the native presentation layout. */
internal enum class CwaSatelliteProduct(
    val datasetId: String,
    val imageSize: Int,
    val bounds: GeoBounds,
) {
    TAIWAN(
        "O-C0042-004", 800,
        GeoBounds(19.100625745, 115.976888855, 28.29937425, 126.02300114),
    ),
    EAST_ASIA(
        "O-B0032-003", 1200,
        GeoBounds(0.0, 102.0, 50.0, 155.0),
    );

    // The same-size Taiwan animation still has DIFFERENT cartography.
    val sourceId: String get() = "$datasetId|fileapi-native"

    fun validateDimensions(width: Int, height: Int) {
        check(width == imageSize && height == imageSize) {
            "CWA 衛星原圖版型尺寸不符：${width}×$height，預期 ${imageSize}×$imageSize"
        }
    }
}
