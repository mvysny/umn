dependencies {
    api(project(":umn-core"))
    api(libs.dynatest)
    implementation(libs.slf4j.simple)
    runtimeOnly("org.junit.platform:junit-platform-launcher")
}

