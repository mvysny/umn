dependencies {
    api(project(":umn-core"))
    implementation(libs.hazelcast)
    testImplementation(project(":umn-sync-tester"))
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-sync-hazelcast", "UMN: Sync with Hazelcast")
