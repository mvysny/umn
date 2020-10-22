dependencies {
    api(project(":umn-core"))
    api("com.github.mvysny.dynatest:dynatest-engine:${properties["dynatest_version"]}")
    implementation("org.slf4j:slf4j-simple:${properties["slf4j_version"]}")
}
