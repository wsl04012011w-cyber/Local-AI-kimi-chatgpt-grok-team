package dev.localai.chat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localai.chat.R
import dev.localai.chat.llm.ImportState
import dev.localai.chat.llm.ModelState
import dev.localai.chat.ui.chat.components.ChatBubble
import dev.localai.chat.ui.chat.components.ChatInputBar
import dev.localai.chat.ui.chat.components.EmptyState
import dev.localai.chat.ui.chat.components.ImportingDialog
import dev.localai.chat.ui.chat.components.ModelStatusChip
import dev.localai.chat.ui.chat.components.SettingsSheet
import kotlinx.coroutines.launch

private const val BOTTOM_ANCHOR_KEY = "bottom_anchor"

/**
 * Rola até o FIM real da lista. O item-âncora é sempre o último: como a lista não pode rolar além
 * do final, o scroll para na base da última bolha — mesmo que ela seja muito mais alta que a
 * tela. (animateScrollToItem(lastIndex) alinharia o TOPO da última bolha ao topo da tela, o que
 * fazia o chat "subir" durante respostas longas.)
 */
private suspend fun LazyListState.scrollToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importModel) }

    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // "Seguir a resposta": ligado por padrão; desliga quando o USUÁRIO rola para cima e volta a
    // ligar quando ele chega ao final (ou envia uma mensagem). Rolagens programáticas não contam.
    var autoFollow by remember { mutableStateOf(true) }
    val followConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) autoFollow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll ->
            if (!canScroll) autoFollow = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // Nova mensagem (a do usuário ou o início da resposta): volta a seguir.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            autoFollow = true
            listState.scrollToBottom()
        }
    }

    // Durante o streaming (resposta ou raciocínio crescendo), acompanha o final.
    val last = messages.lastOrNull()
    val lastContentLen = last?.content?.length ?: 0
    val lastThinkingLen = last?.thinkingContent?.length ?: 0
    val lastExpanded = last?.let { it.isThinking || it.showThinking } ?: false
    LaunchedEffect(lastContentLen, lastThinkingLen, lastExpanded, isGenerating) {
        if (autoFollow && messages.isNotEmpty()) listState.scrollToBottom()
    }

    val showScrollToBottom by remember {
        derivedStateOf { !autoFollow && listState.canScrollForward }
    }

    val statusLine = remember(settings, stats, modelState) {
        if (modelState !is ModelState.Loaded) {
            ""
        } else {
            buildList {
                add("ctx ${settings.nCtx}")
                add("FA ${if (settings.effectiveFlashAttn) "ON" else "OFF"}")
                add("KV ${settings.kvTypeK.label}/${settings.kvTypeV.label}")
                add("geração ${settings.nThreads} / prefill ${settings.nThreadsBatch} threads")
                stats?.let {
                    if (it.reusedTokens > 0) add("↻ ${it.reusedTokens} tok em cache")
                    if (it.genTokensPerSecond > 0f) add("%.1f tok/s".format(it.genTokensPerSecond))
                }
            }.joinToString(" · ")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    actions = {
                        ModelStatusChip(
                            state = modelState,
                            onClick = { modelPicker.launch(arrayOf("*/*")) },
                        )
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.settings))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Opções")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.load_model)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    modelPicker.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.unload_model)) },
                                leadingIcon = { Icon(Icons.Filled.Eject, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.unloadModel()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_chat)) },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showClearConfirm = true
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (statusLine.isNotEmpty()) {
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime),
                ),
            ) {
                ChatInputBar(
                    enabled = modelState is ModelState.Loaded,
                    isGenerating = isGenerating,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(followConnection),
        ) {
            if (messages.isEmpty() && modelState !is ModelState.Loading) {
                EmptyState(
                    modelLoaded = modelState is ModelState.Loaded,
                    onLoadModel = { modelPicker.launch(arrayOf("*/*")) },
                    onSuggestion = viewModel::send,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            onToggleThinking = if (message.thinkingContent.isNotEmpty()) {
                                { viewModel.toggleThinkingVisibility(message.id) }
                            } else {
                                null
                            },
                        )
                    }
                    // Âncora de fim de lista (ver scrollToBottom)
                    item(key = BOTTOM_ANCHOR_KEY) { Spacer(Modifier.height(1.dp)) }
                }
            }

            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        autoFollow = true
                        scope.launch { listState.scrollToBottom() }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ir para o final")
                }
            }

            if (importState is ImportState.Importing) {
                ImportingDialog(fileName = (importState as ImportState.Importing).fileName)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onDismiss = { showSettings = false },
            onApply = viewModel::applySettings,
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.clear_chat)) },
            text = { Text("Apagar todo o histórico da conversa?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearChat()
                    },
                ) { Text("Apagar") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}
