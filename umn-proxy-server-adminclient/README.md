# UMN Proxy Server AdminClient

A Java/Kotlin Proxy Server admin client which uses REST+Retrofit to communicate with UMN Proxy Server.

Add the client as a Gradle dependency:
```
repositories {
    maven { url "https://dl.bintray.com/mvysny/gitlab" }
}
dependencies {
    compile('com.gitlab.mvysny.umn:umn-proxy-server-adminclient:0.11')
}
```

Example:
```
val client = createAdminClient("http://localhost:10145/admin/")
client.createOrUpdateUser("user", "example")
```
