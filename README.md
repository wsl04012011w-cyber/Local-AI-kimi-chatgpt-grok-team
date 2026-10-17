# Local AI — chat offline para Android

O Local AI e um chat de IA que executa inferencia no aparelho usando llama.cpp e modelos GGUF locais. A interface foi migrada de WebView para React Native com TypeScript; o motor de inferencia continua nativo em Kotlin/JNI.

## Stack e versoes

| Camada | Tecnologia |
|---|---|
| Interface | React Native 0.87.1 + TypeScript 6.0.3 |
| React | 19.2.3 |
| Runtime JS | Hermes |
| Integração Android | Kotlin + Native Module React Native |
| Motor de inferencia | llama.cpp v0.3.0 via JNI/C++ |
| Persistencia | Room + SharedPreferences |
| Injeção de dependencias | Hilt |
| JDK | 17 |
| Gradle | 9.4.1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.2.10 |
| Android SDK | compileSdk 37 / targetSdk 36 |
| NDK | 27.1.12297006 |
| CMake | 3.22.1 |

As principais dependencias JavaScript estao fixadas em versoes exatas no `package.json`. O build de CI usa Node.js 22, compativel com os requisitos de React Native 0.87.

## Arquitetura

- `App.tsx`: chat React Native com mensagens, estado de streaming, painel de raciocinio, seletor GGUF e painel de desempenho.
- `LocalAiReactModule.kt`: bridge tipada entre TypeScript e o ViewModel Kotlin.
- `ChatViewModel.kt`: segue responsavel pela geração, historico, cancelamento, importacao do modelo e estado da inferencia.
- Room e SharedPreferences mantem o historico de conversa e as configuracoes.
- `app/src/main/cpp/llama-jni.cpp`: ponte JNI para o motor llama.cpp.
- `app/src/main/cpp/CMakeLists.txt`: integra os alvos C++ do React Native e a biblioteca JNI existente.
- `app/src/main/assets/index.html`: interface antiga em WebView, mantida temporariamente como referencia, mas nao e carregada pela Activity principal.

A interface e executada em views nativas via React Native; nao usa WebView. O uso de RAM depende tanto do Hermes/UI como do modelo GGUF e dos parametros de inferencia, portanto a migracao nao garante sozinha reducao de memoria.

## Desenvolvimento local

Requisitos: Node.js >= 22.13, JDK 17, Android SDK platform 37, NDK 27.1.12297006 e CMake 3.22.1.

1. Instale as dependencias JavaScript: `npm install`.
2. Baixe a versao fixada do llama.cpp:
   `git clone --depth 1 --branch v0.3.0 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp`
3. Inicie o Metro em um terminal: `npm start`.
4. Compile em outro terminal: `gradle :app:assembleDebug` ou `gradle :app:assembleRelease`.

O build de release empacota o bundle JavaScript; nao depende do Metro em execucao. A permissao INTERNET para conectar ao Metro fica no manifest de debug; o manifest principal de release nao pede acesso a internet.

## Build no GitHub Actions

O workflow `.github/workflows/build-android.yml` configura Node/JDK/Gradle/Android SDK, instala dependencias, baixa a tag v0.3.0 do llama.cpp, verifica os tipos TypeScript, compila e assina o APK de release e publica o artefato `local-ai-release-signed-apk`.

## Usar o app

1. Baixe um modelo GGUF compativel com o aparelho.
2. Abra o Local AI e toque em **Abrir GGUF**.
3. Depois de carregar, envie mensagens. A interface recebe as atualizacoes conforme os tokens chegam e permite parar a geracao.
4. Em **Desempenho**, ajuste o contexto, threads, KV cache, flash attention, mmap, mlock e reutilizacao de cache. Mudar os parametros recarrega o modelo.

## Notas

- Modelos grandes podem precisar de bastante RAM; comece com um GGUF pequeno e quantizado.
- Velocidade e consumo dependem do modelo e do aparelho.
- O APK nao inclui um modelo GGUF; o usuario seleciona o arquivo no dispositivo.
- O workflow atual usa uma chave de assinatura efemera por build. Para lancamentos com atualizacao in-place, configure uma chave de release persistente e protegida.
