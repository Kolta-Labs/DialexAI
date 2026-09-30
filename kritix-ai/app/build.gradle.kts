import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation("io.github.koltalabs.kolt:utils")
            implementation("io.github.koltalabs.kolt:logutils")
            implementation("io.github.koltalabs.kolt:compose-kmp")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kritix.desktop.MainKt"
        jvmArgs += listOf(
            "-Xdock:name=Kritix AI",
            "-Dapple.awt.application.name=Kritix AI",
            "-Dapple.awt.application.appearance=system",
            "-Dapple.laf.useScreenMenuBar=true"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KritixAI"
            packageVersion = "1.0.0"
            vendor = "Kolta Labs"
            description = "Kritix AI - Dialectic Software Engineering & Autonomous Coding Cockpit"
        }
    }
}
