dependencies {
    api(project(":umn-core"))
    api("com.squareup.okhttp3:okhttp:${properties["okhttp_version"]}")
    testImplementation(project(":umn-sync-tester"))
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-proxy-client", "UMN: Proxy Client")
