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
import dev.localai.chat.AppLogger
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
        }.onFailure { error ->
            AppLogger.error("WebBridge", "Falha ao processar comando JavaScript", error)
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
                var fallbackAlreadyShown = false
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
                AppLogger.info("WebView", "Criando WebView e carregando asset local")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        val details = "JS ${message.messageLevel()} em ${message.sourceId()}:${message.lineNumber()} — ${message.message()}"
                        when (message.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> AppLogger.error("WebView-JS", details)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> AppLogger.warn("WebView-JS", details)
                            else -> AppLogger.info("WebView-JS", details)
                        }
                        return true
                    }

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

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        AppLogger.info("WebView", "Carregamento iniciado: ${url}")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        AppLogger.info("WebView", "Carregamento finalizado: ${url}")
                        if (url.startsWith("file:///android_asset/")) {
                            // onPageFinished sozinho não detecta erro de sintaxe/execução JavaScript.
                            // Valida se o bootstrap realmente criou a API que o Compose usa.
                            view.evaluateJavascript(
                                "(typeof window.LocalAI !== 'undefined' && typeof window.LocalAI.setState === 'function')"
                            ) { result ->
                                if (result == "true") {
                                    pageReady = true
                                    AppLogger.info("WebView", "Página local e bootstrap JavaScript prontos")
                                } else {
                                    pageReady = false
                                    AppLogger.error(
                                        "WebView",
                                        "A página terminou de carregar, mas window.LocalAI não foi inicializado (resultado JS: ${result})"
                                    )
                                    if (!fallbackAlreadyShown) {
                                        fallbackAlreadyShown = true
                                        showWebViewDiagnosticFallback(
                                            view,
                                            "O HTML foi aberto, mas o JavaScript da interface não inicializou. Consulte o arquivo de diagnóstico."
                                        )
                                    }
                                }
                            }
                        } else {
                            AppLogger.warn("WebView", "Página finalizada fora do asset local: ${url}")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        AppLogger.error(
                            "WebView",
                            "Erro ao carregar recurso: url=${request.url}, mainFrame=${request.isForMainFrame}, code=${error.errorCode}, description=${error.description}"
                        )
                        if (request.isForMainFrame && !fallbackAlreadyShown) {
                            fallbackAlreadyShown = true
                            showWebViewDiagnosticFallback(view, "Falha ao carregar a interface web (código ${error.errorCode}: ${error.description}).")
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        AppLogger.error(
                            "WebView",
                            "Processo do renderer terminou: crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}"
                        )
                        pageReady = false
                        showWebViewDiagnosticFallback(view, "O processo de renderização do WebView foi encerrado. Reinicie o aplicativo se a tela não voltar.")
                        return true
                    }
                }
                addJavascriptInterface(bridge, "LocalAIAndroid")
                loadUrl("file:///android_asset/index.html")
                postDelayed({
                    if (!pageReady && !fallbackAlreadyShown) {
                        fallbackAlreadyShown = true
                        AppLogger.error("WebView", "Timeout: a interface não terminou de carregar em 8 segundos.")
                        showWebViewDiagnosticFallback(this, "A interface não terminou de carregar em 8 segundos.")
                    }
                }, 8000L)
                webView = this
            }
        },
    )
}


private fun showWebViewDiagnosticFallback(view: WebView, reason: String) {
    val safeReason = android.text.TextUtils.htmlEncode(reason)
    val safePath = android.text.TextUtils.htmlEncode(AppLogger.logFilePath)
    view.post {
        val html = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta charset="utf-8">
              <style>
                body{margin:0;background:#090a0c;color:#f4f4f5;font:16px sans-serif;padding:28px;line-height:1.5}
                main{max-width:620px;margin:8vh auto;border:1px solid #3b3d45;border-radius:18px;padding:24px;background:#15161a}
                h1{font-size:22px;margin-top:0} .reason{color:#ffb4ab;overflow-wrap:anywhere}
                code{display:block;background:#090a0c;padding:12px;border-radius:8px;overflow-wrap:anywhere;white-space:pre-wrap}
                p{color:#c4c6ce}
              </style>
            </head>
            <body><main>
              <h1>A interface do Local AI não carregou</h1>
              <p class="reason">$safeReason</p>
              <p>O diagnóstico foi configurado para ser salvo neste arquivo:</p>
              <code>$safePath</code>
              <p>Procure o arquivo acima com o gerenciador de arquivos com acesso apropriado, ou use Shizuku/root. O caminho fica dentro dos dados privados do aplicativo.</p>
            </main></body></html>
        """.trimIndent()
        try {
            view.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        } catch (error: Throwable) {
            AppLogger.error("WebView", "Falha ao mostrar tela alternativa de diagnóstico", error)
        }
    }
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
