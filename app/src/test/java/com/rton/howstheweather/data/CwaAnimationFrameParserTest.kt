package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CwaAnimationFrameParserTest {
    @Test fun `extracts only requested official animation product and sorts oldest first`() {
        val records = CwaAnimationFrameParser().parse(
            script = """
                0:{"img":'CV1_TW_3600_202607132230.png', 'text':'2026/07/13 22:30'},
                1:{"img":'CV1_3600_202607132220.png', 'text':'2026/07/13 22:20'},
                2:{"img":'CV1_3600_202607132210.png', 'text':'2026/07/13 22:10'}
            """.trimIndent(),
            filePrefix = "CV1_3600_",
            baseUrl = "https://www.cwa.gov.tw/Data/radar/",
        )

        assertEquals(2, records.size)
        assertEquals("2026-07-13T14:10:00Z", records.first().observedAt.toString())
        assertEquals("https://www.cwa.gov.tw/Data/radar/CV1_3600_202607132220.png", records.last().url)
    }

    @Test fun `keeps satellite subdirectory in resolved url`() {
        val records = CwaAnimationFrameParser().parse(
            script = """0:{"img":'TWI_IR1_Gray_800/TWI_IR1_Gray_800-2026-07-13-22-20.jpg', 'text':'2026/07/13 22:20'}""",
            filePrefix = "TWI_IR1_Gray_800-",
            baseUrl = "https://www.cwa.gov.tw/Data/satellite",
        )

        assertEquals(
            "https://www.cwa.gov.tw/Data/satellite/TWI_IR1_Gray_800/TWI_IR1_Gray_800-2026-07-13-22-20.jpg",
            records.single().url,
        )
    }
}
