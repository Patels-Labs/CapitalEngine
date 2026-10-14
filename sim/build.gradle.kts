// Test-only: runs the real plugins on MockBukkit with simple agents. Nothing here is shipped.
dependencies {
    testImplementation(libs.paper.api)
    testImplementation(project(":api"))
    testImplementation(project(":core"))
    testImplementation(project(":market"))
    testImplementation(libs.vault.unlocked)
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)
    testImplementation(libs.junit)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("capitalengine.sim", "true")
    maxHeapSize = "1g"
}

// ./gradlew :sim:run --args="small-20.yml --days 120 --seed 7 --sweep market.autopilot.floor-per-active-player=50,150"
tasks.register<JavaExec>("run") {
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.patelheet.capitalengine.sim.SimMain"
    systemProperty("capitalengine.sim", "true")
    maxHeapSize = "2g"
}
