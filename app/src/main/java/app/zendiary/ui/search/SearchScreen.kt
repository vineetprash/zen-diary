package app.zendiary.ui.search
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
import app.zendiary.ui.home.EntryCard

@OptIn(kotlinx.coroutines.FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results = query.debounce(250).map { c.diary.search(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun toggleFavorite(id: Long) = viewModelScope.launch { c.diary.toggleFavorite(id); query.value = query.value + "" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val vm = containerViewModel { SearchViewModel(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    ZenScaffold(topBar = {
        TopAppBar(colors = zenTopBarColors(), navigationIcon = { IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
            title = { TextField(q, { vm.query.value = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search titles, text, events, activities") }, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (q.isNotBlank() && results.isEmpty()) item { EmptyState(Icons.Rounded.Search, "Nothing found", "Try another word.") }
            items(results, key = { it.id }) { e -> EntryCard(e, { onOpen(e.id) }, { vm.toggleFavorite(e.id) }) }
        }
    }
}
