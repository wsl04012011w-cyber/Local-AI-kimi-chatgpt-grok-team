# Local AI — CI de APK

Este repositório mantém o workflow de compilação separado do ZIP do projeto Android.

## Enviar o projeto

1. Baixe o arquivo revisado `local-ai-v0.1-reviewed.zip` fornecido nesta conversa.
2. Envie-o para a raiz deste repositório com o nome exato `local-ai-v0.1.zip`.
3. O push desse arquivo inicia automaticamente o workflow **Build Android APK**.
4. Abra a aba **Actions**, selecione a execução mais recente e aguarde o resultado.
5. Se a compilação passar, baixe o artefato `local-ai-release-apk` no fim da execução.

Também é possível iniciar o workflow manualmente pela aba **Actions**. O ZIP precisa estar na raiz.

## Observações

- O workflow baixa a tag fixa `v0.3.0` do llama.cpp; não há fallback silencioso para a branch principal.
- O APK é um artefato de CI, não uma garantia de que a inferência já foi testada em um aparelho físico.
- O projeto inicial ainda precisa de validação real de compilação e execução; os logs do Actions serão a próxima etapa da auditoria.
