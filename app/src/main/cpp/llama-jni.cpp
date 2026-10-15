// Ponte JNI entre Kotlin (dev.localai.chat.llm.LlamaBridge) e o llama.cpp.
// API C publica: include/llama.h da tag pinada pelo workflow (v0.3.0).
//
// Mudancas em relacao a v0.1/v0.2 (ver CORRECOES.md):
//  - strings Java <-> C++ via UTF-16 (NewString/GetStringChars) em vez de NewStringUTF:
//    pedacos de token com UTF-8 incompleto / 4 bytes derrubavam a VM (CheckJNI) ou geravam lixo;
//  - parada por llama_vocab_is_eog (fim de turno de Gemma/Qwen), nao so EOS;
//  - prompt tokenizado com parse_special=true (marcadores do template viram tokens de verdade);
//  - chat template embutido no GGUF exposto ao Kotlin (applyChatTemplate / chatTemplate);
//  - erros nativos viram RuntimeException com etapa + codigo, em vez de string vazia;
//  - instrumentacao por etapa SEM registrar conteudo de prompt/resposta;
//  - v0.3.0: flash attention, KV cache quantizado (K/V f16..q4_0), mmap/mlock, n_ctx/threads
//    configuraveis e reaproveitamento do prefixo do KV cache entre turnos;
//  - APIs depreciadas trocadas (llama_kv_cache_seq_rm, llama_load_model_from_file, llama_free_model).
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "utf8_stream.h"

#define LOG_TAG "LocalAI-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBatch = 512;      // n_batch == n_ubatch: limita o buffer de logits (vocab grande do Qwen)
constexpr int kMinGenRoom = 32;  // minimo de espaco no contexto para gerar algo

llama_model*       g_model = nullptr;
llama_context*     g_ctx   = nullptr;
const llama_vocab* g_vocab = nullptr;
std::atomic<bool>  g_cancelled{false};
std::atomic<bool>  g_loaded{false};   // permite isModelLoaded sem segurar o mutex da geracao
std::mutex         g_model_mutex;

// --- v0.3.0: reaproveitamento de KV cache + estatisticas da ultima geracao
std::vector<llama_token> g_cached;               // tokens que JA estao no KV cache (seq 0), em ordem
bool                     g_reuse_cache = true;
std::atomic<long long>   g_stat_reused{0};       // tokens do prompt reaproveitados do cache
std::atomic<long long>   g_stat_prompt{0};       // tokens do prompt realmente processados
std::atomic<long long>   g_stat_gen{0};          // tokens gerados
std::atomic<long long>   g_stat_prompt_ms{0};
std::atomic<long long>   g_stat_gen_ms{0};

void free_model() {
    g_loaded.store(false);
    g_cached.clear();
    if (g_ctx)   { llama_free(g_ctx);        g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
}

// Codigos vindos do Kotlin (KvCacheType.code) -> tipo ggml.
ggml_type kv_type_from_code(int code) {
    switch (code) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q5_1;
        case 3:  return GGML_TYPE_Q5_0;
        case 4:  return GGML_TYPE_Q4_1;
        case 5:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

// Flash attention: no llama.cpp antigo o campo e `bool flash_attn`; no novo e o enum
// `flash_attn_type` (-1 auto, 0 desligado, 1 ligado). SFINAE escolhe o que existir na tag usada,
// sem depender do nome do enumerador.
template <class P>
auto set_flash_attn(P& p, bool on, int)
    -> decltype((void) (p.flash_attn_type = static_cast<decltype(p.flash_attn_type)>(1)), void()) {
    p.flash_attn_type = static_cast<decltype(p.flash_attn_type)>(on ? 1 : 0);
}
template <class P>
auto set_flash_attn(P& p, bool on, long) -> decltype((void) (p.flash_attn = on), void()) {
    p.flash_attn = on;
}

std::string token_to_piece(const llama_vocab* vocab, llama_token token) {
    std::string piece(64, '\0');
    int n = llama_token_to_piece(vocab, token, piece.data(), (int) piece.size(), 0, true);
    if (n < 0) {
        piece.resize((size_t) -n);
        n = llama_token_to_piece(vocab, token, piece.data(), (int) piece.size(), 0, true);
    }
    piece.resize(n < 0 ? 0 : (size_t) n);
    return piece;
}

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (!chars) return {};
    std::string out = localai::utf16_to_utf8(reinterpret_cast<const char16_t*>(chars),
                                             (size_t) env->GetStringLength(s));
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring u16_to_jstring(JNIEnv* env, const std::u16string& s) {
    return env->NewString(reinterpret_cast<const jchar*>(s.data()), (jsize) s.size());
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& s) {
    localai::Utf8Stream st;
    std::u16string u = st.push(s) + st.finish();
    return u16_to_jstring(env, u);
}

void throw_runtime(JNIEnv* env, const std::string& msg) {
    if (env->ExceptionCheck()) return;  // ja ha uma excecao pendente
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, msg.c_str()); env->DeleteLocalRef(cls); }
}

// Libera sampler e referencia global em qualquer saida (inclusive excecao C++).
struct GenGuard {
    JNIEnv* env;
    jobject callback = nullptr;
    llama_sampler* smpl = nullptr;
    ~GenGuard() {
        if (smpl) llama_sampler_free(smpl);
        if (callback) env->DeleteGlobalRef(callback);
    }
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localai_chat_llm_LlamaBridge_loadModel(
        JNIEnv* env, jobject, jstring path,
        jint nCtx, jint nThreads, jboolean flashAttn,
        jint kvTypeK, jint kvTypeV,
        jboolean useMmap, jboolean useMlock, jboolean reuseCache) {
    // Sinaliza ANTES de esperar o mutex: uma geracao em andamento precisa ver o cancelamento.
    g_cancelled.store(true);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model) free_model();

    const std::string c_path = jstring_to_utf8(env, path);
    LOGI("stage=load_model begin");

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only no Android (v1). Vulkan fica pro roadmap.
    mparams.use_mmap  = useMmap  == JNI_TRUE;   // false = le o modelo inteiro para a RAM
    mparams.use_mlock = useMlock == JNI_TRUE;   // trava as paginas na RAM (pode falhar por RLIMIT_MEMLOCK)

    g_model = llama_model_load_from_file(c_path.c_str(), mparams);
    if (!g_model) {
        LOGE("stage=load_model falhou ao carregar o arquivo");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = (uint32_t) std::max(512, (int) nCtx);
    cparams.n_batch  = kBatch;
    cparams.n_ubatch = kBatch;
    if (nThreads > 0) {
        cparams.n_threads       = (int32_t) nThreads;
        cparams.n_threads_batch = (int32_t) nThreads;
    }

    cparams.type_k = kv_type_from_code((int) kvTypeK);
    cparams.type_v = kv_type_from_code((int) kvTypeV);
    // V quantizado so funciona com flash attention no llama.cpp: liga automaticamente.
    bool use_fa = flashAttn == JNI_TRUE;
    if (cparams.type_v != GGML_TYPE_F16 && !use_fa) {
        use_fa = true;
        LOGI("stage=load_context V quantizado exige flash attention: ligando");
    }
    set_flash_attn(cparams, use_fa, 0);
    g_reuse_cache = reuseCache == JNI_TRUE;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("stage=load_context falhou (memoria insuficiente ou parametros invalidos)");
        free_model();
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    g_cached.clear();
    g_loaded.store(true);
    LOGI("stage=load_model ok n_ctx=%d n_batch=%d threads=%d fa=%d k=%s v=%s mmap=%d mlock=%d reuse=%d",
         (int) llama_n_ctx(g_ctx), kBatch, (int) nThreads, use_fa ? 1 : 0,
         ggml_type_name(cparams.type_k), ggml_type_name(cparams.type_v),
         (int) mparams.use_mmap, (int) mparams.use_mlock, g_reuse_cache ? 1 : 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_chat_llm_LlamaBridge_unloadModel(JNIEnv*, jobject) {
    // Pede cancelamento antes de esperar o lock: a geracao ativa sai antes de liberar os recursos.
    g_cancelled.store(true);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    free_model();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localai_chat_llm_LlamaBridge_isModelLoaded(JNIEnv*, jobject) {
    return g_loaded.load() ? JNI_TRUE : JNI_FALSE;
}

// "reaproveitados|prompt_novos|gerados|prompt_ms|gen_ms" da ultima geracao (sem conteudo de texto).
extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_chat_llm_LlamaBridge_lastStats(JNIEnv* env, jobject) {
    const std::string out = std::to_string(g_stat_reused.load()) + "|"
                          + std::to_string(g_stat_prompt.load()) + "|"
                          + std::to_string(g_stat_gen.load()) + "|"
                          + std::to_string(g_stat_prompt_ms.load()) + "|"
                          + std::to_string(g_stat_gen_ms.load());
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localai_chat_llm_LlamaBridge_cancelGeneration(JNIEnv*, jobject) {
    g_cancelled.store(true);
    LOGI("cancelamento solicitado");
}

// Texto do chat template embutido no GGUF ("" se nao houver).
extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_chat_llm_LlamaBridge_chatTemplate(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    const char* tmpl = g_model ? llama_model_chat_template(g_model, nullptr) : nullptr;
    return utf8_to_jstring(env, tmpl ? tmpl : "");
}

// Aplica o template embutido no GGUF via llama_chat_apply_template (so reconhece os formatos
// que o llama.cpp conhece). Retorna null se nao houver template ou se ele nao for suportado;
// o Kotlin entao escolhe um template por familia.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_chat_llm_LlamaBridge_applyChatTemplate(
        JNIEnv* env, jobject, jobjectArray jroles, jobjectArray jcontents) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (!g_model || !jroles || !jcontents) return nullptr;
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    if (n <= 0 || n != env->GetArrayLength(jcontents)) return nullptr;

    std::vector<std::string> roles((size_t) n), contents((size_t) n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[(size_t) i]    = jstring_to_utf8(env, r);
        contents[(size_t) i] = jstring_to_utf8(env, c);
        total += roles[(size_t) i].size() + contents[(size_t) i].size();
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs((size_t) n);
    for (size_t i = 0; i < (size_t) n; i++) {
        msgs[i].role    = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
    }

    try {
        std::string buf(total * 2 + 1024, '\0');
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                                buf.data(), (int32_t) buf.size());
        if (len > (int32_t) buf.size()) {
            buf.assign((size_t) len, '\0');
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), (int32_t) buf.size());
        }
        if (len < 0) {
            LOGI("stage=apply_template template embutido nao suportado (rc=%d)", (int) len);
            return nullptr;
        }
        buf.resize((size_t) len);
        return utf8_to_jstring(env, buf);
    } catch (const std::exception& e) {
        LOGE("stage=apply_template excecao C++: %s", e.what());
        return nullptr;
    }
}

// true se [text] vira exatamente 1 token quando marcadores especiais sao interpretados.
// Usado para identificar a familia de template (ex.: "<start_of_turn>", "<|im_start|>").
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localai_chat_llm_LlamaBridge_isSingleSpecialToken(JNIEnv* env, jobject, jstring jtext) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (!g_vocab) return JNI_FALSE;
    const std::string text = jstring_to_utf8(env, jtext);
    llama_token toks[4];
    const int n = llama_tokenize(g_vocab, text.c_str(), (int) text.size(), toks, 4, false, true);
    return n == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localai_chat_llm_LlamaBridge_generate(
        JNIEnv* env, jobject, jstring jprompt, jobject jcallback) {

    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (!g_model || !g_ctx || !g_vocab) {
        throw_runtime(env, "etapa=generate erro=modelo_nao_carregado");
        return nullptr;
    }
    g_cancelled.store(false);

    GenGuard guard{env};
    std::string error;                       // preenchido em qualquer falha
    std::u16string result;                   // texto final (UTF-16)
    int n_generated = 0;
    const char* stop_reason = "desconhecido";

    try {
        guard.callback = env->NewGlobalRef(jcallback);
        if (!guard.callback) {
            throw_runtime(env, "etapa=callback erro=NewGlobalRef_falhou");
            return nullptr;
        }
        jclass callbackClass = env->GetObjectClass(guard.callback);
        jmethodID onToken = callbackClass
            ? env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V") : nullptr;
        if (callbackClass) env->DeleteLocalRef(callbackClass);
        if (!onToken) {
            env->ExceptionClear();
            throw_runtime(env, "etapa=callback erro=metodo_onToken_nao_encontrado");
            return nullptr;
        }

        localai::Utf8Stream utf8;
        auto emit_u16 = [&](const std::u16string& u) {
            if (u.empty()) return;
            result += u;
            if (g_cancelled.load()) return;
            jstring s = u16_to_jstring(env, u);
            if (!s) { g_cancelled.store(true); return; }          // OutOfMemoryError pendente
            env->CallVoidMethod(guard.callback, onToken, s);
            env->DeleteLocalRef(s);
            if (env->ExceptionCheck()) {                          // excecao Kotlin no callback
                env->ExceptionClear();
                g_cancelled.store(true);
            }
        };

        const int n_ctx = (int) llama_n_ctx(g_ctx);
        const std::string prompt = jstring_to_utf8(env, jprompt);

        // O template ja inclui o BOS quando precisa; evita BOS duplicado.
        const bool has_leading_bos = prompt.rfind("<bos>", 0) == 0
                                  || prompt.rfind("<s>", 0) == 0
                                  || prompt.rfind("<|begin_of_text|>", 0) == 0;

        // --- tokenizacao (parse_special=true: marcadores do template viram tokens de controle)
        std::vector<llama_token> tokens(prompt.size() + 8);
        int n_tokens = llama_tokenize(g_vocab, prompt.c_str(), (int) prompt.size(),
                                      tokens.data(), (int) tokens.size(),
                                      /*add_special=*/!has_leading_bos, /*parse_special=*/true);
        if (n_tokens < 0) {   // buffer pequeno: o retorno negativo e -(tamanho necessario)
            tokens.resize((size_t) -n_tokens);
            n_tokens = llama_tokenize(g_vocab, prompt.c_str(), (int) prompt.size(),
                                      tokens.data(), (int) tokens.size(),
                                      !has_leading_bos, true);
        }
        if (n_tokens <= 0) {
            error = "etapa=tokenize codigo=" + std::to_string(n_tokens);
            throw_runtime(env, error);
            LOGE("%s", error.c_str());
            return nullptr;
        }
        tokens.resize((size_t) n_tokens);
        LOGI("stage=tokenize ok n_tokens=%d n_ctx=%d prompt_bytes=%zu", n_tokens, n_ctx, prompt.size());

        if (n_tokens + kMinGenRoom > n_ctx) {
            error = "etapa=prompt erro=prompt_muito_longo n_tokens=" + std::to_string(n_tokens)
                  + " n_ctx=" + std::to_string(n_ctx);
            throw_runtime(env, error);
            LOGE("%s", error.c_str());
            return nullptr;
        }

        // Reaproveita o KV cache: mantem so o prefixo de tokens igual ao que ja esta no contexto
        // (o historico e reenviado a cada pedido, entao normalmente quase tudo bate). Se o modelo
        // nao suporta remocao parcial (ex.: SWA/recorrente), cai para limpeza total.
        auto mem = llama_get_memory(g_ctx);
        int n_past = 0;
        if (g_reuse_cache && !g_cached.empty()) {
            const int limit = (int) std::min<size_t>(g_cached.size(), (size_t) n_tokens);
            while (n_past < limit && g_cached[(size_t) n_past] == tokens[(size_t) n_past]) n_past++;
            if (n_past >= n_tokens) n_past = n_tokens - 1;   // re-decodifica o ultimo token: precisa de logits
        }
        if (n_past > 0 && llama_memory_seq_rm(mem, 0, (llama_pos) n_past, -1)) {
            g_cached.resize((size_t) n_past);
        } else {
            n_past = 0;
            llama_memory_clear(mem, true);
            g_cached.clear();
        }
        LOGI("stage=cache reaproveitados=%d novos=%d", n_past, n_tokens - n_past);

        guard.smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(guard.smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(guard.smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(guard.smpl, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(guard.smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // --- decodifica so o trecho NOVO do prompt, em blocos (respeitando cancelamento)
        const auto t_prompt0 = std::chrono::steady_clock::now();
        for (int i = n_past; i < n_tokens && !g_cancelled.load(); i += kBatch) {
            const int chunk = std::min(kBatch, n_tokens - i);
            llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
            const int rc = llama_decode(g_ctx, batch);
            if (rc != 0) {
                error = "etapa=prompt_decode codigo=" + std::to_string(rc)
                      + " offset=" + std::to_string(i) + " n_tokens=" + std::to_string(n_tokens);
                g_cached.clear();
                llama_memory_clear(mem, true);
                break;
            }
            g_cached.insert(g_cached.end(), tokens.begin() + i, tokens.begin() + i + chunk);
        }
        const auto t_gen0 = std::chrono::steady_clock::now();
        if (error.empty()) LOGI("stage=prompt_decode ok%s", g_cancelled.load() ? " (cancelado)" : "");

        // --- laco de geracao
        stop_reason = "cancelado";
        llama_token new_token = 0;
        while (error.empty() && !g_cancelled.load()) {
            if ((int) g_cached.size() >= n_ctx) { stop_reason = "contexto_cheio"; break; }

            new_token = llama_sampler_sample(guard.smpl, g_ctx, -1);
            if (llama_vocab_is_eog(g_vocab, new_token)) {         // <end_of_turn>, <|im_end|>, EOS...
                stop_reason = "eog";
                break;
            }
            llama_sampler_accept(guard.smpl, new_token);

            const std::string piece = token_to_piece(g_vocab, new_token);
            emit_u16(utf8.push(piece));
            n_generated++;

            llama_batch batch = llama_batch_get_one(&new_token, 1);
            const int rc = llama_decode(g_ctx, batch);
            if (rc != 0) {
                error = "etapa=decode codigo=" + std::to_string(rc)
                      + " n_gerados=" + std::to_string(n_generated);
                g_cached.clear();
                llama_memory_clear(mem, true);
                break;
            }
            g_cached.push_back(new_token);   // agora esta no KV cache
        }
        emit_u16(utf8.finish());   // bytes de UTF-8 truncado no fim viram U+FFFD

        {
            using ms = std::chrono::milliseconds;
            const auto t_end = std::chrono::steady_clock::now();
            g_stat_reused.store(n_past);
            g_stat_prompt.store(n_tokens - n_past);
            g_stat_gen.store(n_generated);
            g_stat_prompt_ms.store(std::chrono::duration_cast<ms>(t_gen0 - t_prompt0).count());
            g_stat_gen_ms.store(std::chrono::duration_cast<ms>(t_end - t_gen0).count());
        }
        LOGI("stage=done n_gerados=%d parada=%s prompt_ms=%lld gen_ms=%lld",
             n_generated, error.empty() ? stop_reason : "erro",
             (long long) g_stat_prompt_ms.load(), (long long) g_stat_gen_ms.load());
        if (!error.empty()) {
            LOGE("%s", error.c_str());
            throw_runtime(env, error);
            return nullptr;
        }
        return u16_to_jstring(env, result);

    } catch (const std::exception& e) {
        error = std::string("etapa=generate excecao_cpp=") + e.what();
    } catch (...) {
        error = "etapa=generate excecao_cpp=desconhecida";
    }
    LOGE("%s", error.c_str());
    throw_runtime(env, error);
    return nullptr;
}
