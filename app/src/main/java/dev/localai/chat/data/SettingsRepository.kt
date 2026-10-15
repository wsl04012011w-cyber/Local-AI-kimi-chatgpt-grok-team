package dev.localai.chat.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.localai.chat.llm.InferenceSettings
import dev.localai.chat.llm.KvCacheType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persiste as configurações de inferência (SharedPreferences, sem dependência extra). */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("inference_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<InferenceSettings> = _settings.asStateFlow()

    fun save(settings: InferenceSettings) {
        _settings.value = settings
        prefs.edit()
            .putInt(K_CTX, settings.nCtx)
            .putInt(K_THREADS, settings.nThreads)
            .putBoolean(K_FA, settings.flashAttn)
            .putInt(K_KV_K, settings.kvTypeK.code)
            .putInt(K_KV_V, settings.kvTypeV.code)
            .putBoolean(K_MMAP, settings.useMmap)
            .putBoolean(K_MLOCK, settings.useMlock)
            .putBoolean(K_REUSE, settings.reuseCache)
            .apply()
    }

    fun lastModelName(): String? = prefs.getString(K_MODEL_NAME, null)

    fun setLastModelName(name: String) {
        prefs.edit().putString(K_MODEL_NAME, name).apply()
    }

    private fun load(): InferenceSettings {
        val d = InferenceSettings()
        return InferenceSettings(
            nCtx = prefs.getInt(K_CTX, d.nCtx),
            nThreads = prefs.getInt(K_THREADS, d.nThreads),
            flashAttn = prefs.getBoolean(K_FA, d.flashAttn),
            kvTypeK = KvCacheType.fromCode(prefs.getInt(K_KV_K, d.kvTypeK.code)),
            kvTypeV = KvCacheType.fromCode(prefs.getInt(K_KV_V, d.kvTypeV.code)),
            useMmap = prefs.getBoolean(K_MMAP, d.useMmap),
            useMlock = prefs.getBoolean(K_MLOCK, d.useMlock),
            reuseCache = prefs.getBoolean(K_REUSE, d.reuseCache),
        )
    }

    private companion object {
        const val K_CTX = "n_ctx"
        const val K_THREADS = "n_threads"
        const val K_FA = "flash_attn"
        const val K_KV_K = "kv_type_k"
        const val K_KV_V = "kv_type_v"
        const val K_MMAP = "use_mmap"
        const val K_MLOCK = "use_mlock"
        const val K_REUSE = "reuse_cache"
        const val K_MODEL_NAME = "last_model_name"
    }
}
