dependencies {
    api(project(":umn-core"))
    implementation("org.slf4j:slf4j-api:${properties["slf4j_version"]}")
    api("com.squareup.okhttp3:okhttp:${properties["okhttp_version"]}")
    testImplementation(project(":umn-sync-tester"))
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-proxy-client", "UMN: Proxy Client")

kotlin {
    explicitApi()
}
