dependencies {
    compile(project(":umn-core"))
    compile("org.apache.kafka:kafka-clients:1.1.0") {
        exclude(mapOf("module" to "snappy-java"))
        exclude(mapOf("module" to "lz4-java"))
    }
    testCompile(project(":umn-sync-tester"))
}
