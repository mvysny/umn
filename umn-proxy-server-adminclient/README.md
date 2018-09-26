# UMN Proxy Server AdminClient

A Java/Kotlin Proxy Server admin client which uses REST+Retrofit to communicate with UMN Proxy Server.

Example:
```
val client = createAdminClient("http://localhost:10145/admin/")
client.createOrUpdateUser("user", "example")
```
