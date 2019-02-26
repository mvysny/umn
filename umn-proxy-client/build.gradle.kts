dependencies {
    compile(project(":umn-core"))
    compile("com.squareup.okhttp3:okhttp:${properties["okhttp_version"]}")
    testCompile(project(":umn-sync-tester"))
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-proxy-client", "UMN: Proxy Client")
