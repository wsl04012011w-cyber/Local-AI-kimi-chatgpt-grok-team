package dev.localai.chat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.localai.chat.ui.theme.LocalAITheme
import dev.localai.chat.ui.web.LocalAiWebScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.info("MainActivity", "onCreate iniciado")
        try {
            super.onCreate(savedInstanceState)
            window.statusBarColor = Color.rgb(9, 10, 12)
            window.navigationBarColor = Color.rgb(9, 10, 12)
            setContent {
                LocalAITheme {
                    LocalAiWebScreen()
                }
            }
            AppLogger.info("MainActivity", "Compose content configurado")
        } catch (error: Throwable) {
            AppLogger.error("MainActivity", "Falha ao inicializar a Activity/UI", error)
            throw error
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.info("MainActivity", "onResume")
    }
}
