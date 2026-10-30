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
val request = Request.Builder().url("wss://umn-proxy-hostname:10146")
ProxyClient(httpClient, request, "username", "password")
```

To add syncing capabilities to your existing UMN database:

```kotlin
val umn = ...
val httpClient = OkHttpClient()
val request = Request.Builder().url("wss://umn-proxy-hostname:10146")

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

### Old Android and Let's Encrypt

Let's Encrypt's chain ends at ISRG Root X1, which Android trusts only from 7.1.1 on; the
cross-sign that covered older devices expired in 2024. Below 7.1.1 the syncer stays
`SyncerStatus.Offline` with "Trust anchor for certification path not found" and retries forever.
`ProxyClient` uses the `OkHttpClient` you give it, so the fix lives in your app: bundle ISRG
Root X1 and X2 (X2 covers an ECDSA chain), PEMs taken from the Mozilla CA store, as
`res/raw/isrg_root_x1.pem` and `res/raw/isrg_root_x2.pem`.

On API 24 and up, a network security config does it with no code. Put this into
`res/xml/network_security_config.xml`, and point the manifest's
`<application android:networkSecurityConfig="@xml/network_security_config">` at it:

```xml
<network-security-config>
    <base-config>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/isrg_root_x1" />
            <certificates src="@raw/isrg_root_x2" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

API 22 and 23 ignore the network security config. There, give `ProxyClient` an `OkHttpClient`
whose trust manager merges the system roots with the bundled ones; it keeps trusting every site
the system does:

```kotlin
fun Context.trustManager(): X509TrustManager {
    val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.single() as X509TrustManager
    val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    system.acceptedIssuers.forEachIndexed { i, cert -> roots.setCertificateEntry("system$i", cert) }
    for (res in listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)) {
        resources.openRawResource(res).use {
            roots.setCertificateEntry("bundled$res", CertificateFactory.getInstance("X.509").generateCertificate(it))
        }
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(roots) }.trustManagers.single() as X509TrustManager
}

val httpClient = if (Build.VERSION.SDK_INT < 24) {
    val trust = context.trustManager()
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
} else OkHttpClient()
```
