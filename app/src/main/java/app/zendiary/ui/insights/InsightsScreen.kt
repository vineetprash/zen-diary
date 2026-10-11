package app.zendiary.ui.insights
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.zendiary.AppContainer
import app.zendiary.core.*
import app.zendiary.domain.*
import app.zendiary.ui.components.*
import app.zendiary.ui.theme.*
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

class InsightsViewModel(private val c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(StatsRange.D30)
    val stats = combine(c.diary.observeMoodRows(), c.diary.observeTagMoodRows(), range) { rows, tags, r ->
        StatsCalculator.build(rows, tags, r, LocalDate.now(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val streak = c.streaks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo.EMPTY)
}

@Composable
fun InsightsScreen(openStreaksFirst: Boolean = false) {
    val vm = containerViewModel { InsightsViewModel(it) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(if (openStreaksFirst) 1 else 0) }

    ZenScaffold(hasBottomBar = true, topBar = { TopAppBar(title = { Text("Insights", style = MaterialTheme.typography.headlineMedium) }, colors = zenTopBarColors()) }) { pad ->
        Column(Modifier.padding(pad)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("Mood", "Streaks").forEachIndexed { i, t -> SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) } }
            }
            if (tab == 0) {
                val s = stats
                LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatsRange.entries.forEach { r -> FilterChip(range == r, { vm.range.value = r }, { Text(r.label) }) }
                        }
                    }
                    if (s == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    else if (s.byDay.isEmpty()) item { EmptyState(Icons.Rounded.Insights, "No data yet", "Log a few entries and your patterns will appear here.") }
                    else items(StatsSections.all, key = { it.key }) { sec ->
                        SoftCard(Modifier.fillMaxWidth()) { SectionTitle(sec.title); sec.Content(s) }
                    }
                }
            } else StreakContent(streak)
        }
    }
}

// ---------- Stats sections (open/closed: add a section = add an object + list it) ----------
interface StatsSection { val key: String; val title: String; @Composable fun Content(d: StatsData) }

object StatsSections {
    val all: List<StatsSection> = listOf(DistributionSection, TrendSection, WeekCompareSection, WeekdaySection, MonthlySection, ActivitySection, PixelsSection)
}

private object DistributionSection : StatsSection {
    override val key = "dist"; override val title = "Mood distribution"
    @Composable override fun Content(d: StatsData) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DonutChart(d.counts) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.avg?.let { "%.1f".format(it) } ?: "–", style = MaterialTheme.typography.headlineMedium)
                    Text("avg", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mood.entries.reversed().forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoodFace(m.level, 22.dp); Spacer(Modifier.width(8.dp))
                        Text("${m.label}  ${d.counts[m.level - 1]}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private object TrendSection : StatsSection {
    override val key = "trend"; override val title = "Mood trend"
    @Composable override fun Content(d: StatsData) =
        LineChart(listOf(ChartSeries(d.trend, MaterialTheme.colorScheme.primary)), d.trendLabels, colorDots = d.trend.size <= 45)
}

private object WeekCompareSection : StatsSection {
    override val key = "week"; override val title = "This week vs. before"
    @Composable override fun Content(d: StatsData) {
        val tw = d.thisWeek.filterNotNull(); val lw = d.lastWeek.filterNotNull()
        val diff = if (tw.isNotEmpty() && lw.isNotEmpty()) tw.average() - lw.average() else null
        Text(
            when { diff == null -> "Log a few more days to compare weeks."; diff > 0.15 -> "This week is brighter than last week (+%.1f)".format(diff)
                diff < -0.15 -> "This week is a bit heavier than last week (%.1f)".format(diff); else -> "This week feels much like last week." },
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LineChart(listOf(ChartSeries(d.lastWeek, MaterialTheme.colorScheme.outline, "Last week"), ChartSeries(d.thisWeek, MaterialTheme.colorScheme.primary, "This week")), d.weekDayLabels)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Dot(MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)); Text("This week", style = MaterialTheme.typography.labelSmall) }
            Row(verticalAlignment = Alignment.CenterVertically) { Dot(MaterialTheme.colorScheme.outline); Spacer(Modifier.width(4.dp)); Text("Last week", style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(14.dp))
        Text("Weekly averages (last 8 weeks)", style = MaterialTheme.typography.labelLarge)
        BarChart(d.weekly.map { it.second }, d.weekly.map { it.first.short() }, highlight = d.weekly.lastIndex)
    }
}

private object WeekdaySection : StatsSection {
    override val key = "weekday"; override val title = "Mood by weekday"
    @Composable override fun Content(d: StatsData) =
        BarChart(d.weekdayAvg, java.time.DayOfWeek.entries.map { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) })
}

private object MonthlySection : StatsSection {
    override val key = "monthly"; override val title = "Monthly averages"
    @Composable override fun Content(d: StatsData) =
        BarChart(d.monthly.map { it.second }, d.monthly.map { it.first.month.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }, highlight = d.monthly.lastIndex)
}

private object ActivitySection : StatsSection {
    override val key = "activity"; override val title = "Activities & mood"
    @Composable override fun Content(d: StatsData) {
        if (d.tagImpact.isEmpty()) { Text("Tag activities on at least 3 days to see which ones lift or lower your mood.", style = MaterialTheme.typography.bodyMedium); return }
        val best = d.tagImpact.first()
        if (best.delta > 0.1) Text("You tend to feel best on days with “${best.tag}” (+%.1f).".format(best.delta), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val shown = (d.tagImpact.take(4) + d.tagImpact.takeLast(3)).distinctBy { it.tag }
        val maxAbs = shown.maxOf { kotlin.math.abs(it.delta) }.coerceAtLeast(0.3f)
        shown.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(t.tag, Modifier.width(92.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(14.dp)) {
                    val frac = kotlin.math.abs(t.delta) / maxAbs * 0.5f
                    Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    Box(Modifier.align(if (t.delta >= 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight().padding(0.dp)) {
                        Box(Modifier.align(if (t.delta >= 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxWidth(frac * 2).fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp)).background(if (t.delta >= 0) Mood.GOOD.color else Mood.AWFUL.color))
                    }
                }
                Text("%+.1f".format(t.delta), Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private object PixelsSection : StatsSection {
    override val key = "pixels"; override val title = "Year in pixels"
    @Composable override fun Content(d: StatsData) {
        var year by remember { mutableIntStateOf(d.today.year) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ year-- }) { Icon(Icons.Rounded.ChevronLeft, "Previous year") }
            Text("$year", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton({ year++ }, enabled = year < d.today.year) { Icon(Icons.Rounded.ChevronRight, "Next year") }
        }
        PixelGrid(year, d.byDay)
    }
}

// ---------- Streaks ----------
@Composable
fun StreakContent(s: StreakInfo) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            val progress by animateFloatAsState((s.current.toFloat() / s.nextMilestone).coerceIn(0f, 1f), label = "ring")
            Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                val track = cs.surfaceVariant; val arc = cs.primary
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 16.dp.toPx(); val dia = size.minDimension - sw
                    val tl = androidx.compose.ui.geometry.Offset((size.width - dia) / 2, (size.height - dia) / 2)
                    drawArc(track, 0f, 360f, false, tl, androidx.compose.ui.geometry.Size(dia, dia), style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(arc, -90f, 360f * progress, false, tl, androidx.compose.ui.geometry.Size(dia, dia), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = Mood.RAD.color, modifier = Modifier.size(30.dp))
                    Text("${s.current}", style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Serif)
                    Text(if (s.current == 1) "day streak" else "day streak", style = MaterialTheme.typography.labelMedium)
                    Text("next: ${s.nextMilestone}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftCard(Modifier.weight(1f)) { Text("Best streak", style = MaterialTheme.typography.labelMedium); Text("${s.best} days", style = MaterialTheme.typography.titleLarge) }
                SoftCard(Modifier.weight(1f)) { Text("Days journaled", style = MaterialTheme.typography.labelMedium); Text("${s.totalDays}", style = MaterialTheme.typography.titleLarge) }
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SectionTitle("Last 10 days")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    s.last10.forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                when (d.status) {
                                    DayStatus.LOGGED -> if (d.mood != null) MoodFace(Math.round(d.mood), 28.dp) else Dot(cs.primary, 24.dp)
                                    DayStatus.GRACE -> Box(Modifier.size(24.dp).border(2.dp, cs.tertiary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Spa, "Grace day", Modifier.size(14.dp), tint = cs.tertiary) }
                                    DayStatus.PENDING -> Box(Modifier.size(24.dp).border(2.dp, cs.primary, CircleShape))
                                    DayStatus.MISSED -> Box(Modifier.size(24.dp).background(cs.surfaceVariant, CircleShape))
                                }
                            }
                            Text(d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (s.loggedToday) "Today is done. Rest easy." else "Today is still open - one line is enough.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SectionTitle("Milestones")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(7, 30, 100, 365).forEach { m ->
                        val earned = s.best >= m
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (earned) 1f else 0.4f)) {
                            Box(Modifier.size(54.dp).background(if (earned) Mood.RAD.color.copy(alpha = 0.35f) else cs.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (earned) Icons.Rounded.Spa else Icons.Rounded.Lock, null, Modifier.size(24.dp))
                            }
                            Text("$m", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Grace days: one missed day per week won't break your streak (change in Settings).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
