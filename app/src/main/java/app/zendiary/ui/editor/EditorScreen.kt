package app.zendiary.ui.editor
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
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
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

val prompts = listOf(
    "What made you smile today?", "Three things I'm grateful for:", "What am I carrying that I can set down?",
    "What did I learn about myself today?", "Who made my day easier, and how?", "What would make tomorrow feel good?",
    "Describe a small moment of calm from today.", "What am I looking forward to?", "What drained my energy, and what restored it?",
    "One kind thing I can say to myself right now:",
)

class EditorViewModel(private val c: AppContainer, private val entryId: Long, date: LocalDate, preset: Int) : ViewModel() {
    data class UiState(val loaded: Boolean, val entry: DiaryEntry, val dirty: Boolean = false, val tags: List<String> = emptyList())

    private val blank = DiaryEntry(date = date, minutes = nowMinutes(), mood = preset)
    private val _state = MutableStateFlow(UiState(entryId <= 0, blank))
    val state = _state.asStateFlow()
    private val newImages = mutableSetOf<String>()
    private var saved = false

    init {
        viewModelScope.launch { c.diary.observeTags().collect { t -> _state.update { it.copy(tags = t.map { x -> x.name }) } } }
        if (entryId > 0) viewModelScope.launch { c.diary.get(entryId)?.let { e -> _state.update { it.copy(loaded = true, entry = e) } } }
    }

    fun edit(f: (DiaryEntry) -> DiaryEntry) = _state.update { it.copy(entry = f(it.entry), dirty = true) }

    fun addImages(uris: List<Uri>, resolver: android.content.ContentResolver) = viewModelScope.launch {
        uris.forEach { u -> c.diary.images.importUri(resolver, u)?.let { n -> newImages += n; edit { e -> e.copy(images = e.images + n) } } }
    }
    fun removeImage(n: String) {
        if (n in newImages) { c.diary.images.delete(n); newImages -= n }
        edit { it.copy(images = it.images - n) }
    }
    fun moveImageEarlier(n: String) = edit { e ->
        val i = e.images.indexOf(n); if (i <= 0) e else e.copy(images = e.images.toMutableList().apply { removeAt(i); add(i - 1, n) })
    }
    fun toggleTag(t: String) = edit { e -> e.copy(tags = if (t in e.tags) e.tags - t else e.tags + t) }
    fun addTag(t: String) = viewModelScope.launch { if (t.isNotBlank()) { c.diary.addTag(t); toggleTagOn(t.trim()) } }
    private fun toggleTagOn(t: String) = edit { e -> if (t in e.tags) e else e.copy(tags = e.tags + t) }

    fun save(onDone: (Long) -> Unit) = viewModelScope.launch { val id = c.diary.save(_state.value.entry); saved = true; onDone(id) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { if (entryId > 0) c.diary.delete(entryId); saved = true; onDone() }

    override fun onCleared() { if (!saved) newImages.forEach { c.diary.images.delete(it) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(entryId: Long, date: LocalDate, presetMood: Int, onClose: () -> Unit, onSaved: () -> Unit) {
    val vm = containerViewModel(key = "editor-$entryId-$date") { EditorViewModel(it, entryId, date, presetMood) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val e = ui.entry
    val ctx = LocalContext.current
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { vm.addImages(it, ctx.contentResolver) }
    val settings by containerSettings()
    val tryClose = { if (ui.dirty) confirmExit = true else onClose() }
    BackHandler(onBack = tryClose)

    ZenScaffold(topBar = {
        TopAppBar(
            title = { Text(if (entryId > 0) "Edit entry" else "New entry", style = MaterialTheme.typography.titleLarge) },
            colors = zenTopBarColors(),
            navigationIcon = { IconButton(tryClose) { Icon(Icons.Rounded.Close, "Close") } },
            actions = {
                IconButton({ vm.edit { it.copy(favorite = !it.favorite) } }) { Icon(if (e.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite") }
                if (entryId > 0) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                Button({ vm.save { onSaved() } }, enabled = e.mood in 1..5 && ui.loaded, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
            },
        )
    }) { pad ->
        if (!ui.loaded) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@ZenScaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ showDate = true }, { Text(e.date.pretty()) }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) })
                AssistChip({ showTime = true }, { Text(e.minutes.asClock()) }, leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)) })
            }
            SoftCard(Modifier.fillMaxWidth()) {
                Text("How was it?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Mood.entries.forEach { m ->
                        val sel = e.mood == m.level
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.edit { it.copy(mood = m.level) } }
                            .background(if (sel) m.color.copy(alpha = 0.28f) else Color.Transparent).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MoodFace(m.level, if (sel) 52.dp else 44.dp)
                            Text(m.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            OutlinedTextField(e.title, { vm.edit { s -> s.copy(title = it) } }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, shape = MaterialTheme.shapes.large)
            Column {
                OutlinedTextField(e.body, { vm.edit { s -> s.copy(body = it) } }, Modifier.fillMaxWidth(), label = { Text("What's on your mind?") }, minLines = 7, shape = MaterialTheme.shapes.large)
                TextButton({ vm.edit { it.copy(body = (if (it.body.isBlank()) "" else it.body + "\n\n") + prompts.random() + "\n") } }) {
                    Icon(Icons.Rounded.Lightbulb, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Need inspiration?")
                }
            }
            Column {
                SectionTitle("Activities")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.tags.forEach { t -> FilterChip(t in e.tags, { vm.toggleTag(t) }, { Text(t) }) }
                    AssistChip({ newTag = "" }, { Text("New") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                }
            }
            OutlinedTextField(e.event, { vm.edit { s -> s.copy(event = it) } }, Modifier.fillMaxWidth(), label = { Text("Mark an event (optional)") },
                leadingIcon = { Icon(Icons.Rounded.Event, null) }, singleLine = true, shape = MaterialTheme.shapes.large)
            Column {
                SectionTitle("Photos")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(e.images, key = { it }) { n ->
                        Box(Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).clickable { viewer = n }) {
                            DiaryImage(n, Modifier.fillMaxSize())
                            IconButton({ vm.removeImage(n) }, Modifier.align(Alignment.TopEnd).size(30.dp).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Rounded.Close, "Remove photo", Modifier.size(16.dp), tint = Color.White) }
                            if (e.images.first() != n) IconButton({ vm.moveImageEarlier(n) }, Modifier.align(Alignment.BottomStart).size(30.dp).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Rounded.ChevronLeft, "Move earlier", Modifier.size(16.dp), tint = Color.White) }
                        }
                    }
                    item {
                        Box(Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AddPhotoAlternate, "Add photos", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = e.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000L },
        )
        DatePickerDialog({ showDate = false }, confirmButton = {
            TextButton({ st.selectedDateMillis?.let { ms -> vm.edit { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }; showDate = false }) { Text("OK") }
        }, dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }) { DatePicker(st) }
    }
    if (showTime) {
        val st = rememberTimePickerState(e.minutes / 60, e.minutes % 60, true)
        AlertDialog({ showTime = false }, title = { Text("Time") }, text = { TimePicker(st) },
            confirmButton = { TextButton({ vm.edit { it.copy(minutes = st.hour * 60 + st.minute) }; showTime = false }) { Text("OK") } },
            dismissButton = { TextButton({ showTime = false }) { Text("Cancel") } })
    }
    if (confirmExit) AlertDialog({ confirmExit = false }, title = { Text("Discard changes?") }, text = { Text("Your edits haven't been saved.") },
        confirmButton = { TextButton({ onClose() }) { Text("Discard") } }, dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } })
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete this entry?") }, text = { Text("This can't be undone.") },
        confirmButton = { TextButton({ vm.delete { onClose() } }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    newTag?.let { t -> AlertDialog({ newTag = null }, title = { Text("New activity") },
        text = { OutlinedTextField(t, { newTag = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ vm.addTag(t); newTag = null }) { Text("Add") } }, dismissButton = { TextButton({ newTag = null }) { Text("Cancel") } }) }
    viewer?.let { n -> Dialog({ viewer = null }) { Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable { viewer = null }) { DiaryImage(n, Modifier.fillMaxWidth(), ContentScale.Fit) } } }
}

@Composable
private fun containerSettings(): State<app.zendiary.data.settings.AppSettings> =
    LocalContainer.current.settings.flow.collectAsState(app.zendiary.data.settings.AppSettings())

@Composable
fun BreathingScreen(onDone: () -> Unit) {
    var secs by remember { mutableIntStateOf(60) }
    LaunchedEffect(Unit) { while (secs > 0) { kotlinx.coroutines.delay(1000); secs-- }; onDone() }
    val t = rememberInfiniteTransition(label = "breath")
    val scale by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    val phase = remember(secs) { if ((60 - secs) / 4 % 2 == 0) "Breathe in…" else "Breathe out…" }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Entry saved", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().scale(scale).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
            Text(phase, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(48.dp))
        TextButton(onDone) { Text("Skip") }
    }
}
