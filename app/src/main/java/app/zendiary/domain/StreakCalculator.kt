package app.zendiary.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class DayStatus { LOGGED, GRACE, MISSED, PENDING }
data class StreakDay(val date: LocalDate, val status: DayStatus, val mood: Float?)
data class StreakInfo(
    val current: Int,
    val best: Int,
    val last10: List<StreakDay>,
    val nextMilestone: Int,
    val loggedToday: Boolean,
    val totalDays: Int,
) {
    companion object { val EMPTY = StreakInfo(0, 0, emptyList(), 7, false, 0) }
}

object Milestones { val all = listOf(3, 7, 14, 30, 60, 100, 200, 365) }

/**
 * Streak rules: a day counts when at least one entry exists. With [grace] on, one missed day per
 * rolling 7 days is forgiven (it keeps the streak alive but does not add to it).
 * Today being empty never breaks a streak - the day isn't over yet.
 */
object StreakCalculator {
    fun compute(
        logged: Set<LocalDate>,
        today: LocalDate,
        grace: Boolean,
        moodByDay: Map<LocalDate, Float> = emptyMap(),
    ): StreakInfo {
        val loggedToday = today in logged
        var cur = 0
        var best = 0
        var lastGrace: LocalDate? = null
        val graceDays = mutableSetOf<LocalDate>()
        if (logged.isNotEmpty()) {
            val end = if (loggedToday) today else today.minusDays(1)
            var d = logged.min()
            while (!d.isAfter(end)) {
                if (d in logged) {
                    cur++; if (cur > best) best = cur
                } else if (grace && cur > 0 && (lastGrace == null || ChronoUnit.DAYS.between(lastGrace, d) >= 7)) {
                    lastGrace = d; graceDays += d
                } else cur = 0
                d = d.plusDays(1)
            }
        }
        val last10 = (9 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            val status = when {
                d in logged -> DayStatus.LOGGED
                d in graceDays -> DayStatus.GRACE
                d == today -> DayStatus.PENDING
                else -> DayStatus.MISSED
            }
            StreakDay(d, status, moodByDay[d])
        }
        val next = Milestones.all.firstOrNull { it > cur } ?: (cur + 100)
        return StreakInfo(cur, best, last10, next, loggedToday, logged.size)
    }
}
