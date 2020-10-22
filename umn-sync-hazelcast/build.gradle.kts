dependencies {
    api(project(":umn-core"))
    implementation("com.hazelcast:hazelcast-client:3.10.2")
    testImplementation(project(":umn-sync-tester"))
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-sync-hazelcast", "UMN: Sync with Hazelcast")
