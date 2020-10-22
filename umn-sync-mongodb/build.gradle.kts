dependencies {
    api(project(":umn-core"))
    implementation("org.mongodb:mongodb-driver-sync:3.8.0")
    testImplementation(project(":umn-sync-tester"))
    testImplementation("org.testcontainers:mongodb:${properties["testcontainers_version"]}")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-sync-mongodb", "UMN: Sync with MongoDB")
