dependencies {
    api(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    implementation(libs.slf4j.api)
    api(libs.h2.mvstore)
    api(libs.vok.dataloader)
    testImplementation(libs.slf4j.simple)
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-core", "UMN: Core")

kotlin {
    explicitApi()
}
