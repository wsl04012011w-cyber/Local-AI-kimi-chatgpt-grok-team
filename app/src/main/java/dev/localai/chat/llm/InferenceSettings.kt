package dev.localai.chat.llm

/** Tipo de quantização do KV cache. [code] é o contrato com o JNI (kv_type_from_code). */
enum class KvCacheType(val code: Int, val label: String, val bitsPerElement: Float) {
    F16(0, "F16", 16f),
    Q8_0(1, "Q8_0", 8.5f),
    Q5_1(2, "Q5_1", 6f),
    Q5_0(3, "Q5_0", 5.5f),
    Q4_1(4, "Q4_1", 5f),
    Q4_0(5, "Q4_0", 4.5f),
    ;

    val isQuantized: Boolean get() = this != F16

    companion object {
        fun fromCode(code: Int): KvCacheType = entries.firstOrNull { it.code == code } ?: F16
    }
}

/** Parâmetros de carga do modelo. Mudar qualquer um exige recarregar o modelo. */
data class InferenceSettings(
    val nCtx: Int = 4096,
    val nThreads: Int = defaultThreads(),
    val flashAttn: Boolean = false,
    val kvTypeK: KvCacheType = KvCacheType.F16,
    val kvTypeV: KvCacheType = KvCacheType.F16,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val reuseCache: Boolean = true,
) {
    /** Prefill usa todos os núcleos disponíveis; não altera preferências antigas. */
    val nThreadsBatch: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** V quantizado exige flash attention no llama.cpp. */
    val effectiveFlashAttn: Boolean get() = flashAttn || kvTypeV.isQuantized

    /** Tamanho aproximado do KV cache relativo ao F16 (0..1). */
    val kvSizeRatio: Float get() = (kvTypeK.bitsPerElement + kvTypeV.bitsPerElement) / 32f

    companion object {
        val CTX_OPTIONS = listOf(2048, 4096, 8192)

        fun defaultThreads(): Int {
            val fallback = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            return runCatching {
                val cpuRoot = java.io.File("/sys/devices/system/cpu")
                val frequencies = cpuRoot.listFiles { f ->
                    f.isDirectory && f.name.matches(Regex("cpu\\d+"))
                }.orEmpty().mapNotNull { cpu ->
                    runCatching {
                        java.io.File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                    }.getOrNull()
                }
                val maximum = frequencies.maxOrNull() ?: return@runCatching fallback
                frequencies.count { it == maximum }.coerceAtLeast(1)
            }.getOrDefault(fallback)
        }
    }
}

/** Estatísticas da última geração (vindas do nativo; sem conteúdo de texto). */
data class GenerationStats(
    val reusedTokens: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMs: Long,
    val generationMs: Long,
) {
    val genTokensPerSecond: Float
        get() = if (generationMs > 0) generatedTokens * 1000f / generationMs else 0f

    companion object {
        fun parse(raw: String): GenerationStats? {
            val p = raw.split('|')
            if (p.size != 5) return null
            return runCatching {
                GenerationStats(
                    reusedTokens = p[0].toInt(),
                    promptTokens = p[1].toInt(),
                    generatedTokens = p[2].toInt(),
                    promptMs = p[3].toLong(),
                    generationMs = p[4].toLong(),
                )
            }.getOrNull()
        }
    }
}
