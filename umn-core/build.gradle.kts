dependencies {
    compile(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    compile("org.slf4j:slf4j-api:${properties["slf4j_version"]}")
    compile("com.h2database:h2-mvstore:${properties["mvstore_version"]}")
    compile("com.github.mvysny.vokdataloader:vok-dataloader:0.8")
    testCompile("com.github.mvysny.dynatest:dynatest-engine:${properties["dynatest_version"]}")
    testCompile("org.slf4j:slf4j-simple:${properties["slf4j_version"]}")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-core", "UMN: Core")
