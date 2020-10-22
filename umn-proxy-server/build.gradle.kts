dependencies {
    api(project(":umn-proxy-client"))
    implementation("org.eclipse.jetty.websocket:websocket-server:9.4.12.v20180830")
    implementation("org.slf4j:slf4j-simple:${properties["slf4j_version"]}")

    // REST Admin API
    api("io.javalin:javalin:${properties["javalin_version"]}")
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.google.code.gson:gson:${properties["gson_version"]}")

    // config file parsing
    implementation("org.yaml:snakeyaml:1.23")

    testImplementation(project(":umn-sync-tester"))
}

val zip by tasks.creating(Zip::class) {
    from("src/main/scripts")
    into("lib") {
        from(configurations.runtime.allArtifacts.files)
        from(configurations.runtime)
    }
}

artifacts {
    add("archives", zip)
}
