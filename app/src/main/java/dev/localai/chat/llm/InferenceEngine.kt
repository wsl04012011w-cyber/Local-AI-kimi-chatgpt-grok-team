package dev.localai.chat.llm

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Fachada thread-safe sobre [LlamaBridge]. Gerencia estado do modelo
 * e expoe geracao como Flow<String> (streaming de tokens).
 */
@Singleton
class InferenceEngine @Inject constructor() {

    private val _modelState = kotlinx.coroutines.flow.MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: kotlinx.coroutines.flow.StateFlow<ModelState> = _modelState

    suspend fun loadModel(
        path: String,
        settings: InferenceSettings,
        displayName: String = File(path).name,
    ): Boolean = withContext(Dispatchers.IO) {
        if (_modelState.value is ModelState.Loaded) unload()
        _modelState.value = ModelState.Loading
        val ok = runCatching {
            LlamaBridge.loadModel(
                path = path,
                nCtx = settings.nCtx,
                nThreads = settings.nThreads,
                flashAttn = settings.effectiveFlashAttn,
                kvTypeK = settings.kvTypeK.code,
                kvTypeV = settings.kvTypeV.code,
                useMmap = settings.useMmap,
                useMlock = settings.useMlock,
                reuseCache = settings.reuseCache,
            )
        }.onFailure { Log.e(TAG, "loadModel falhou", it) }.getOrDefault(false)
        _modelState.value = if (ok) {
            ModelState.Loaded(displayName)
        } else {
            ModelState.Error("Falha ao carregar o modelo")
        }
        ok
    }

    /** Estatisticas da ultima geracao (reuso de cache, tokens/s). */
    fun lastStats(): GenerationStats? =
        runCatching { GenerationStats.parse(LlamaBridge.lastStats()) }.getOrNull()

    fun unload() {
        cancel()
        runCatching { LlamaBridge.unloadModel() }
        _modelState.value = ModelState.NotLoaded
    }

    /**
     * Streaming de tokens. Cancelar o Flow (ou chamar [cancel]) interrompe
     * o loop nativo via flag cooperativa. O texto parcial já emitido é
     * preservado pelo coletor.
     */
    fun generate(prompt: String): Flow<String> = callbackFlow {
        try {
            LlamaBridge.generate(prompt) { token ->
                if (isActive) trySend(token)
            }
            close()
        } catch (e: LinkageError) {
            // Biblioteca nativa ausente/incompativel: vira erro tratavel em vez de derrubar o app
            close(IllegalStateException("Falha ao acessar a biblioteca nativa: ${e.message}", e))
        } catch (e: Exception) {
            close(e)
        }
        // O bloco acima so termina quando o nativo ja retornou; nao ha o que cancelar aqui.
        // (O cancelamento real vem de stop() -> cancel(), que sinaliza o laco nativo.)
        awaitClose { }
    }
        // Buffer ilimitado: com o buffer padrao (64) trySend descartaria tokens se o coletor atrasasse.
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    /**
     * Monta o prompt para o modelo carregado. Ordem: (1) template embutido no GGUF, via
     * llama.cpp; (2) template da familia detectada (Gemma, ChatML). Qwen e Gemma nunca
     * compartilham um template fixo. Roda JNI: chame de Dispatchers.IO.
     */
    fun buildPrompt(turns: List<ChatTurn>): String {
        val all = listOf(ChatTurn(ChatTurn.SYSTEM, ChatTemplates.DEFAULT_SYSTEM)) + turns

        val native = runCatching {
            LlamaBridge.applyChatTemplate(
                all.map { it.role }.toTypedArray(),
                all.map { it.content }.toTypedArray(),
            )
        }.getOrNull()
        if (!native.isNullOrEmpty()) {
            Log.i(TAG, "template=embutido_no_gguf turnos=${all.size}")
            return native
        }

        val templateText = runCatching { LlamaBridge.chatTemplate() }.getOrDefault("")
        val detected = ChatTemplates.detectFamily(templateText) { token ->
            runCatching { LlamaBridge.isSingleSpecialToken(token) }.getOrDefault(false)
        }
        val family = detected ?: ChatFamily.CHATML
        Log.i(TAG, "template=familia:$family detectada=${detected != null} turnos=${all.size}")
        return ChatTemplates.format(family, all)
    }

    fun cancel() = LlamaBridge.cancelGeneration()

    private companion object {
        const val TAG = "LocalAI-Engine"
    }
}
