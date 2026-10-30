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
client.createOrUpdateUser2("user", "example")
```

`createOrUpdateUser2` needs UMN proxy server 0.17+; an older server fails it, e.g. the 0.12 server
with a 500, and creates nobody. Against an
older server, use the deprecated `createOrUpdateUser`, which sends the password in the URL.
