# UMN Proxy Client

This package hosts a client for your [UMN Proxy Server](../umn-proxy-server). For instructions
on how to set up UMN Proxy server please see the [UMN Proxy Server documentation](../umn-proxy-server).

The Proxy Client is extremely portable - it only requires the [okhttp3](https://square.github.io/okhttp/)
library and Java 17. The client works on Android. It uses WebSockets for communication,
therefore it's very easy to use even in restricted networks which employ http proxy servers.

## How to use

Add the client as a Gradle dependency:
```
repositories {
    mavenCentral()
}
dependencies {
    implementation("com.gitlab.mvysny.umn:umn-proxy-client:0.16")
}
```

Simply use the following code to create the proxy client:

```kotlin
val httpClient = OkHttpClient()
val request = Request.Builder().url("ws://umn-proxy-hostname:10144")
ProxyClient(httpClient, request, "username", "password")
```

To add syncing capabilities to your existing UMN database:

```kotlin
val umn = ...
val httpClient = OkHttpClient()
val request = Request.Builder().url("ws://umn-proxy-hostname:10144")

val syncingumn = umn.syncing({
    ProxyClient(httpClient, request, "username", "password")
}, JavaDocumentSerializer, true)
// the sync is now started. You must use syncingumn from now on - if you add documents to
// the original umn the changes will not be propagated to syncingumn.

syncingumn.syncer.start()
syncingumn.syncer.stop()

syncingumn.close()
```

### Secure websockets over HTTPS/SSL

Just configure your UMN Proxy Server to use secure websockets; then simply specify
the following URL into the client: `wss://umn-proxy-hostname:10146`.
