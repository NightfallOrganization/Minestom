import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    id("minestom.common-conventions")
    id("minestom.native-conventions")
    id("com.github.johnrengelman.shadow") version ("8.1.1")
}

application {
    mainClass.set("net.minestom.demo.Main")
    // This is included because Shadow is buggy. Wait for https://github.com/johnrengelman/shadow/issues/613 to befixed.
}

dependencies {
    implementation(rootProject)
    implementation(libs.jNoise)
}

tasks.withType<ShadowJar> {
    archiveFileName.set("minestom-demo.jar")
}