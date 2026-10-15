# Local AI 🤖📱

Chat de IA **100% offline** no Android, com inferência local via [llama.cpp](https://github.com/ggml-org/llama.cpp).
Sem permissão de INTERNET — seus dados nunca saem do aparelho.

## Stack

| Camada | Tecnologia |
|---|---|
| UI | Jetpack Compose + Material 3 (tema dark próprio) |
| Arquitetura | MVVM + Clean Architecture + Hilt (DI) |
| Persistência | Room (histórico do chat) |
| Inferência | llama.cpp via JNI (CPU, streaming de tokens) |
| CI | GitHub Actions (NDK + CMake + APK) |

## Estrutura

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        # build nativo: ggml + llama + JNI
│   └── llama-jni.cpp         # ponte JNI (load/generate/cancel)
├── java/dev/localai/chat/
│   ├── MainActivity.kt
│   ├── llm/                  # LlamaBridge, InferenceEngine, TokenStreamProcessor
│   ├── data/                 # Room: entities, dao, repository
│   ├── di/                   # módulo Hilt
│   └── ui/
│       ├── theme/            # Material 3 (dynamic color)
│       └── chat/             # ChatScreen, ViewModel, componentes
└── res/
```

O llama.cpp **não fica no repositório**: o workflow do GitHub Actions clona a tag
pinada (`v0.3.0`) em `app/src/main/cpp/llama.cpp` antes do build (ver `.github/workflows/build.yml`).

## Build local

Requisitos: JDK 17, Android Studio com SDK 35 + NDK 27.

```bash
# 1. baixar as fontes do llama.cpp na versão pinada
git clone --depth 1 --branch v0.3.0 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

# 2. abrir no Android Studio e rodar, ou:
./gradlew :app:assembleDebug
```

## Build no GitHub (CI)

Todo push em `main` roda o workflow:
1. Instala JDK 17 + Android SDK + NDK 27 + CMake
2. Clona o llama.cpp (tag pinada, com fallback pra master)
3. Compila as libs nativas (arm64-v8a, x86_64) via CMake
4. Gera o APK de release e sobe como artifact

## Como usar

1. Baixe um modelo **.gguf** pequeno (ex.: Qwen2.5-0.5B-Instruct, TinyLlama) no aparelho
2. Abra o app → toque no chip "Sem modelo" → selecione o .gguf
3. Espere carregar e converse. Streaming de tokens em tempo real, botão de parar.

## Changelog v0.3.0

1. **Scroll automático corrigido** — antes, `animateScrollToItem(lastIndex)` alinhava o *topo* da última bolha ao topo da tela (o chat "subia" em respostas longas). Agora há um item-âncora no fim da lista e o app segue o final real da resposta. O acompanhamento só é interrompido quando o usuário rola para cima; volta ao chegar no fim, ao tocar no botão ↓ ou ao enviar mensagem.
2. **Visual dark profissional** — paleta graphite + acento índigo, tema sempre escuro (sem dynamic color), assistente em texto corrido com avatar, bolha do usuário em gradiente, input em "pílula", chip de status com indicador de cor, estado vazio com sugestões, blocos de código, **negrito**, `código inline`, listas e títulos.
3. **Desempenho do modelo** (ícone ⚙ no topo): flash attention, KV cache K/V em F16/Q8_0/Q5_1/Q5_0/Q4_1/Q4_0, contexto (2048/4096/8192), threads, mmap, mlock e reaproveitamento do KV cache entre mensagens (só o trecho novo do prompt é processado). V quantizado liga a flash attention automaticamente (exigência do llama.cpp). Barra de status mostra ctx, FA, KV, threads, tokens reaproveitados e tok/s.
4. **Caixa de raciocínio persistente** — o raciocínio agora é salvo no banco (coluna `thinking`); a caixa aparece expandida enquanto o modelo pensa e fica minimizada no chat depois, com toque para expandir.
5. **Design geral** — novo tema, tipografia, formas e componentes (ver item 2).

Outras correções: o modelo é descarregado antes de copiar um novo arquivo por cima (com mmap, sobrescrever o arquivo mapeado pode derrubar o app); o chip mostra o nome original do arquivo .gguf.

> **Atenção:** o banco subiu para a versão 2 e usa `fallbackToDestructiveMigration`, então o histórico de conversas existente é apagado na primeira abertura.

## Changelog v0.2.0

### Correções e melhorias

1. **Edge-to-edge / WindowInsets**
   - Tratamento correto de status bar, navigation bar e teclado
   - TopAppBar e input bar não ficam mais escondidos atrás das system bars

2. **Filtro de tokens especiais**
   - `TokenStreamProcessor` remove marcadores internos (`<|im_start|>`, `<|im_end|>`, etc.)
   - Processamento incremental (não espera a resposta completa)
   - Lida com marcadores fragmentados entre chunks

3. **Detecção de thinking**
   - Reconhece `<think>`, `<thinking>` e variantes
   - Separa conteúdo de raciocínio da resposta final

4. **Faixa visual de thinking**
   - Painel compacto com indicador "Raciocinando…"
   - Expandir/recolher o conteúdo de thinking
   - Não cria painel vazio quando o modelo não usa thinking

5. **Scroll automático inteligente**
   - Acompanha a resposta só se o usuário estiver no final
   - Respeita quando o usuário rola para ler mensagens antigas
   - FAB discreto para voltar ao final

6. **Cancelamento confiável**
   - Flag atômica no C++ + `awaitClose` no Flow
   - Interrompe o loop nativo de verdade
   - Preserva texto parcial e permite nova mensagem logo em seguida

## Changelog v0.2.1 (Qwen/Gemma) — detalhes em CORRECOES.md

- Template de conversa por modelo (embutido no GGUF; fallback Gemma/ChatML). Fim do ChatML fixo.
- Geracao para no fim de turno (`llama_vocab_is_eog`), nao so no EOS (causa da resposta "contaminada" do Gemma).
- Prompt tokenizado com marcadores especiais interpretados (`parse_special=true`).
- JNI: strings via UTF-16 (token com UTF-8 incompleto/emoji nao passa mais por `NewStringUTF`).
- Erros nativos viram excecao com etapa + codigo (logcat tags `LocalAI-JNI`, `LocalAI-Engine`).
- Removida a limpeza por regex de "user"/"assistant" na saida.
- Corrigido import quebrado em `ChatViewModel.kt` (`\n` literal).

## Notas de robustez

- **Cancelamento cooperativo**: cancelar o Flow de geração sinaliza o loop nativo e persiste o texto parcial
- **Threading**: inferência sempre em `Dispatchers.IO`; UI nunca bloqueada
- **JNI defensivo**: checagem de null em modelo/contexto, tratamento de falha de tokenização, flag atômica
- **ProGuard**: métodos nativos preservados (`-keepclassmembers ... native <methods>`)

## Roadmap

- [x] Templates de chat por modelo (GGUF embutido → Gemma/ChatML; v0.2.1). Falta: Llama-3, Mistral…
- [ ] Download de modelos via Hugging Face dentro do app
- [ ] Backend Vulkan (GPU) no llama.cpp
- [ ] Multi-conversa + renomear/apagar chats
- [x] Flash attention, KV cache quantizado, mmap/mlock, reuso de cache (v0.3.0)
- [ ] Ajuste de temperatura/top-k/top-p na UI
- [ ] Exportar conversa
