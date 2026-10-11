package app.zendiary.domain

import app.zendiary.core.toLocalDate
import app.zendiary.data.db.MoodRow
import app.zendiary.data.db.TagMoodRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class StatsRange(val days: Int?, val label: String) {
    D30(30, "30 days"), D90(90, "90 days"), Y1(365, "1 year"), ALL(null, "All time")
}

data class TagImpact(val tag: String, val days: Int, val delta: Float)

class StatsData(
    val range: StatsRange,
    val today: LocalDate,
    val total: Int,
    val avg: Float?,
    val counts: IntArray,
    val trend: List<Float?>,
    val trendLabels: List<String>,
    val weekdayAvg: List<Float?>,       // Mon..Sun
    val thisWeek: List<Float?>,
    val lastWeek: List<Float?>,
    val weekDayLabels: List<String>,
    val weekly: List<Pair<LocalDate, Float?>>,
    val monthly: List<Pair<YearMonth, Float?>>,
    val tagImpact: List<TagImpact>,
    val byDay: Map<LocalDate, Float>,
)

object StatsCalculator {
    fun build(
        rows: List<MoodRow>,
        tagRows: List<TagMoodRow>,
        range: StatsRange,
        today: LocalDate,
        weekStart: DayOfWeek,
    ): StatsData {
        val byDay: Map<LocalDate, Float> = rows.groupBy { it.date.toLocalDate() }
            .mapValues { (_, l) -> l.map { it.mood }.average().toFloat() }
        val from = range.days?.let { today.minusDays(it - 1L) }
        val inRange = rows.filter { from == null || !it.date.toLocalDate().isBefore(from) }
        val counts = IntArray(5)
        inRange.forEach { counts[(it.mood - 1).coerceIn(0, 4)]++ }
        val avg = if (inRange.isEmpty()) null else inRange.map { it.mood }.average().toFloat()

        // Trend: daily up to 90 points, otherwise weekly buckets
        val startDay = from ?: (byDay.keys.minOrNull() ?: today)
        val n = java.time.temporal.ChronoUnit.DAYS.between(startDay, today).toInt() + 1
        val bucket = if (n > 90) 7 else 1
        val trend = ArrayList<Float?>(); val labels = ArrayList<String>()
        var s = startDay
        while (!s.isAfter(today)) {
            val vals = (0 until bucket).mapNotNull { byDay[s.plusDays(it.toLong())] }
            trend += if (vals.isEmpty()) null else vals.average().toFloat()
            labels += "${s.dayOfMonth}/${s.monthValue}"
            s = s.plusDays(bucket.toLong())
        }

        val weekday = (1..7).map { dow ->
            val v = byDay.filterKeys { it.dayOfWeek.value == dow && (from == null || !it.isBefore(from)) }.values
            if (v.isEmpty()) null else v.average().toFloat()
        }

        val thisStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
        val thisWeek = (0..6).map { byDay[thisStart.plusDays(it.toLong())] }
        val lastWeek = (0..6).map { byDay[thisStart.minusWeeks(1).plusDays(it.toLong())] }
        val dayLabels = (0..6).map { thisStart.plusDays(it.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
        val weekly = (7 downTo 0).map { i ->
            val ws = thisStart.minusWeeks(i.toLong())
            val v = (0..6).mapNotNull { byDay[ws.plusDays(it.toLong())] }
            ws to (if (v.isEmpty()) null else v.average().toFloat())
        }
        val thisMonth = YearMonth.from(today)
        val monthly = (11 downTo 0).map { i ->
            val m = thisMonth.minusMonths(i.toLong())
            val v = byDay.filterKeys { YearMonth.from(it) == m }.values
            m to (if (v.isEmpty()) null else v.average().toFloat())
        }

        val overall = if (byDay.isEmpty()) 0f else byDay.values.average().toFloat()
        val impact = tagRows.groupBy { it.tag }.mapNotNull { (tag, l) ->
            val perDay = l.groupBy { it.date }.mapValues { e -> e.value.map { it.mood }.average() }
            if (perDay.size < 3) null else TagImpact(tag, perDay.size, perDay.values.average().toFloat() - overall)
        }.sortedByDescending { it.delta }

        return StatsData(range, today, inRange.size, avg, counts, trend, labels, weekday, thisWeek, lastWeek, dayLabels, weekly, monthly, impact, byDay)
    }
}

data class MonthSummary(val month: YearMonth, val count: Int, val avg: Float, val bestDay: LocalDate?, val bestMood: Int, val topTag: String?) {
    companion object {
        fun of(month: YearMonth, entries: List<DiaryEntry>): MonthSummary? {
            if (entries.isEmpty()) return null
            val best = entries.maxByOrNull { it.mood }
            val top = entries.flatMap { it.tags }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return MonthSummary(month, entries.size, entries.map { it.mood }.average().toFloat(), best?.date, best?.mood ?: 0, top)
        }
    }
}
