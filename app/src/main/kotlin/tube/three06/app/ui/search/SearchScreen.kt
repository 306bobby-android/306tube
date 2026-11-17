package tube.three06.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.hidingShorts
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.rememberItemClick
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.SearchFilter
import tube.three06.recommend.WatchEvent

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val graph: AppGraph, initialQuery: String?) : ViewModel() {
    val query = MutableStateFlow(initialQuery ?: "")
    val submitted = MutableStateFlow(initialQuery?.takeIf { it.isNotBlank() })
    val filter = MutableStateFlow(SearchFilter())

    val recent: StateFlow<List<String>> = graph.library.recentSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suggestions: StateFlow<List<String>> = query
        .debounce(250)
        .distinctUntilChanged()
        .mapLatest { q ->
            if (q.length < 2) emptyList()
            else runCatching { graph.youTube.searchSuggestions(q) }.getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val results = Paged<Item>(viewModelScope, ::itemKey) { continuation ->
        val q = submitted.value ?: return@Paged Page(emptyList())
        graph.youTube.search(q, filter.value, continuation)
    }

    init {
        if (submitted.value != null) results.load()
    }

    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query.value = q
        submitted.value = q
        viewModelScope.launch { graph.library.recordSearch(q) }
        results.load()
    }

    fun clearSubmitted() {
        submitted.value = null
        results.clear()
    }

    fun setFilter(f: SearchFilter) {
        filter.value = f
        if (submitted.value != null) results.load()
    }

    fun removeRecent(q: String) = viewModelScope.launch { graph.library.removeSearch(q) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initialQuery: String?) {
    val vm = graphViewModel(key = "search:${initialQuery ?: ""}") { SearchViewModel(it, initialQuery) }
    val query by vm.query.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.state.collectAsStateWithLifecycle()
    val resultItems = results.items.hidingShorts()
    val nav = LocalNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(submitted == null) }
    val onItemClick = rememberItemClick(WatchEvent.Source.SEARCH)

    fun go(text: String) {
        vm.submit(text)
        editing = false
        keyboard?.hide()
    }

    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (editing && submitted != null) editing = false else nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = {
                            vm.query.value = it
                            editing = true
                        },
                        placeholder = { Text("Search YouTube") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .clickable { editing = true },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go(query) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    vm.query.value = ""
                                    vm.clearSubmitted()
                                    editing = true
                                }) { Icon(Icons.Default.Clear, "Clear") }
                            }
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (editing || submitted == null) {
                val shown = if (query.isBlank()) recent else suggestions
                LazyColumn {
                    items(shown, key = { it }) { s ->
                        ListItem(
                            headlineContent = { Text(s) },
                            leadingContent = { Icon(if (query.isBlank()) Icons.Default.History else Icons.Default.Search, null) },
                            trailingContent = if (query.isBlank()) {
                                { IconButton(onClick = { vm.removeRecent(s) }) { Icon(Icons.Default.Clear, "Remove") } }
                            } else null,
                            modifier = Modifier.clickable { go(s) },
                        )
                    }
                }
            } else {
                FilterChips(filter, vm::setFilter)
                PagedColumn(
                    state = results.copy(items = resultItems),
                    onRefresh = vm.results::load,
                    onLoadMore = vm.results::loadMore,
                    onRetry = vm.results::load,
                    emptyTitle = "No results",
                    emptyMessage = "Try different words or filters.",
                ) {
                    items(resultItems, key = { itemKey(it) }) { item ->
                        ItemView(item, onItemClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(filter: SearchFilter, onChange: (SearchFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            EnumChip("Type", filter.type, SearchFilter.Type.entries, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) {
                onChange(filter.copy(type = it))
            }
        }
        item {
            EnumChip("Upload date", filter.uploadDate, SearchFilter.UploadDate.entries, {
                when (it) {
                    SearchFilter.UploadDate.HOUR -> "Last hour"
                    SearchFilter.UploadDate.TODAY -> "Today"
                    SearchFilter.UploadDate.WEEK -> "This week"
                    SearchFilter.UploadDate.MONTH -> "This month"
                    SearchFilter.UploadDate.YEAR -> "This year"
                }
            }) { onChange(filter.copy(uploadDate = it)) }
        }
        item {
            EnumChip("Duration", filter.duration, SearchFilter.Duration.entries, {
                when (it) {
                    SearchFilter.Duration.SHORT -> "Under 4 min"
                    SearchFilter.Duration.MEDIUM -> "4–20 min"
                    SearchFilter.Duration.LONG -> "Over 20 min"
                }
            }) { onChange(filter.copy(duration = it)) }
        }
        item {
            EnumChip("Sort", filter.sort, SearchFilter.Sort.entries, {
                when (it) {
                    SearchFilter.Sort.RELEVANCE -> "Relevance"
                    SearchFilter.Sort.UPLOAD_DATE -> "Upload date"
                    SearchFilter.Sort.VIEW_COUNT -> "View count"
                    SearchFilter.Sort.RATING -> "Rating"
                }
            }) { onChange(filter.copy(sort = it)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <E> EnumChip(label: String, selected: E?, options: List<E>, name: (E) -> String, onSelect: (E?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row {
        FilterChip(selected = selected != null, onClick = { open = true }, label = { Text(selected?.let(name) ?: label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any", style = MaterialTheme.typography.bodyMedium) }, onClick = { onSelect(null); open = false })
            options.forEach { o ->
                DropdownMenuItem(text = { Text(name(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}
