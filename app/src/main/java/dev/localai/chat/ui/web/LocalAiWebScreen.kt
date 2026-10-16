package dev.localai.chat.ui.web

import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localai.chat.llm.GenerationStats
import dev.localai.chat.llm.ImportState
import dev.localai.chat.llm.InferenceSettings
import dev.localai.chat.llm.KvCacheType
import dev.localai.chat.llm.ModelState
import dev.localai.chat.ui.chat.ChatMessage
import dev.localai.chat.ui.chat.ChatViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interface web local. O HTML cuida da apresentação; Kotlin mantém o acesso a arquivos,
 * configurações, persistência e ciclo de vida do motor JNI.
 */
@Composable
fun LocalAiWebScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::importModel) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val pendingEvents = remember { mutableStateListOf<String>() }
    val bridge = remember { LocalAiJavascriptBridge() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun dispatchCommand(raw: String) {
        runCatching {
            val command = JSONObject(raw)
            when (command.optString("type")) {
                "uiReady" -> Unit
                "loadModel" -> modelPicker.launch(arrayOf("*/*"))
                "send" -> viewModel.send(command.optString("text"))
                "stop" -> viewModel.stop()
                "clearChat" -> viewModel.clearChat()
                "unloadModel" -> viewModel.unloadModel()
                "toggleThinking" -> viewModel.toggleThinkingVisibility(command.optLong("id"))
                "applySettings" -> {
                    val current = viewModel.settings.value
                    val values = command.optJSONObject("settings") ?: JSONObject()
                    val kvK = KvCacheType.fromCode(values.optInt("kvTypeK", current.kvTypeK.code))
                    val kvV = KvCacheType.fromCode(values.optInt("kvTypeV", current.kvTypeV.code))
                    val requestedFa = values.optBoolean("flashAttn", current.flashAttn)
                    val updated = current.copy(
                        nCtx = values.optInt("nCtx", current.nCtx)
                            .coerceIn(512, 32768),
                        nThreads = values.optInt("nThreads", current.nThreads)
                            .coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
                        flashAttn = requestedFa || kvV.isQuantized,
                        kvTypeK = kvK,
                        kvTypeV = kvV,
                        useMmap = values.optBoolean("useMmap", current.useMmap),
                        useMlock = values.optBoolean("useMlock", current.useMlock),
                        reuseCache = values.optBoolean("reuseCache", current.reuseCache),
                    )
                    viewModel.applySettings(updated)
                }
            }
        }.onFailure {
            webView?.post {
                webView?.evaluateJavascript(
                    "window.LocalAI && window.LocalAI.showToast('Não foi possível executar a ação.');",
                    null,
                )
            }
        }
    }

    bridge.onMessage = { raw -> mainHandler.post { dispatchCommand(raw) } }

    val stateJson = remember(modelState, settings, stats, isGenerating, generationState, importState) {
        createStateJson(modelState, settings, stats, isGenerating, generationState.name, importState)
    }
    LaunchedEffect(stateJson, pageReady) {
        if (pageReady) {
            webView?.evaluateJavascript(
                "window.LocalAI && window.LocalAI.setState(JSON.parse(${JSONObject.quote(stateJson)}));",
                null,
            )
        }
    }

    // O histórico completo só é enviado quando sua estrutura muda. No streaming, envia-se apenas
    // a última mensagem, sem serializar novamente todo o histórico a cada lote de texto.
    val lastMessagesSent = remember { mutableStateOf<List<ChatMessage>?>(null) }
    LaunchedEffect(messages, pageReady) {
        if (!pageReady) return@LaunchedEffect
        val previous = lastMessagesSent.value
        val sendSnapshot = previous == null ||
            previous.size != messages.size ||
            previous.dropLast(1) != messages.dropLast(1)
        if (sendSnapshot) {
            val json = messagesToJson(messages)
            webView?.evaluateJavascript(
                "window.LocalAI && window.LocalAI.setMessages(JSON.parse(${JSONObject.quote(json)}));",
                null,
            )
        } else if (previous.lastOrNull() != messages.lastOrNull()) {
            val last = messages.lastOrNull()
            if (last != null) {
                val json = messageToJson(last).toString()
                webView?.evaluateJavascript(
                    "window.LocalAI && window.LocalAI.updateMessage(JSON.parse(${JSONObject.quote(json)}));",
                    null,
                )
            }
        }
        lastMessagesSent.value = messages
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> pendingEvents.add(event) }
    }
    LaunchedEffect(pageReady, pendingEvents.size) {
        if (pageReady && pendingEvents.isNotEmpty()) {
            val queued = pendingEvents.toList()
            pendingEvents.clear()
            queued.forEach { message ->
                webView?.evaluateJavascript(
                    "window.LocalAI && window.LocalAI.showToast(${JSONObject.quote(message)});",
                    null,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.removeJavascriptInterface("LocalAIAndroid")
            webView?.destroy()
            webView = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(android.graphics.Color.rgb(9, 10, 12))
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                val webSettings = this.settings
                webSettings.javaScriptEnabled = true
                webSettings.domStorageEnabled = true
                webSettings.allowFileAccess = true
                webSettings.allowContentAccess = false
                webSettings.javaScriptCanOpenWindowsAutomatically = false
                webSettings.setSupportMultipleWindows(false)
                webSettings.allowFileAccessFromFileURLs = false
                webSettings.allowUniversalAccessFromFileURLs = false
                webChromeClient = object : WebChromeClient() {
                    override fun onJsConfirm(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: JsResult?,
                    ): Boolean {
                        if (result == null) return false
                        AlertDialog.Builder(viewContext)
                            .setMessage(message.orEmpty())
                            .setPositiveButton("Confirmar") { _, _ -> result.confirm() }
                            .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .show()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        defaultValue: String?,
                        result: android.webkit.JsPromptResult?,
                    ): Boolean {
                        if (result == null) return false
                        val input = EditText(viewContext).apply { setText(defaultValue.orEmpty()) }
                        AlertDialog.Builder(viewContext)
                            .setTitle(message.orEmpty())
                            .setView(input)
                            .setPositiveButton("Confirmar") { _, _ -> result.confirm(input.text.toString()) }
                            .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .show()
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !request.url.toString().startsWith("file:///android_asset/")

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        !url.startsWith("file:///android_asset/")

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (url.startsWith("file:///android_asset/")) pageReady = true
                    }
                }
                addJavascriptInterface(bridge, "LocalAIAndroid")
                loadUrl("file:///android_asset/index.html")
                webView = this
            }
        },
    )
}

private class LocalAiJavascriptBridge {
    @Volatile
    var onMessage: ((String) -> Unit)? = null

    @JavascriptInterface
    fun postMessage(message: String) {
        onMessage?.invoke(message)
    }
}

private fun createStateJson(
    modelState: ModelState,
    settings: InferenceSettings,
    stats: GenerationStats?,
    isGenerating: Boolean,
    generationState: String,
    importState: ImportState,
): String {
    val model = JSONObject()
    when (modelState) {
        ModelState.NotLoaded -> model.put("status", "not_loaded").put("name", "")
        ModelState.Loading -> model.put("status", "loading").put("name", "")
        is ModelState.Loaded -> model.put("status", "loaded").put("name", modelState.modelName)
        is ModelState.Error -> model.put("status", "error").put("name", "").put("message", modelState.message)
    }
    val settingsJson = JSONObject()
        .put("nCtx", settings.nCtx)
        .put("nThreads", settings.nThreads)
        .put("nThreadsBatch", settings.nThreadsBatch)
        .put("maxThreads", Runtime.getRuntime().availableProcessors().coerceAtLeast(settings.nThreads))
        .put("defaultThreads", InferenceSettings.defaultThreads())
        .put("flashAttn", settings.flashAttn)
        .put("effectiveFlashAttn", settings.effectiveFlashAttn)
        .put("kvTypeK", settings.kvTypeK.code)
        .put("kvTypeV", settings.kvTypeV.code)
        .put("useMmap", settings.useMmap)
        .put("useMlock", settings.useMlock)
        .put("reuseCache", settings.reuseCache)
        .put("modelLoaded", modelState is ModelState.Loaded)
    val statsJson = JSONObject()
    if (stats != null) {
        statsJson.put("reusedTokens", stats.reusedTokens)
            .put("promptTokens", stats.promptTokens)
            .put("generatedTokens", stats.generatedTokens)
            .put("tokensPerSecond", stats.genTokensPerSecond.toDouble())
            .put("promptMs", stats.promptMs)
            .put("generationMs", stats.generationMs)
    }
    val importing = importState is ImportState.Importing
    val importName = (importState as? ImportState.Importing)?.fileName.orEmpty()
    return JSONObject()
        .put("model", model)
        .put("settings", settingsJson)
        .put("stats", if (stats == null) JSONObject.NULL else statsJson)
        .put("generating", isGenerating)
        .put("generationState", generationState)
        .put("importing", importing)
        .put("importName", importName)
        .toString()
}

private fun messagesToJson(messages: List<ChatMessage>): String =
    JSONArray().apply { messages.forEach { put(messageToJson(it)) } }.toString()

private fun messageToJson(message: ChatMessage): JSONObject =
    JSONObject()
        .put("id", message.id)
        .put("role", message.role.name)
        .put("content", message.content)
        .put("thinkingContent", message.thinkingContent)
        .put("isStreaming", message.isStreaming)
        .put("isThinking", message.isThinking)
        .put("showThinking", message.showThinking)
