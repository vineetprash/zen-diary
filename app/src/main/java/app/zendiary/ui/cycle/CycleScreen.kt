package app.zendiary.ui.cycle
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
import java.time.LocalDate
import java.time.YearMonth

class CycleViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    val days = c.cycle.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun set(d: LocalDate, flow: Int, symptoms: List<String>) = viewModelScope.launch { c.cycle.set(d, flow, symptoms) }
}

private val symptomList = listOf("Cramps", "Headache", "Bloating", "Fatigue", "Mood swings", "Cravings", "Back pain", "Acne", "Nausea", "Tender")
private val flowLabels = listOf("None", "Light", "Medium", "Heavy")
private val rose = Color(0xFFD98C9B)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleScreen() {
    val vm = containerViewModel { CycleViewModel(it) }
    val month by vm.month.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val byDate = remember(days) { days.associateBy { it.date } }
    val pred = remember(days) { CyclePredictor.predict(days) }
    var editing by remember { mutableStateOf<LocalDate?>(null) }

    ZenScaffold(hasBottomBar = true, topBar = { TopAppBar(title = { Text("Cycle", style = MaterialTheme.typography.headlineMedium) }, colors = zenTopBarColors()) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SoftCard(Modifier.fillMaxWidth()) {
                Text(pred.nextStart?.let { "Next period ≈ ${it.pretty()}" } ?: "Log your period to see predictions", style = MaterialTheme.typography.titleMedium)
                if (pred.lastStart != null) Text("Average cycle ${pred.avgCycleDays} days · period ${pred.avgPeriodDays} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SoftCard(Modifier.fillMaxWidth()) {
                MonthHeader(month.label(), { vm.month.value = month.minusMonths(1) }, { vm.month.value = month.plusMonths(1) })
                MonthGrid(month, onDayClick = { editing = it }) { d ->
                    val log = byDate[d]
                    when {
                        log != null && log.flow > 0 -> Box(Modifier.size(26.dp).background(rose.copy(alpha = 0.35f + 0.2f * log.flow), CircleShape))
                        d in pred.predictedDays -> Box(Modifier.size(26.dp).border(2.dp, rose, CircleShape))
                        log != null && log.symptoms.isNotEmpty() -> Dot(MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Dot(rose, 12.dp); Spacer(Modifier.width(4.dp)); Text("Period", style = MaterialTheme.typography.labelSmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).border(2.dp, rose, CircleShape)); Spacer(Modifier.width(4.dp)); Text("Predicted", style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text("Estimates only - not medical advice or contraception. All cycle data stays on your device and is excluded from the diary feed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editing?.let { d ->
        val cur = byDate[d]
        var flow by remember(d) { mutableIntStateOf(cur?.flow ?: 0) }
        var sym by remember(d) { mutableStateOf(cur?.symptoms ?: emptyList()) }
        AlertDialog({ editing = null }, title = { Text(d.pretty()) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Flow", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { flowLabels.forEachIndexed { i, l -> FilterChip(flow == i, { flow = i }, { Text(l) }) } }
                Spacer(Modifier.height(12.dp)); Text("Symptoms", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { symptomList.forEach { s -> FilterChip(s in sym, { sym = if (s in sym) sym - s else sym + s }, { Text(s) }) } }
            }
        }, confirmButton = { TextButton({ vm.set(d, flow, sym); editing = null }) { Text("Save") } }, dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}
