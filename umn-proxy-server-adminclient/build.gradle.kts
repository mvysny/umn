dependencies {
    compile(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    compile("com.squareup.retrofit2:converter-gson:2.4.0")
    testCompile(project(":umn-proxy-server"))
    testCompile("com.github.mvysny.dynatest:dynatest-engine:${properties["dynatest_version"]}")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-proxy-server-adminclient", "UMN: Proxy Server: the Admin interface client")
