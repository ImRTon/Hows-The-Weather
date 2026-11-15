package com.rton.howstheweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `latest selection scans a full sized inventory without collecting unrelated frames`() {
        val script = buildString {
            append("""{"img":"TWI_IR1_Gray_800/older.jpg","text":"2026/11/10 19:20"},""")
            repeat(12_000) { index ->
                append("""{"img":"FDK_VIS_Gray_2750/FDK_VIS_Gray_2750-$index.jpg","text":"2026/11/10 19:50"},""")
            }
            append("""{"img":"TWI_IR1_Gray_800/TWI_IR1_Gray_800-2026-11-10-19-40.jpg","text":"2026/11/10 19:40"},""")
            append("""{"img":"TWI_IR1_Gray_800/TWI_IR1_Gray_800-2026-11-10-19-10.jpg","text":"2026/11/10 19:10"}""")
        }
        val record = CwaAnimationFrameParser().parseLatest(script, "TWI_IR1_Gray_800-", "https://www.cwa.gov.tw/Data/satellite")

        assertEquals("2026-11-10T11:40:00Z", record?.observedAt.toString())
        assertTrue(record!!.url.endsWith("19-40.jpg"))
    }

    @Test fun `latest inventory scan can be cancelled before completing`() {
        val script = ("""{"img":"FDK/FDK-001.jpg","text":"2026/11/10 19:40"},""").repeat(1000)
        var checked = 0
        val failure = runCatching {
            CwaAnimationFrameParser().parseLatest(script, "TWI_IR1_Gray_800-", "https://www.cwa.gov.tw") {
                if (++checked == 10) throw java.util.concurrent.CancellationException("cancel scan")
            }
        }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
        assertEquals(10, checked)
    }
}
