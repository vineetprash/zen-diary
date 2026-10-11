package app.zendiary

import app.zendiary.domain.StreakCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {
    private val today = LocalDate.of(2026, 10, 11)
    private fun days(vararg back: Int) = back.map { today.minusDays(it.toLong()) }.toSet()

    @Test fun consecutiveDaysCount() =
        assertEquals(3, StreakCalculator.compute(days(0, 1, 2), today, false).current)

    @Test fun emptyTodayDoesNotBreakStreak() =
        assertEquals(2, StreakCalculator.compute(days(1, 2), today, false).current)

    @Test fun missedDayBreaksWithoutGrace() =
        assertEquals(1, StreakCalculator.compute(days(0, 2, 3), today, false).current)

    @Test fun graceForgivesOneMissedDay() =
        assertEquals(3, StreakCalculator.compute(days(0, 2, 3), today, true).current)

    @Test fun graceDoesNotForgiveTwoInARow() =
        assertEquals(1, StreakCalculator.compute(days(0, 3, 4), today, true).current)

    @Test fun bestStreakIsTracked() =
        assertEquals(4, StreakCalculator.compute(days(0, 10, 11, 12, 13), today, false).best)
}
