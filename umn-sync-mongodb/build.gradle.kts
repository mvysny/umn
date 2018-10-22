dependencies {
    compile(project(":umn-core"))
    compile("org.mongodb:mongodb-driver-sync:3.8.0")
    testCompile(project(":umn-sync-tester"))
    testCompile("ch.qos.logback:logback-classic:1.2.3")
}

val configureBintray = ext["configureBintray"] as (artifactId: String, description: String) -> Unit
configureBintray("umn-sync-mongodb", "UMN: Sync with MongoDB")
