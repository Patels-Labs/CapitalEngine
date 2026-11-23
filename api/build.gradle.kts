plugins { `maven-publish` }

base.archivesName = "capitalengine-api"

dependencies { compileOnly(libs.paper.api) }

java { withJavadocJar(); withSourcesJar() }

tasks.processResources {   // the running API reports the version it was built as (ApiImpl reads this file)
    val v = project.version
    inputs.property("version", v)
    filesMatching("capitalengine-api.version") { expand("version" to v) }
}

tasks.jar { from("API-LICENSE.txt") { into("META-INF") } }   // LICENSE and ADDON-EXCEPTION.txt come from the root build
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    doLast { copy { from("API-LICENSE.txt", rootProject.file("LICENSE"), rootProject.file("ADDON-EXCEPTION.txt")); into(destinationDir!!) } }
}
tasks.named<Jar>("javadocJar") { from("API-LICENSE.txt") { into("META-INF") } }
tasks.named<Jar>("sourcesJar") { from("API-LICENSE.txt") { into("META-INF") } }

publishing {
    publications { create<MavenPublication>("api") { artifactId = "capitalengine-api"; from(components["java"]) } }
    // ponytail: repo URL (a folder, file:///... , for the GitHub Pages repo, or an http repo with -PcapitalRepoUser/-PcapitalRepoPass); nothing is published unless -PcapitalRepoUrl is given.
    findProperty("capitalRepoUrl")?.let { url ->
        repositories {
            maven {
                setUrl(url.toString())
                findProperty("capitalRepoUser")?.let { user -> credentials { username = user.toString(); password = findProperty("capitalRepoPass")?.toString() } }
            }
        }
    }
}
