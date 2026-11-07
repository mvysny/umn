plugins {
    application
}

// The distribution's own runtime, kept out of the published library: the start scripts, the zip and `run` resolve
// runtimeClasspath, the publication runtimeElements.
val distributionRuntimeOnly = configurations.dependencyScope("distributionRuntimeOnly")
configurations.runtimeClasspath { extendsFrom(distributionRuntimeOnly.get()) }

dependencies {
    // the public API (Main, Config) exposes none of these: Jetty and Javalin are ours to swap
    implementation(project(":umn-core"))
    implementation(project(":umn-proxy-protocol"))
    implementation(libs.slf4j.api)
    // a consumer's second binding would make SLF4J warn and pick one
    "distributionRuntimeOnly"(libs.slf4j.simple)
    testRuntimeOnly(libs.slf4j.simple)

    // REST Admin API
    implementation(libs.javalin)
    // still reaches an embedder's runtime classpath, lifting its Jetty: one JVM holds one Jetty
    implementation(platform(libs.jetty.bom))
    implementation(platform(libs.jetty.ee10.bom))
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.gson)

    // config file parsing
    implementation(libs.snakeyaml)

    testImplementation(project(":umn-sync-tester"))
    testImplementation(project(":umn-proxy-client"))
}

application {
    mainClass = "com.gitlab.mvysny.umn.proxy.Main"
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-proxy-server", "UMN: Proxy Server")

kotlin {
    explicitApi()
}
