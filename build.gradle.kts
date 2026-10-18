plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("com.facebook.react.rootproject")
}

/*
 * React Native native modules consume these values through rootProject.ext.
 * Keeping them aligned with :app avoids library modules silently falling back
 * to compileSdk 30/minSdk 16 when the app uses newer Android APIs.
 */
extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 36
extra["minSdkVersion"] = 26
extra["kotlinVersion"] = "2.2.10"
extra["ndkVersion"] = "27.1.12297006"
