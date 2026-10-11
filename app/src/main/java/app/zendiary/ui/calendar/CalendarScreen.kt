package app.zendiary.ui.calendar
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
import app.zendiary.ui.home.MonthSummaryCard
import java.time.LocalDate
import java.time.YearMonth

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    val moodByDay = c.diary.observeMoodRows().map { rows ->
        rows.groupBy { it.date.toLocalDate() }.mapValues { (_, l) -> l.map { it.mood }.average().toFloat() }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val summary = month.flatMapLatest { m -> c.diary.observeMonth(m).map { MonthSummary.of(m, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    suspend fun entriesOn(d: LocalDate) = c.diary.forDate(d)
}

@Composable
fun CalendarScreen(onOpen: (Long) -> Unit, onNew: (LocalDate) -> Unit) {
    val vm = containerViewModel { CalendarViewModel(it) }
    val month by vm.month.collectAsStateWithLifecycle()
    val moods by vm.moodByDay.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<Pair<LocalDate, List<DiaryEntry>>?>(null) }

    ZenScaffold(hasBottomBar = true, topBar = { TopAppBar(title = { Text("Calendar", style = MaterialTheme.typography.headlineMedium) }, colors = zenTopBarColors(),
        actions = { TextButton({ vm.month.value = YearMonth.now() }) { Text("Today") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SoftCard(Modifier.fillMaxWidth()) {
                MonthHeader(month.label(), { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) }, nextEnabled = month.isBefore(YearMonth.now()))
                MonthGrid(month, onDayClick = { d ->
                    scope.launch {
                        val l = vm.entriesOn(d)
                        when (l.size) { 0 -> onNew(d); 1 -> onOpen(l[0].id); else -> pick = d to l }
                    }
                }) { d -> moods[d]?.let { MoodFace(Math.round(it), 30.dp) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Mood.entries.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) { MoodFace(m.level, 18.dp); Spacer(Modifier.width(4.dp)); Text(m.label, style = MaterialTheme.typography.labelSmall) } }
            }
            summary?.let { MonthSummaryCard(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
    pick?.let { (d, list) ->
        AlertDialog({ pick = null }, title = { Text(d.pretty()) }, text = {
            Column { list.forEach { e -> TextButton({ pick = null; onOpen(e.id) }) { MoodFace(e.mood, 24.dp); Spacer(Modifier.width(8.dp)); Text("${e.minutes.asClock()}  ${e.title.ifBlank { "Untitled" }}") } } }
        }, confirmButton = { TextButton({ pick = null; onNew(d) }) { Text("Add another") } }, dismissButton = { TextButton({ pick = null }) { Text("Close") } })
    }
}
