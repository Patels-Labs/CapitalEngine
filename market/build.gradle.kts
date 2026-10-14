plugins { alias(libs.plugins.run.paper) }

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":api"))
    compileOnly(libs.papi)
    compileOnly(libs.hikari)    // loaded at runtime through plugin.yml `libraries:`
    compileOnly(libs.floodgate)

    testImplementation(libs.paper.api)
    testImplementation(project(":api"))
    testImplementation(project(":core"))   // integration tests load the real CapitalEngine
    testImplementation(libs.vault.unlocked)
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)        // Paper ships sqlite at runtime
    testRuntimeOnly(libs.postgres)         // StoreDialectTest runs against real servers when CAPITAL_TEST_* is set
    testRuntimeOnly(libs.mysql)
    testImplementation(libs.junit)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val v = project.version
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) }
}

tasks.jar { archiveBaseName = "CapitalMarket" }

tasks.runServer {
    minecraftVersion(findProperty("mc")?.toString() ?: "1.21.11")
    pluginJars(project(":core").tasks.jar)   // CapitalEngine, which also carries the API classes
}
