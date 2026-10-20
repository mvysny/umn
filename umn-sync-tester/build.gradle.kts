dependencies {
    api(project(":umn-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
    api(kotlin("test-junit5"))
    implementation(libs.slf4j.simple)
}

