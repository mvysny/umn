import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

dependencies {
    implementation(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(project(":umn-proxy-server"))
    testImplementation(project(":umn-core"))
    testImplementation(libs.javalin)
    testRuntimeOnly(libs.slf4j.simple)
}

// the tests run the server's internals directly, to seed its users and logs behind the admin API's back
val proxyServer = project(":umn-proxy-server")
tasks.named<KotlinCompile>("compileTestKotlin") {
    val server = proxyServer.layout.buildDirectory
    compilerOptions.freeCompilerArgs.add(server.map {
        "-Xfriend-paths=${it.dir("classes/kotlin/main").asFile},${it.dir("libs").asFile}/umn-proxy-server-${proxyServer.version}.jar"
    })
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-proxy-server-adminclient", "UMN: Proxy Server: the Admin interface client")
