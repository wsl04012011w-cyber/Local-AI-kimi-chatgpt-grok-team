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
    // Call the already-installed CLI entry point directly. This avoids npx
    // package resolution differences inside Gradle's settings process.
    autolinkLibrariesFromCommand(
        listOf("node", "node_modules/@react-native-community/cli/build/bin.js", "config")
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
