dependencies {
    api(project(":umn-core"))
    implementation(libs.slf4j.api)
    implementation(libs.mongodb.driver)
    testImplementation(project(":umn-sync-tester"))
    testImplementation(libs.testcontainers.mongodb)
}

tasks.test {
    failOnNoDiscoveredTests = false  // SyncMongoDBClientTest skips itself when Testcontainers sees no Docker
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-sync-mongodb", "UMN: Sync with MongoDB")
