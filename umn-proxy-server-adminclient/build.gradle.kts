dependencies {
    implementation(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    implementation("com.squareup.okhttp3:okhttp:${properties["okhttp_version"]}")
    implementation("com.google.code.gson:gson:${properties["gson_version"]}")
    testImplementation(project(":umn-proxy-server"))
    testImplementation("com.github.mvysny.dynatest:dynatest-engine:${properties["dynatest_version"]}")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-proxy-server-adminclient", "UMN: Proxy Server: the Admin interface client")
