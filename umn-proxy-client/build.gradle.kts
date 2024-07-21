dependencies {
    api(project(":umn-core"))
    implementation(libs.slf4j.api)
    api(libs.okhttp)
    testImplementation(project(":umn-sync-tester"))
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-proxy-client", "UMN: Proxy Client")

kotlin {
    explicitApi()
}
