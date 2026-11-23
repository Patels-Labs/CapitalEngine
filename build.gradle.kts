plugins { `java-library` }

subprojects {
    apply(plugin = "java-library")
    group = "com.patelheet"
    // each released module owns its version in <module>/version.txt, which Release Please maintains; sim is never released
    version = file("version.txt").takeIf { it.exists() }?.readText()?.trim() ?: "0.0.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.org/repository/maven-public/")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://repo.opencollab.dev/main/")
    }
    tasks.withType<JavaCompile> { options.release = 21; options.encoding = "UTF-8" }
    tasks.withType<Test> { useJUnitPlatform() }
    // GPL-3.0-only with the CapitalEngine Add-on Exception: every jar carries both texts
    tasks.withType<Jar> { metaInf { from(rootProject.file("LICENSE"), rootProject.file("ADDON-EXCEPTION.txt")) } }
}
