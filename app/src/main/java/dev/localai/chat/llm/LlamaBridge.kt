package dev.localai.chat.llm

/**
 * Ponte JNI para o llama.cpp compilado como libllama-jni.so.
 * Os simbolos nativos sao implementados em cpp/llama-jni.cpp.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama-jni")
    }

    /**
     * Carrega um modelo .gguf com os parametros de [InferenceSettings]. Retorna true em sucesso.
     * [kvTypeK]/[kvTypeV] sao [KvCacheType.code]. V quantizado liga flash attention sozinho.
     */
    external fun loadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        flashAttn: Boolean,
        kvTypeK: Int,
        kvTypeV: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        reuseCache: Boolean,
    ): Boolean

    /** Libera modelo + contexto da memoria. */
    external fun unloadModel()

    external fun isModelLoaded(): Boolean

    /**
     * Roda inferencia com streaming. Bloqueia a thread chamadora;
     * chame de um dispatcher de IO. O callback recebe cada pedaco
     * de texto gerado. Retorna o texto completo no final.
     * Falhas nativas chegam como RuntimeException com "etapa=... codigo=...".
     */
    external fun generate(prompt: String, callback: TokenCallback): String

    /** Texto do chat template embutido no GGUF, ou "" se o modelo nao tiver. */
    external fun chatTemplate(): String

    /**
     * Aplica o template embutido no GGUF (llama_chat_apply_template) e ja inclui o prefixo da
     * resposta do assistente. Retorna null se nao houver template ou se ele nao for suportado.
     */
    external fun applyChatTemplate(roles: Array<String>, contents: Array<String>): String?

    /** true se [text] e exatamente 1 token especial no vocabulario do modelo carregado. */
    external fun isSingleSpecialToken(text: String): Boolean

    /** "reaproveitados|prompt_novos|gerados|prompt_ms|gen_ms" da ultima geracao. */
    external fun lastStats(): String

    /** Sinaliza cancelamento para o loop de geracao nativo. */
    external fun cancelGeneration()

    fun interface TokenCallback {
        fun onToken(token: String)
    }
}
