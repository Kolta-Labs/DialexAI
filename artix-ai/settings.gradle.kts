rootProject.name = "Artix"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

val koltPath = when {
    File(rootDir.parentFile?.parentFile, "KoltLibs").exists() -> "../../KoltLibs"
    File(rootDir.parentFile?.parentFile, "Kolt").exists() -> "../../Kolt"
    File(rootDir.parentFile, "KoltLibs").exists() -> "../KoltLibs"
    File(rootDir.parentFile, "Kolt").exists() -> "../Kolt"
    else -> "../../KoltLibs"
}

includeBuild(koltPath) {
    dependencySubstitution {
        substitute(module("io.github.koltalabs.kolt:utils")).using(project(":libs:utils"))
        substitute(module("io.github.koltalabs.kolt:logutils")).using(project(":libs:logutils"))
        substitute(module("io.github.koltalabs.kolt:compose-kmp")).using(project(":libs:compose-kmp"))
        substitute(module("io.github.koltalabs.kolt:kolt-bom")).using(project(":libs:bom"))
    }
}

include(":app")
