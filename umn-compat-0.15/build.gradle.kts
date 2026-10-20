// Runs UMN 0.15 nodes against HEAD and the production proxy server image; unpublished.
// The scenarios are in design/ideas/review-incompatibilities-since-0.15.md.

repositories {
    // UMN 0.15 died with JCenter; its jars and poms are committed here, copied from Aedict's .m2
    exclusiveContent {
        forRepository { maven(layout.projectDirectory.dir("repo")) }
        filter { includeGroup("com.gitlab.mvysny.umn") }
    }
}

// UMN 0.15 the way Aedict-Online's Gradle resolves it, never mixed with HEAD's classpath
val umn015: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    umn015(libs.umn015.proxy.client) {
        exclude(group = "com.squareup.okhttp3")
    }
    umn015(libs.okhttp)
    umn015(libs.umn015.vok.dataloader)
    umn015(libs.umn015.kotlin.stdlib)
    umn015(libs.umn015.slf4j.simple)

    testImplementation(project(":umn-proxy-client"))
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}

// UMN 0.15's side: Driver015 and the shared steps, compiled against umn015 only
val old: SourceSet by sourceSets.creating {
    java.srcDir("src/scenario/java")
    compileClasspath = umn015
    runtimeClasspath = output + umn015
}
sourceSets.test {
    java.srcDir("src/scenario/java")
}

tasks.test {
    // the tests fork Driver015 on this classpath
    inputs.files(old.runtimeClasspath)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dumn015.classpath=${old.runtimeClasspath.asPath}")
    }
}

tasks.register<JavaExec>("recordGolden015") {
    description = "Records the bytes UMN 0.15 writes into src/test/resources/golden; run once, commit the files"
    classpath = old.runtimeClasspath
    mainClass = "compat.Driver015"
    args("golden", layout.projectDirectory.dir("src/test/resources/golden").asFile.path)
}
