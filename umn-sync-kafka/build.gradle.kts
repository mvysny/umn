dependencies {
    api(project(":umn-core"))
    implementation("org.apache.kafka:kafka-clients:1.1.0") {
        exclude(mapOf("module" to "snappy-java"))
        exclude(mapOf("module" to "lz4-java"))
    }
    testImplementation(project(":umn-sync-tester"))
    testImplementation("org.testcontainers:kafka:${properties["testcontainers_version"]}")
}
