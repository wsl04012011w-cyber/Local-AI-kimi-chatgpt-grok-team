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
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 10, 12)
        window.navigationBarColor = Color.rgb(9, 10, 12)
        setContent {
            LocalAITheme {
                LocalAiWebScreen()
            }
        }
    }
}
