package app.zendiary.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class CyclePrediction(
    val lastStart: LocalDate?,
    val avgCycleDays: Int,
    val avgPeriodDays: Int,
    val nextStart: LocalDate?,
    val predictedDays: Set<LocalDate>,
)

/** Simple averaging of past cycles. Estimates only - not medical advice or contraception. */
object CyclePredictor {
    fun predict(days: List<CycleDay>, today: LocalDate = LocalDate.now()): CyclePrediction {
        val flowDays = days.filter { it.flow > 0 }.map { it.date }.toSortedSet()
        val starts = flowDays.filter { !flowDays.contains(it.minusDays(1)) }
        if (starts.isEmpty()) return CyclePrediction(null, 28, 5, null, emptySet())
        val gaps = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it in 15..60 }
        val avgCycle = if (gaps.isEmpty()) 28 else gaps.takeLast(6).average().toInt()
        val lengths = starts.map { s -> generateSequence(s) { it.plusDays(1) }.takeWhile { it in flowDays }.count() }
        val avgPeriod = lengths.takeLast(6).average().toInt().coerceIn(2, 10)
        val last = starts.last()
        var next = last.plusDays(avgCycle.toLong())
        while (next.plusDays(avgPeriod.toLong()).isBefore(today)) next = next.plusDays(avgCycle.toLong())
        val predicted = buildSet {
            for (c in 0..2) {
                val s = next.plusDays((c * avgCycle).toLong())
                for (i in 0 until avgPeriod) add(s.plusDays(i.toLong()))
            }
        }
        return CyclePrediction(last, avgCycle, avgPeriod, next, predicted)
    }
}
