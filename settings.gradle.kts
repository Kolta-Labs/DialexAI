rootProject.name = "Dialex"

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
includeBuild("../KoltLibs") {
    dependencySubstitution {
        substitute(module("io.github.koltsystems.koltx:utils")).using(project(":libs:utils"))
        substitute(module("io.github.koltsystems.koltx:logutils")).using(project(":libs:logutils"))
        substitute(module("io.github.koltsystems.koltx:compose-kmp")).using(project(":libs:compose-kmp"))
    }
}

include(":shared")
include(":desktopApp")
include(":androidApp")
