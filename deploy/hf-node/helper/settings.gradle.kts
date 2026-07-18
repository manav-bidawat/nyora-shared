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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "nyora-helper"

// The helper is the JVM :shared module only — the kotatsu-parsers engine +
// NyoraRestServer. Its source is cloned into ./nyora-shared at build time.
include(":shared")
