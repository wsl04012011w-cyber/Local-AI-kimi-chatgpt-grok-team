package dev.localai.chat

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import dev.localai.chat.llm.ImportState
import dev.localai.chat.llm.InferenceSettings
import dev.localai.chat.llm.KvCacheType
import dev.localai.chat.llm.ModelState
import dev.localai.chat.ui.chat.ChatMessage
import dev.localai.chat.ui.chat.ChatViewModel
import dev.localai.chat.ui.chat.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Mantem a interface JavaScript separada do motor Android: GGUF, Room, configuracoes e
 * inferencia llama.cpp continuam do lado Kotlin/JNI.
 */
class LocalAiReactModule(
    private val appContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(appContext) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptions = mutableListOf<Job>()
    private var observing = false

    override fun getName(): String = "LocalAI"

    @ReactMethod
    fun getInitialState(promise: Promise) {
        mainScope.launch {
            try {
                val vm = viewModel()
                startObserving(vm)
                promise.resolve(stateSnapshot(vm))
            } catch (error: Throwable) {
                AppLogger.error("ReactNativeBridge", "Nao foi possivel iniciar o bridge", error)
                promise.reject("LOCAL_AI_BRIDGE_INIT", error.message, error)
            }
        }
    }

    @ReactMethod
    fun pickModel() {
        mainScope.launch {
            try {
                val activity = appContext.currentActivity as? MainActivity
                    ?: error("A tela principal ainda nao esta disponivel.")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivityForResult(intent, MODEL_PICKER_REQUEST_CODE)
            } catch (error: Throwable) {
                reportFailure("Abrir seletor de modelo", error)
            }
        }
    }

    @ReactMethod
    fun sendMessage(text: String) = withViewModel("Enviar mensagem") { it.send(text) }

    @ReactMethod
    fun stopGeneration() = withViewModel("Parar geracao") { it.stop() }

    @ReactMethod
    fun clearChat() = withViewModel("Limpar conversa") { it.clearChat() }

    @ReactMethod
    fun unloadModel() = withViewModel("Descarregar modelo") { it.unloadModel() }

    @ReactMethod
    fun toggleThinking(messageId: Double) =
        withViewModel("Alternar raciocinio") { it.toggleThinkingVisibility(messageId.toLong()) }

    @ReactMethod
    fun applySettings(values: ReadableMap) {
        withViewModel("Aplicar configuracoes") { vm ->
            val current = vm.settings.value
            val kvK = KvCacheType.fromCode(readInt(values, "kvTypeK", current.kvTypeK.code))
            val kvV = KvCacheType.fromCode(readInt(values, "kvTypeV", current.kvTypeV.code))
            val updated = current.copy(
                nCtx = readInt(values, "nCtx", current.nCtx).coerceIn(512, 32768),
                nThreads = readInt(values, "nThreads", current.nThreads)
                    .coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
                flashAttn = readBoolean(values, "flashAttn", current.flashAttn) || kvV.isQuantized,
                kvTypeK = kvK,
                kvTypeV = kvV,
                useMmap = readBoolean(values, "useMmap", current.useMmap),
                useMlock = readBoolean(values, "useMlock", current.useMlock),
                reuseCache = readBoolean(values, "reuseCache", current.reuseCache),
            )
            vm.applySettings(updated)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required by NativeEventEmitter; observers start from getInitialState().
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required by NativeEventEmitter; this module owns one observer group.
    }

    private fun withViewModel(actionName: String, action: (ChatViewModel) -> Unit) {
        mainScope.launch {
            try {
                val vm = viewModel()
                action(vm)
                emitState(vm)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(actionName, error)
            }
        }
    }

    private fun viewModel(): ChatViewModel {
        val activity = appContext.currentActivity as? ComponentActivity
            ?: error("A Activity principal ainda nao esta disponivel.")
        return ViewModelProvider(activity)[ChatViewModel::class.java]
    }

    private fun startObserving(vm: ChatViewModel) {
        if (observing) return
        observing = true
        subscriptions += mainScope.launch { vm.messages.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.modelState.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.settings.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.isGenerating.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.generationState.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.importState.collect { emitState(vm) } }
        subscriptions += mainScope.launch { vm.stats.collect { emitState(vm) } }
        subscriptions += mainScope.launch {
            vm.events.collect { message ->
                emit("LocalAINotice", Arguments.createMap().apply {
                    putString("message", message)
                })
            }
        }
    }

    private fun emitState(vm: ChatViewModel) {
        emit("LocalAIState", stateSnapshot(vm))
    }

    private fun emit(eventName: String, payload: Any) {
        runCatching {
            appContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, payload)
        }
    }

    private fun stateSnapshot(vm: ChatViewModel): WritableMap =
        Arguments.createMap().apply {
            val messageArray = Arguments.createArray()
            vm.messages.value.forEach { messageArray.pushMap(messageSnapshot(it)) }
            putArray("messages", messageArray)

            when (val model = vm.modelState.value) {
                ModelState.NotLoaded -> {
                    putString("modelStatus", "notLoaded")
                    putString("modelName", "")
                    putString("modelError", "")
                }
                ModelState.Loading -> {
                    putString("modelStatus", "loading")
                    putString("modelName", "")
                    putString("modelError", "")
                }
                is ModelState.Loaded -> {
                    putString("modelStatus", "loaded")
                    putString("modelName", model.modelName)
                    putString("modelError", "")
                }
                is ModelState.Error -> {
                    putString("modelStatus", "error")
                    putString("modelName", "")
                    putString("modelError", model.message)
                }
            }

            putBoolean("isGenerating", vm.isGenerating.value)
            putString("generationState", vm.generationState.value.name.lowercase())

            val importing = vm.importState.value as? ImportState.Importing
            putBoolean("importing", importing != null)
            putString("importName", importing?.fileName.orEmpty())

            val settings = vm.settings.value
            putMap("settings", Arguments.createMap().apply {
                putInt("nCtx", settings.nCtx)
                putInt("nThreads", settings.nThreads)
                putInt("nThreadsBatch", settings.nThreadsBatch)
                putBoolean("flashAttn", settings.flashAttn)
                putBoolean("effectiveFlashAttn", settings.effectiveFlashAttn)
                putInt("kvTypeK", settings.kvTypeK.code)
                putInt("kvTypeV", settings.kvTypeV.code)
                putString("kvTypeKLabel", settings.kvTypeK.label)
                putString("kvTypeVLabel", settings.kvTypeV.label)
                putBoolean("useMmap", settings.useMmap)
                putBoolean("useMlock", settings.useMlock)
                putBoolean("reuseCache", settings.reuseCache)
            })

            val stats = vm.stats.value
            if (stats == null) {
                putNull("stats")
            } else {
                putMap("stats", Arguments.createMap().apply {
                    putInt("reusedTokens", stats.reusedTokens)
                    putInt("promptTokens", stats.promptTokens)
                    putInt("generatedTokens", stats.generatedTokens)
                    putDouble("promptMs", stats.promptMs.toDouble())
                    putDouble("generationMs", stats.generationMs.toDouble())
                    putDouble("genTokensPerSecond", stats.genTokensPerSecond.toDouble())
                })
            }
        }

    private fun messageSnapshot(message: ChatMessage): WritableMap =
        Arguments.createMap().apply {
            putDouble("id", message.id.toDouble())
            putString("role", if (message.role == Role.USER) "user" else "assistant")
            putString("content", message.content)
            putString("thinkingContent", message.thinkingContent)
            putBoolean("isStreaming", message.isStreaming)
            putBoolean("isThinking", message.isThinking)
            putBoolean("showThinking", message.showThinking)
        }

    private fun readInt(values: ReadableMap, key: String, fallback: Int): Int =
        if (values.hasKey(key) && !values.isNull(key)) values.getInt(key) else fallback

    private fun readBoolean(values: ReadableMap, key: String, fallback: Boolean): Boolean =
        if (values.hasKey(key) && !values.isNull(key)) values.getBoolean(key) else fallback

    private fun reportFailure(action: String, error: Throwable) {
        AppLogger.error("ReactNativeBridge", "Falha: $action", error)
        emit("LocalAINotice", Arguments.createMap().apply {
            putString("message", error.message ?: "Nao foi possivel executar: $action")
        })
    }

    override fun invalidate() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        mainScope.cancel()
        super.invalidate()
    }

    companion object {
        const val MODEL_PICKER_REQUEST_CODE = 5741
    }
}
