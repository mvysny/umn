dependencies {
    compile(project(":umn-proxy-client"))
    compile("org.eclipse.jetty.websocket:websocket-server:9.4.12.v20180830")
    compile("ch.qos.logback:logback-classic:1.2.3")

    // REST Admin API
    compile("io.javalin:javalin:${properties["javalin_version"]}")
    compile(kotlin("stdlib-jdk8"))
    compile("com.google.code.gson:gson:${properties["gson_version"]}")

    // config file parsing
    compile("org.yaml:snakeyaml:1.23")

    testCompile(project(":umn-sync-tester"))
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
