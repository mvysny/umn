// Proves the README's kotlinx.serialization DocumentSerializer example; unpublished. A module of its
// own, so that the serialization compiler plugin never runs on umn-core.
plugins {
    kotlin("plugin.serialization")
}

dependencies {
    testImplementation(project(":umn-core"))
    testImplementation(libs.kotlinx.serialization.protobuf)
    testImplementation(libs.slf4j.simple)
}
