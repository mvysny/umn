plugins {
    application
}

dependencies {
    api(project(":umn-proxy-client"))
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty)
    implementation(libs.slf4j.simple)

    // REST Admin API
    api(libs.javalin)
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.gson)

    // config file parsing
    implementation(libs.snakeyaml)

    testImplementation(project(":umn-sync-tester"))
}

application {
    mainClass = "com.gitlab.mvysny.umn.proxy.Main"
}

