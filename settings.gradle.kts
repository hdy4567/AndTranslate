pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndTranslate"
include(":apps:android")
include(":libs:audio-capture")
include(":libs:stt-engine")
include(":libs:subtitle-renderer")
include(":libs:translation")
include(":libs:privacy")
