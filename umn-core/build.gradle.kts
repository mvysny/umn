dependencies {
    compile(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    compile("org.slf4j:slf4j-api:1.7.25")
    compile("com.h2database:h2-mvstore:1.4.197")
    testCompile("com.github.mvysny.dynatest:dynatest-engine:${properties["dynatest_version"]}")
    testCompile("ch.qos.logback:logback-classic:1.2.3")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-core", "UMN: Core")
