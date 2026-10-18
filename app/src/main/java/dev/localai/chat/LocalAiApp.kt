package dev.localai.chat

import android.app.Application
import android.content.Context
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LocalAiApp : Application(), ReactApplication {
    override val reactHost: ReactHost by lazy {
        getDefaultReactHost(
            context = applicationContext,
            packageList = PackageList(this).packages.apply {
                add(LocalAiReactPackage())
            },
        )
    }

    override fun attachBaseContext(base: Context) {
        // Do not touch applicationContext/filesDir before Application is attached.
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize diagnostics only after the Application has a valid base context,
        // but before React Native startup so fatal startup exceptions are persisted.
        try {
            AppLogger.initialize(this)
            AppLogger.info("Application", "Inicializando React Native")
            loadReactNative(this)
            AppLogger.info("Application", "React Native inicializado")
        } catch (error: Throwable) {
            AppLogger.error("Application", "Falha fatal ao inicializar React Native", error)
            throw error
        }
    }
}
