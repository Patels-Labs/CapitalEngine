plugins { alias(libs.plugins.run.paper) }

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":api"))
    compileOnly(libs.vault.unlocked)   // also contains the legacy net.milkbowl.vault classes
    compileOnly(libs.hikari)           // loaded at runtime through plugin.yml `libraries:`
    compileOnly(libs.papi)
    compileOnly(libs.floodgate)

    testImplementation(libs.paper.api)
    testImplementation(project(":api"))
    testImplementation(libs.vault.unlocked)
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)
    testImplementation(libs.junit)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val v = project.version
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) }
}

// Core is where the API classes live at runtime: add-ons compile against capitalengine-api and find them in this jar.
tasks.jar {
    archiveFileName = "CapitalEngine.jar"
    from(project(":api").sourceSets.main.get().output)
}

tasks.runServer {
    minecraftVersion(findProperty("mc")?.toString() ?: "1.21.11")   // ./gradlew :core:runServer -Pmc=26.2
    downloadPlugins { hangar("VaultUnlocked", "2.20.2") }   // smoke tests: gives us both Vault interfaces
}
