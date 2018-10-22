dependencies {
    compile(project(":umn-core"))
    compile("com.hazelcast:hazelcast-client:3.10.2")
    testCompile(project(":umn-sync-tester"))
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-sync-hazelcast", "UMN: Sync with Hazelcast")
