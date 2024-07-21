dependencies {
    api(project(":umn-core"))
    implementation("org.slf4j:slf4j-api:${properties["slf4j_version"]}")
    implementation("org.mongodb:mongodb-driver-sync:3.8.0")
    testImplementation(project(":umn-sync-tester"))
    testImplementation("org.testcontainers:mongodb:${properties["testcontainers_version"]}")
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-sync-mongodb", "UMN: Sync with MongoDB")
