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
    // Use the React Native package's CLI shim, which delegates to the pinned
    // @react-native-community/cli installed by npm.
    autolinkLibrariesFromCommand(
        listOf("node", "node_modules/react-native/cli.js", "config")
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
