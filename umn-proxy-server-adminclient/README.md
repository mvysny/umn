# UMN Proxy Server AdminClient

A Java/Kotlin Proxy Server admin client which uses REST+Retrofit to communicate with UMN Proxy Server.

Add the client as a Gradle dependency:
```
repositories {
    mavenCentral()
}
dependencies {
    implementation("com.gitlab.mvysny.umn:umn-proxy-server-adminclient:0.16")
}
```

Example:
```
val client = createAdminClient("http://localhost:10145/admin/")
client.createOrUpdateUser("user", "example")
```
