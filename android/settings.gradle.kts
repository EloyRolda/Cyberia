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

rootProject.name = "Cyberia"

// La lógica de sincronización vive en un build aparte, en Kotlin puro (JVM): se puede compilar y
// probar sin el SDK de Android con `./gradlew -p core test`.
includeBuild("core")
include(":app")
