plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories(); intellijDependencies() }
}

dependencies {
    intellijPlatform { intellijIdeaCommunity("2024.2"); instrumentationTools() }
}


intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "242"; untilBuild = provider { null } }
    }
}
