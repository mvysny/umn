dependencies {
    implementation(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(project(":umn-proxy-server"))
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-proxy-server-adminclient", "UMN: Proxy Server: the Admin interface client")
