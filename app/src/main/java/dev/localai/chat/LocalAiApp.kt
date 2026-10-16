package dev.localai.chat

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LocalAiApp : Application() {
    override fun attachBaseContext(base: Context) {
        // Instala antes do onCreate/Compose/Hilt para capturar falhas de inicialização.
        AppLogger.initialize(base)
        AppLogger.info("Application", "attachBaseContext iniciado")
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.info("Application", "onCreate concluído")
    }
}
