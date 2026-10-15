package dev.localai.chat.ui.chat

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.localai.chat.data.ChatRepository
import dev.localai.chat.data.SettingsRepository
import dev.localai.chat.data.db.MessageEntity
import dev.localai.chat.llm.GenerationStats
import dev.localai.chat.llm.ImportState
import dev.localai.chat.llm.InferenceSettings
import dev.localai.chat.llm.ChatTurn
import dev.localai.chat.llm.InferenceEngine
import dev.localai.chat.llm.ModelState
import dev.localai.chat.llm.PromptHistory
import dev.localai.chat.llm.TokenStreamProcessor
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Role { USER, ASSISTANT }

enum class GenerationState {
    Idle,
    Generating,
    Thinking,
    Completed,
    Cancelled,
    Error,
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val thinkingContent: String = "",
    val isStreaming: Boolean = false,
    val isThinking: Boolean = false,
    /** Caixa de raciocínio expandida pelo usuário. Padrão: minimizada. */
    val showThinking: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: InferenceEngine,
    private val repository: ChatRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val modelState: StateFlow<ModelState> = engine.modelState

    val settings: StateFlow<InferenceSettings> = settingsRepository.settings

    private val _stats = MutableStateFlow<GenerationStats?>(null)
    val stats: StateFlow<GenerationStats?> = _stats.asStateFlow()

    // Mensagens cujo raciocínio o usuário expandiu. Fica aqui (não na entidade) para sobreviver
    // às reemissões do Room.
    private val expandedThinking = mutableSetOf<Long>()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generationState = MutableStateFlow(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var sendJob: Job? = null          // fase de preparo (inserts + prompt)
    private var generationJob: Job? = null    // fase de geracao nativa
    private var currentAssistantId: Long = -1L
    private val processor = TokenStreamProcessor()

    init {
        viewModelScope.launch {
            repository.observeMessages().collect { entities ->
                val streaming = _messages.value.lastOrNull()?.takeIf { it.isStreaming }
                _messages.value = entities.map { entity ->
                    if (streaming != null && entity.id == streaming.id) streaming
                    else entity.toChatMessage()
                }
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_importState.value is ImportState.Importing) return
        viewModelScope.launch {
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "modelo.gguf"
            _importState.value = ImportState.Importing(displayName)
            try {
                // Libera o modelo atual: o arquivo e mapeado (mmap) e sera sobrescrito pela copia.
                stop()
                engine.unload()
                val dest = copyModelToInternalStorage(uri)
                settingsRepository.setLastModelName(displayName)
                val loaded = engine.loadModel(dest.absolutePath, settings.value, displayName)
                if (!loaded) {
                    _events.emit("Nao foi possivel carregar o modelo. Verifique se e um .gguf valido.")
                }
            } catch (e: IOException) {
                _events.emit("Erro ao copiar o modelo: ${e.message}")
            } finally {
                _importState.value = ImportState.Idle
            }
        }
    }

    /** Salva as configurações e recarrega o último modelo com elas (se houver um no aparelho). */
    fun applySettings(new: InferenceSettings) {
        settingsRepository.save(new)
        val file = modelFile()
        if (!file.exists()) {
            viewModelScope.launch { _events.emit("Configurações salvas. Carregue um modelo para aplicá-las.") }
            return
        }
        stop()
        viewModelScope.launch {
            val name = settingsRepository.lastModelName() ?: "modelo.gguf"
            val ok = engine.loadModel(file.absolutePath, new, name)
            _events.emit(
                if (ok) "Configurações aplicadas"
                else "Falha ao recarregar. Tente desligar a flash attention ou usar KV F16.",
            )
        }
    }

    fun unloadModel() {
        stop()
        engine.unload()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        if (engine.modelState.value !is ModelState.Loaded) {
            viewModelScope.launch { _events.emit("Carregue um modelo primeiro.") }
            return
        }

        // Historico interno (o que o modelo ve) = mensagens JA concluidas, capturado antes de
        // acrescentar a mensagem nova: nada e duplicado e nao ha mais o truque de dropLast(1).
        // A bolha de streaming e mensagens vazias nunca entram. Marca "gerando" de forma
        // sincrona para um segundo toque rapido nao iniciar outra geracao.
        val priorTurns = _messages.value
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .map { ChatTurn(if (it.role == Role.USER) ChatTurn.USER else ChatTurn.ASSISTANT, it.content) }
        _isGenerating.value = true
        _generationState.value = GenerationState.Generating

        sendJob = viewModelScope.launch {
            val (assistantId, prompt) = try {
                val (userId, _) = repository.addMessage(Role.USER, trimmed)
                appendLocal(ChatMessage(userId, Role.USER, trimmed))

                val (newAssistantId, _) = repository.addMessage(Role.ASSISTANT, "")
                currentAssistantId = newAssistantId
                appendLocal(
                    ChatMessage(
                        id = newAssistantId,
                        role = Role.ASSISTANT,
                        content = "",
                        isStreaming = true,
                    ),
                )
                processor.reset()

                val nCtx = settings.value.nCtx
                val turns = PromptHistory.select(
                    priorTurns + ChatTurn(ChatTurn.USER, trimmed),
                    maxTurns = if (nCtx > 4096) 24 else PromptHistory.MAX_TURNS,
                    maxChars = PromptHistory.charBudget(nCtx),
                )
                newAssistantId to withContext(Dispatchers.IO) { engine.buildPrompt(turns) }
            } catch (e: CancellationException) {
                _isGenerating.value = false
                _generationState.value = GenerationState.Cancelled
                throw e
            } catch (e: Exception) {
                _isGenerating.value = false
                _generationState.value = GenerationState.Error
                _events.emit("Erro ao preparar a mensagem: ${e.message}")
                return@launch
            }

            generationJob = viewModelScope.launch {
                val visibleSb = StringBuilder()
                val thinkingSb = StringBuilder()
                var cancelled = false
                var lastUiPublishMs = 0L
                var streamingThinking = false
                try {
                    engine.generate(prompt).collect { token ->
                        val out = processor.feed(token)
                        if (out.turnBoundaryReached) engine.cancel()

                        if (out.thinkingJustStarted) {
                            _generationState.value = GenerationState.Thinking
                            streamingThinking = true
                        }
                        if (out.thinkingDelta.isNotEmpty()) thinkingSb.append(out.thinkingDelta)
                        if (out.thinkingJustEnded) {
                            _generationState.value = GenerationState.Generating
                            streamingThinking = false
                        }
                        if (out.visibleDelta.isNotEmpty()) visibleSb.append(out.visibleDelta)

                        // Agrupa a publicação em no máximo ~16 atualizações por segundo.
                        // toString() e a cópia do item visual só acontecem no flush agrupado.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUiPublishMs >= 60L) {
                            updateStreaming(
                                assistantId,
                                visibleSb.toString(),
                                thinkingSb.toString(),
                                isThinking = streamingThinking,
                            )
                            lastUiPublishMs = now
                        }
                    }
                    // Flush residual buffer
                    val finalOut = processor.flush()
                    if (finalOut.thinkingDelta.isNotEmpty()) thinkingSb.append(finalOut.thinkingDelta)
                    if (finalOut.visibleDelta.isNotEmpty()) visibleSb.append(finalOut.visibleDelta)
                } catch (e: CancellationException) {
                    cancelled = true
                    // Ainda faz flush do que estiver no buffer
                    val finalOut = processor.flush()
                    if (finalOut.thinkingDelta.isNotEmpty()) thinkingSb.append(finalOut.thinkingDelta)
                    if (finalOut.visibleDelta.isNotEmpty()) visibleSb.append(finalOut.visibleDelta)
                    throw e
                } catch (e: Exception) {
                    _generationState.value = GenerationState.Error
                    _events.emit("Erro na inferencia: ${e.message}")
                } finally {
                    val finalContent = visibleSb.toString().trim()
                    val finalThinking = thinkingSb.toString().trim()
                    // A cancelled generation must still persist its partial output
                    // and restore UI state; Room writes are suspending operations.
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { repository.updateContent(assistantId, finalContent, finalThinking) }
                    }
                    finishStreaming(assistantId, finalContent, finalThinking)
                    _stats.value = engine.lastStats() ?: _stats.value
                    _isGenerating.value = false
                    _generationState.value = if (cancelled) {
                        GenerationState.Cancelled
                    } else if (_generationState.value == GenerationState.Error) {
                        GenerationState.Error
                    } else {
                        GenerationState.Completed
                    }
                    generationJob = null
                    currentAssistantId = -1L
                }
            }
        }
    }

    fun stop() {
        if (!_isGenerating.value) return
        sendJob?.cancel()          // cobre o toque em "parar" durante o preparo, antes da geracao existir
        generationJob?.cancel()
        generationJob = null
        engine.cancel()
        // O finally do job cuida de persistir o texto parcial e resetar estados
    }

    fun toggleThinkingVisibility(messageId: Long) {
        if (!expandedThinking.add(messageId)) expandedThinking.remove(messageId)
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(showThinking = messageId in expandedThinking) else it
        }
    }

    fun clearChat() {
        stop()
        viewModelScope.launch {
            repository.clear()
            expandedThinking.clear()
            _messages.value = emptyList()
            _generationState.value = GenerationState.Idle
        }
    }

    private fun modelFile(): File = File(File(context.filesDir, "models"), "model.gguf")

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private suspend fun copyModelToInternalStorage(uri: Uri): File =
        withContext(Dispatchers.IO) {
            val dest = modelFile().also { it.parentFile?.mkdirs() }
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Nao foi possivel ler o arquivo selecionado")
            dest
        }

    private fun appendLocal(message: ChatMessage) {
        val current = _messages.value
        _messages.value = if (current.any { it.id == message.id }) {
            current.map { if (it.id == message.id) message else it }
        } else {
            current + message
        }
    }

    private fun updateStreaming(
        id: Long,
        content: String,
        thinking: String,
        isThinking: Boolean,
    ) {
        _messages.value = _messages.value.map {
            if (it.id == id) {
                it.copy(
                    content = content,
                    thinkingContent = thinking,
                    isStreaming = true,
                    isThinking = isThinking,
                )
            } else {
                it
            }
        }
    }

    private fun finishStreaming(id: Long, content: String, thinking: String) {
        _messages.value = _messages.value.map {
            if (it.id == id) {
                it.copy(
                    content = content,
                    thinkingContent = thinking,
                    isStreaming = false,
                    isThinking = false,
                )
            } else {
                it
            }
        }
    }

    override fun onCleared() {
        engine.cancel()
        super.onCleared()
    }

    private fun MessageEntity.toChatMessage() = ChatMessage(
        id = id,
        role = if (role == "user") Role.USER else Role.ASSISTANT,
        content = content,
        thinkingContent = thinking,
        showThinking = id in expandedThinking,
    )
}
