package app.zendiary.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

fun LocalDate.pretty(): String = format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
fun LocalDate.short(): String = format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
fun YearMonth.label(): String = format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
fun YearMonth.shortLabel(): String = format(DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()))
fun Int.asClock(): String = "%02d:%02d".format(this / 60, this % 60)
fun Long.toLocalDate(): LocalDate = LocalDate.ofEpochDay(this)
fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
