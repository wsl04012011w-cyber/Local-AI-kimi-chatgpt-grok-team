# Local AI v0.2.1 — correções Qwen (crash) e Gemma (resposta contaminada)

> **Leia primeiro:** nada aqui foi compilado com Gradle/NDK nem executado em aparelho. O ambiente
> onde isto foi feito não tinha Kotlin, Gradle, NDK, CMake, rede para clonar o llama.cpp nem acesso
> de escrita ao GitHub. O que foi realmente executado está na seção 5.

## 1. Causa do crash do Qwen — NÃO confirmada

Não havia logcat/tombstone, então não há evidência para declarar uma causa. Candidatos, por força de evidência no código:

| # | Candidato | Onde (v0.2.0) | Evidência | Status |
|---|-----------|---------------|-----------|--------|
| A | Pedaços de token (bytes crus) passados a `NewStringUTF`: UTF-8 truncado entre tokens e emoji (4 bytes) não são "Modified UTF-8". Aborta a VM com CheckJNI (apps debuggable) ou gera texto corrompido. O Qwen usa BPE por bytes, então divide caracteres multibyte (CJK/emoji) entre tokens. | `llama-jni.cpp` L125 e L202 | Leitura de código; mecanismo conhecido da JNI | **Hipótese forte, corrigida** |
| B | Prompt vindo de `GetStringUTFChars` (CESU-8 para emoji) entregue ao tokenizer. | L119-120 | Leitura de código | Defeito real, corrigido |
| C | `llama_kv_cache_seq_rm(ctx, -1, …)`: API removida/renomeada para `llama_memory_*` (deprecação em #14030). Há issue upstream (#15104) relatando `GGML_ASSERT` com `seq_id < 0` em `llama_memory_seq_rm` em algumas versões. | L152 | Busca web; **não verifiquei o `llama.h` da v0.3.0** | Risco de compilação/abort; substituído por `llama_memory_clear` |
| D | Memória: `n_batch=2048` com vocabulário de ~152k (Qwen) infla o buffer de logits do grafo. | L70-71 | Raciocínio, sem medição | Mitigado (`n_batch=n_ubatch=512`), não confirmado |

Observação: o ZIP enviado **não compila** (import com `\n` literal em `ChatViewModel.kt`, linha 18) e usa `llama_kv_cache_seq_rm`, que provavelmente não existe na tag v0.3.0 (tags `v0.x` são de 2026). Então o APK que travou pode ter vindo de outra revisão. **Preciso saber qual commit/ZIP gerou o APK testado.**

Instrumentação adicionada (sem conteúdo de conversa): logs `stage=...` com contagens e códigos de retorno, e `RuntimeException("etapa=… codigo=…")` na fronteira JNI, em vez de string vazia. Para confirmar a causa:

    adb logcat -s LocalAI-JNI LocalAI-Engine AndroidRuntime DEBUG libc

Se travar de novo, as últimas linhas `stage=` antes do crash indicam a etapa (tokenize, prompt_decode, decode…).

## 2. Causa da resposta incoerente do Gemma — confirmada por leitura de código (não executada em aparelho)

1. **ChatML fixo para todos os modelos** (`ChatViewModel.kt`: constantes de template e `buildPrompt`). O Gemma usa `<start_of_turn>user … <end_of_turn> <start_of_turn>model`.
2. **Prompt tokenizado com `parse_special=false`**: `<|im_start|>` virava texto literal.
3. **Parada só em `llama_vocab_eos`**. O Gemma termina o turno com `<end_of_turn>` (EOG, mas não EOS): o laço continuava e o modelo inventava turnos "user/assistant". O "gerando…" no fim do seu exemplo é o rótulo de streaming da UI, ou seja, a geração ainda corria.
4. **Realimentação**: o texto inventado era salvo como resposta e reenviado no turno seguinte; piora a cada mensagem.
5. Os regex de `stripSpecials` removiam "assistant\n"/"user\n" até de respostas legítimas (proibido pelo seu pedido) e só mascaravam parte do problema.

## 3. O que mudou (arquivos)

| Arquivo | Mudança |
|---|---|
| `cpp/llama-jni.cpp` | Reescrito: UTF-16 na fronteira JNI; parada por EOG; `parse_special=true`; sem BOS duplicado; `llama_memory_clear`; `llama_model_load_from_file`/`llama_model_free`; checagem de tamanho do prompt; exceções com etapa/código; guard RAII (sampler e global ref); try/catch C++; `isModelLoaded` sem lock; cancelamento sinalizado antes do lock em `loadModel`; novos JNI `chatTemplate`, `applyChatTemplate`, `isSingleSpecialToken`; `n_batch=512` |
| `cpp/utf8_stream.h` (novo) | Conversor incremental UTF-8→UTF-16 e UTF-16→UTF-8 (testável no host) |
| `llm/ChatTemplates.kt` (novo) | Templates Gemma e ChatML + detecção de família |
| `llm/PromptHistory.kt` (novo) | Seleção do histórico (puro, sem Android) |
| `llm/InferenceEngine.kt` | `buildPrompt` (GGUF → família); buffer ilimitado no Flow (o padrão de 64 descartaria tokens); removido `cancelGeneration` stale no `awaitClose`; `LinkageError` vira erro tratável |
| `llm/LlamaBridge.kt` | Declarações dos novos métodos nativos |
| `llm/TokenStreamProcessor.kt` | Fim de turno estrutural (`<start_of_turn>`, `<|im_start|>`…); mais marcadores; **removidos** os regex de "user"/"assistant" |
| `ui/chat/ChatViewModel.kt` | Import corrigido; sem ChatML fixo; histórico capturado antes de acrescentar a mensagem; `appendLocal` idempotente; "gerando" marcado de forma síncrona; `stop()` cobre a fase de preparo; `onCleared` cancela; `trim()` no texto final |
| `app/build.gradle.kts` | JUnit; versão 0.2.1 |
| `app/src/test/...` | Testes Kotlin (3 arquivos) e teste C++ de host |

Ordem do template: (1) embutido no GGUF via `llama_chat_apply_template` (só reconhece os formatos que o llama.cpp conhece); (2) família detectada (Gemma/ChatML); (3) ChatML como último recurso. O histórico guarda só a resposta visível (sem thinking), e a UI continua separada do histórico interno.

## 4. API do llama.cpp — o que foi e o que não foi verificado

Confirmado em exemplos/fontes públicos do llama.cpp: `llama_model_load_from_file`, `llama_model_free`, `llama_vocab_is_eog`, `llama_model_chat_template(model, nullptr)` (retorna `const char*`), `llama_chat_apply_template(tmpl, msgs, n, add_ass, buf, len)`, `llama_memory_clear(llama_get_memory(ctx), true)`.
**Não verificado contra o `include/llama.h` da v0.3.0** (não consegui baixá-lo): essas assinaturas na tag exata, e `llama_tokenize`/`llama_token_to_piece` com `vocab` (já usados pelo código antigo). O primeiro build no Actions é o teste real; se falhar, o erro apontará a linha.

## 5. Testes — o que foi executado de fato

| Teste | Resultado |
|---|---|
| `utf8_stream_test.cpp` (g++ no host, 20+ asserts: UTF-8 dividido, emoji, inválidos, truncado, 200 KB, NUL) | **Passou** |
| `llama-jni.cpp` com `g++ -fsyntax-only` contra **stubs escritos à mão** de `jni.h`/`llama.h` | Passou — só prova consistência interna, **não** compatibilidade com o `llama.h` real |
| Porta em Python da lógica Kotlin (processador, templates, histórico) com as mesmas expectativas dos testes | Passou — **não** prova que o Kotlin compila |
| Testes Kotlin JUnit (`TokenStreamProcessorTest`, `ChatTemplatesTest`, `PromptHistoryTest`) | **Escritos, NÃO executados** (sem kotlinc/Gradle) |
| Build Gradle/NDK/APK | **Não feito** |

Cobertura dos cenários pedidos nos testes Kotlin: Qwen — sem thinking, com thinking, fragmentação de marcadores, tokens de fim de turno, cancelamento no meio do thinking, conteúdo longo; Gemma — 1ª mensagem, 2ª com histórico, 3+ turnos sem duplicação, "user"/"assistant" legítimos preservados, rótulos não exibidos, histórico não duplicado.

**Exigem aparelho/modelo real (não cobertos):** geração real do Qwen com thinking; término normal e por cancelamento; nova geração após cancelar; prompt longo no contexto de 4096; Gemma em 3+ turnos reais; mensagens com emoji/CJK; consumo de memória.

## 6. Problemas conhecidos / pendências

- Causa do crash do Qwen não confirmada (seção 1).
- `</think>` sem `<think>` de abertura (modelos cujo template já abre o thinking) vaza para o texto visível.
- Tokens de canal (gpt-oss/harmony: `<|channel|>`) não são tratados.
- Se o GGUF não marcar `<end_of_turn>`/`<|im_end|>` como EOG (conversões antigas), a proteção é o fim de turno estrutural no processador, que não para o laço nativo instantaneamente.
- O histórico salvo no Room **ainda contém as respostas contaminadas do Gemma**: use "Limpar conversa" uma vez após instalar.
- O histórico é limitado a 12 turnos/8000 caracteres (aproximação; não conta tokens). Prompt maior que o contexto gera erro explícito `prompt_muito_longo`.
- Cada mensagem reprocessa o histórico inteiro (KV cache zerado): correto, porém lento em conversas longas.
- O workflow (`.github/workflows`) não pôde ser lido (GitHub bloqueou acesso automatizado) e **não foi alterado**.

## 7. Como gerar e instalar o APK

1. Crie uma branch (ex.: `fix/qwen-gemma`) e envie o ZIP corrigido com o **nome exato** `local-ai-v0.1.zip` na raiz (substitui o atual). Confira no workflow se ele roda em branch ou só em `main`; se só em `main`, use **Run workflow** (se houver `workflow_dispatch`) ou faça o push em `main` ciente de que isso sobrescreve o ZIP.
2. Aba **Actions** → execução mais recente → aguarde. **Se falhar na compilação nativa, mande o log**: é o primeiro teste real contra o `llama.h` da v0.3.0.
3. Baixe o artefato `local-ai-release-apk`, instale no celular (permitir fontes desconhecidas).
4. Abra o app → "Limpar conversa" → carregue o modelo → teste.
5. Se travar, rode o `adb logcat` da seção 1 e envie as linhas.

Sugestão de CI (não aplicada): antes do build, rodar
`g++ -std=c++17 -Wall -Wextra -I app/src/main/cpp app/src/test/cpp/utf8_stream_test.cpp -o /tmp/t && /tmp/t` e `./gradlew :app:testDebugUnitTest`.
