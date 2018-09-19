# UMN Proxy

UMN Proxy Client plugs into the synchronization mechanism and transparently relies synchronization commands
over a http(s) web socket protocol to a remote
UMN Proxy Server. Typically you employ the UMN Proxy setup when:

* You don't want to expose your database directly over the internet, but you want it to be hidden
  behind a firewall
* You are developing an Android app.
* You don't want to go through the hassle of setting up MongoDB/Hazelcast/Kafka server.

Say that you sync over a MongoDB server without the UMN Proxy. Then the communication path of your app
looks like the following:

```
[Your App] --uses--> [UMN] <---uses---> [SyncMongoDBClient] <---INTERNET---> [MongoDB]
```

The problem is that the `SyncMongoDBClient` class requires MongoDB Java client which doesn't work on Android;
also your database needs to be exposed over the internet. You can solve both of these issues
by using the UMN Proxy. The Proxy relies the synchronization commands using a very thin [umn-proxy-client](../umn-proxy-client)
client which works from anywhere and uses http(s) websockets:

```
[Your App] --uses--> [UMN] <---uses---> [ProxyClient] <---INTERNET---> [UMN Proxy Server] --uses--> [SyncMongoDBClient] --localhost--> [MongoDB]
```

UMN Proxy supports two modes of operation:

* The Proxy Mode
* The Standalone Mode

## The Proxy Mode

In this mode, the UMN Proxy only relies the synchronization commands to the actual database.
Therefore, you need to run both the UMN Proxy and the actual database server (such as MongoDB, Hazelcast or
Kafka) on your server machine. If you wish your setup to be simpler, use the Standalone Mode.

## The Standalone Mode

UMN Proxy is capable of not only acting as a proxy (relying sync commands to, say, MongoDB) -
it can also be configured to actually handle the duty of storing of the log (simply by using `MVStoreGlobalLog`
to store it into a H2 MVStore database).

Therefore, if you are shooting for the most simple setup, you don't need Kafka nor MongoDB nor Hazelcast -
all you need is a UMN Proxy server up and running. Since UMN Proxy only requires Java JRE to run,
it's extremely easy to launch and extremely portable.

See below on how to configure UMN Proxy Server in Standalone Mode.

## More technical explanation

UMN uses the [GlobalLogClient](../umn-core/src/main/kotlin/com/gitlab/mvysny/umn/sync/GlobalLogClient.kt)
interface to access a common server to perform the synchronization with all other UMN clients.
Every server then provides its own implementation of the `GlobalLogClient`,
which is just a thin wrapper wrapping native client for that particular server.
For example, the [UMN MongoDB Sync](../umn-sync-mongodb)
provides the `SyncMongoDBClient` class which implements the `GlobalLogClient` interface and uses
the [MongoDB Java client](https://mongodb.github.io/mongo-java-driver/) to push log items to MongoDB queues. The very same idea is used by the Kafka Sync,
Hazelcast sync and others.

The problem is that the [MongoDB Java Client doesn't work on Android](https://gitlab.com/mvysny/umn/issues/1). And neither
does Hazelcast nor Kafka client. They only work on a full-blown JRE. So you do exactly that -
you run the MongoDB client on your Java server along with your database, and use UMN Proxy to relay
the synchronization commands to your server.

## Configuration and Deployment

TBD

### Standalone Mode

In this mode the UMN Proxy stores the log using the embedded H2 MVStorage database, and therefore
it doesn't require Kafka/MongoDB/Hazelcast server

TBD configuration
