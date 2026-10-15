# Metodos nativos da ponte JNI nao podem ser ofuscados/renomeados
-keepclassmembers class dev.localai.chat.llm.LlamaBridge {
    native <methods>;
}

# Compose / Material defaults
-dontwarn androidx.compose.**
-dontwarn com.google.android.material.**
