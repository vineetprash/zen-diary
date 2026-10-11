package app.zendiary

import app.zendiary.io.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun roundTripsQuotesCommasAndNewlines() {
        val tricky = "He said \"hi\", then\nleft"
        val line = listOf("a", tricky, "c").joinToString(",") { Csv.escape(it) }
        assertEquals(listOf(listOf("a", tricky, "c")), Csv.parse(line + "\n"))
    }
}
