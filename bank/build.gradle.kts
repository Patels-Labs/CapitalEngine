plugins { alias(libs.plugins.run.paper) }

repositories {
    // the claim plugins publish their APIs on their own repos; each is limited to its group so nothing else resolves from them
    maven("https://jitpack.io") { content { includeGroup("com.github.GriefPrevention"); includeGroup("com.github.angeschossen") } }
    maven("https://maven.enginehub.org/repo/") { content { includeGroupByRegex("com\\.sk89q.*") } }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":api"))
    compileOnly(libs.papi)
    compileOnly(libs.hikari)    // loaded at runtime through plugin.yml `libraries:`
    compileOnly(libs.floodgate)
    compileOnly(libs.griefprevention) { isTransitive = false }
    compileOnly(libs.lands) { isTransitive = false }
    compileOnly(libs.worldguard) {   // keep its newer guava/gson and its own server API off our classpath: we compile against paper-api
        for (g in listOf("org.bukkit", "io.papermc.paper", "org.spigotmc", "com.google.guava", "com.google.code.gson")) exclude(group = g)
    }

    testImplementation(libs.paper.api)
    testImplementation(project(":api"))
    testImplementation(project(":core"))   // integration tests load the real CapitalEngine
    testImplementation(libs.vault.unlocked)
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)        // Paper ships sqlite at runtime
    testImplementation(libs.junit)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val v = project.version
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) }
}

tasks.jar { archiveBaseName = "CapitalBank" }

tasks.runServer {
    minecraftVersion(findProperty("mc")?.toString() ?: "1.21.11")
    pluginJars(project(":core").tasks.jar)   // CapitalEngine, which also carries the API classes
}
