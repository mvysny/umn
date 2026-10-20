dependencies {
    api(project(":umn-core"))
    implementation(libs.kafka.client) {
        exclude(mapOf("module" to "snappy-java"))
        exclude(mapOf("module" to "lz4-java"))
    }
    testImplementation(project(":umn-sync-tester"))
    testImplementation(libs.testcontainers.kafka)
}

tasks.test {
    failOnNoDiscoveredTests = false  // KafkaClientTest is disabled in its source
}
