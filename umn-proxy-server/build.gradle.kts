plugins {
    application
}

dependencies {
    api(project(":umn-proxy-client"))
    implementation(libs.slf4j.simple)

    // REST Admin API
    api(libs.javalin)
    api(platform(libs.jetty.bom))
    api(platform(libs.jetty.ee10.bom))
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.gson)

    // config file parsing
    implementation(libs.snakeyaml)

    testImplementation(project(":umn-sync-tester"))
}

application {
    mainClass = "com.gitlab.mvysny.umn.proxy.Main"
}

