dependencies {
    api(project(":umn-core"))
    implementation("com.hazelcast:hazelcast-client:3.10.2")
    testImplementation(project(":umn-sync-tester"))
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-sync-hazelcast", "UMN: Sync with Hazelcast")
