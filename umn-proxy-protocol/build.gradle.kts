// Its package stays umn-proxy-client's, com.gitlab.mvysny.umn.proxyclient, where 0.15 had AbstractMessage: the
// compat module's Golden.java compiles against 0.15 and HEAD alike, and apps importing it change nothing.
dependencies {
    api(kotlin("stdlib"))  // don't use -jdk8 to stay compatible with Android
    api(project(":umn-core"))
}

val publish = ext["publish"] as (artifactId: String, description: String) -> Unit
publish("umn-proxy-protocol", "UMN: Proxy wire protocol, shared by the Proxy Client and Server")

kotlin {
    explicitApi()
}
