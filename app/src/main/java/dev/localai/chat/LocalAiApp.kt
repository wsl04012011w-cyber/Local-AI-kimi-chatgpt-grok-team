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
        AppLogger.initialize(base)
        AppLogger.info("Application", "attachBaseContext iniciado")
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.info("Application", "Inicializando React Native")
        loadReactNative(this)
        AppLogger.info("Application", "React Native inicializado")
    }
}
