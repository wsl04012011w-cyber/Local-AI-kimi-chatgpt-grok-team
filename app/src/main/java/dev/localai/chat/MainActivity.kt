package dev.localai.chat

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import dagger.hilt.android.AndroidEntryPoint
import dev.localai.chat.ui.chat.ChatViewModel

@AndroidEntryPoint
class MainActivity : ReactActivity() {
    override fun getMainComponentName(): String = "LocalAI"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.info("MainActivity", "onCreate React Native")
        try {
            window.statusBarColor = Color.rgb(9, 11, 16)
            window.navigationBarColor = Color.rgb(9, 11, 16)
            super.onCreate(savedInstanceState)
            AppLogger.info("MainActivity", "React Native Activity criada")
        } catch (error: Throwable) {
            AppLogger.error("MainActivity", "Falha ao inicializar React Native", error)
            throw error
        }
    }

    @Deprecated("Required for the native document picker bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LocalAiReactModule.MODEL_PICKER_REQUEST_CODE &&
            resultCode == RESULT_OK
        ) {
            val uri: Uri = data?.data ?: return
            try {
                ViewModelProvider(this)[ChatViewModel::class.java].importModel(uri)
            } catch (error: Throwable) {
                AppLogger.error("MainActivity", "Falha ao receber o arquivo GGUF", error)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.info("MainActivity", "onResume")
    }
}
