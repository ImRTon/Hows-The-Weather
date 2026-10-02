package com.rton.howstheweather.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapProviderTest {
    @Test
    fun `provider names select the configured base map`() {
        assertEquals(MapProvider.OSM, MapProvider.fromConfig("osm"))
        assertEquals(MapProvider.OSM, MapProvider.fromConfig(" OpenStreetMap "))
        assertEquals(MapProvider.GOOGLE, MapProvider.fromConfig("google"))
        assertEquals(MapProvider.GOOGLE, MapProvider.fromConfig(""))
    }

    @Test
    fun `tile url template expands coordinates, subdomain and retina suffix`() {
        val url = expandTileUrlTemplate(
            "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
            zoom = 12,
            x = 3428,
            y = 1753,
        )
        // (3428 + 1753) mod 4 = 1 -> "b"
        assertEquals("https://b.basemaps.cartocdn.com/dark_all/12/3428/1753@2x.png", url)
    }

    @Test
    fun `template without optional placeholders is left intact`() {
        assertEquals(
            "https://tile.openstreetmap.org/3/4/2.png",
            expandTileUrlTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png", 3, 4, 2),
        )
    }
}
