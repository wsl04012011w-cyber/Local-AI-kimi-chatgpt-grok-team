pluginManagement {
    includeBuild("node_modules/@react-native/gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("com.facebook.react.settings")
}
extensions.configure<com.facebook.react.ReactSettingsExtension> {
    // This repository keeps settings.gradle.kts at the JavaScript project root,
    // unlike the standard React Native layout where it lives in ./android.
    // Explicitly run the CLI from this settings directory so relative paths to
    // node_modules resolve correctly in both CI and local builds.
    autolinkLibrariesFromCommand(
        listOf("node", "node_modules/react-native/cli.js", "config"),
        workingDirectory = settingsDir,
        lockFiles = files("package-lock.json")
    )
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "local-ai"
include(":app")
includeBuild("node_modules/@react-native/gradle-plugin")
