package app.zendiary.ui.home
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.zendiary.AppContainer
import app.zendiary.core.*
import app.zendiary.domain.*
import app.zendiary.ui.components.*
import app.zendiary.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    val favoritesOnly = MutableStateFlow(false)

    val entries = combine(month, favoritesOnly) { m, f -> m to f }
        .flatMapLatest { (m, f) -> c.diary.observeMonth(m).map { l -> if (f) l.filter { it.favorite } else l } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val months = c.diary.observeFirstMonth().map { first ->
        val now = YearMonth.now()
        val start = minOf(first ?: now, now.minusMonths(2))
        generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(now) }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(YearMonth.now()))

    val recent = c.diary.observeDays(LocalDate.now().minusDays(1), LocalDate.now())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streak = c.streaks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo.EMPTY)

    /** Shown during the first week of a month: recap of the month that just ended. */
    val lastMonthSummary = combine(c.diary.observeMonth(YearMonth.now().minusMonths(1)), c.settings.flow) { l, s ->
        val prev = YearMonth.now().minusMonths(1)
        if (LocalDate.now().dayOfMonth <= 7 && s.summaryDismissed != prev.toString()) MonthSummary.of(prev, l) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun dismissSummary(m: YearMonth) = viewModelScope.launch { c.settings.set(app.zendiary.data.settings.Keys.SUMMARY_DISMISSED, m.toString()) }
    fun toggleFavorite(id: Long) = viewModelScope.launch { c.diary.toggleFavorite(id) }
}

@Composable
fun HomeScreen(onOpen: (Long) -> Unit, onNew: (LocalDate) -> Unit, onSearch: () -> Unit, onStreaks: () -> Unit) {
    val vm = containerViewModel { HomeViewModel(it) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val favOnly by vm.favoritesOnly.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val summary by vm.lastMonthSummary.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    ZenScaffold(
        hasBottomBar = true,
        topBar = {
            TopAppBar(
                title = { Text("Zen Diary", style = MaterialTheme.typography.headlineMedium) },
                colors = zenTopBarColors(),
                actions = {
                    AssistChip(onClick = onStreaks, label = { Text("${streak.current}") },
                        leadingIcon = { Icon(Icons.Rounded.LocalFireDepartment, "Streak", Modifier.size(18.dp)) })
                    IconButton({ vm.favoritesOnly.value = !favOnly }) {
                        Icon(if (favOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorites only")
                    }
                    IconButton(onSearch) { Icon(Icons.Rounded.Search, "Search") }
                },
            )
        },
        fab = { ExtendedFloatingActionButton(onClick = { onNew(today) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New entry") }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MonthChips(months, month) { vm.month.value = it }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(today to "today", today.minusDays(1) to "yesterday").forEach { (d, label) ->
                            val e = recent.filter { it.date == d }.maxByOrNull { it.minutes }
                            SoftCard(Modifier.weight(1f), onClick = { if (e != null) onOpen(e.id) else onNew(d) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (e != null) MoodFace(e.mood, 36.dp) else Icon(Icons.Rounded.Add, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(if (e != null) "Update $label" else "Write $label", style = MaterialTheme.typography.titleSmall)
                                        Text(d.short(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                summary?.let { s -> item { MonthSummaryCard(s, onDismiss = { vm.dismissSummary(s.month) }) } }
                if (entries.isEmpty()) item {
                    EmptyState(Icons.Rounded.AutoStories, if (favOnly) "No favorites this month" else "A blank page",
                        "Nothing written in ${month.label()} yet.\nTap “New entry” to begin.")
                }
                items(entries, key = { it.id }) { e -> EntryCard(e, { onOpen(e.id) }, { vm.toggleFavorite(e.id) }) }
            }
        }
    }
}

@Composable
private fun MonthChips(months: List<YearMonth>, selected: YearMonth, onSelect: (YearMonth) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(months.size, selected) { months.indexOf(selected).takeIf { it >= 0 }?.let { state.animateScrollToItem(it) } }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(months, key = { it.toString() }) { m ->
            FilterChip(selected = m == selected, onClick = { onSelect(m) }, label = { Text(if (m.year == YearMonth.now().year) m.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) else m.shortLabel()) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryCard(e: DiaryEntry, onClick: () -> Unit, onFavorite: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(e.date.pretty() + "  ·  " + e.minutes.asClock(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (e.title.isNotBlank()) Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(onFavorite, Modifier.size(32.dp)) { Icon(if (e.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite", Modifier.size(20.dp), tint = if (e.favorite) Mood.RAD.color else MaterialTheme.colorScheme.outline) }
            MoodFace(e.mood, 38.dp)
        }
        if (e.event.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Event, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(e.event, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (e.body.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(e.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
        if (e.images.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                e.images.take(3).forEachIndexed { i, n ->
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp))) {
                        DiaryImage(n, Modifier.fillMaxSize())
                        if (i == 2 && e.images.size > 3) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { Text("+${e.images.size - 3}", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        if (e.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                e.tags.take(5).forEach { Text("#$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun MonthSummaryCard(s: MonthSummary, onDismiss: (() -> Unit)? = null) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.month.label()} in review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            onDismiss?.let { IconButton(it, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp)) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MoodFace(Math.round(s.avg), 44.dp)
            Column {
                Text("${s.count} entries · average %.1f / 5".format(s.avg), style = MaterialTheme.typography.bodyMedium)
                s.bestDay?.let { Text("Brightest day: ${it.short()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s.topTag?.let { Text("Most common activity: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
