# UMN Proxy Server

The UMN Proxy plugs into the synchronization mechanism and transparently relies synchronization commands
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
client which uses http(s) websockets:

```
[Your App] --uses--> [UMN] <---uses---> [ProxyClient] <---INTERNET---> [UMN Proxy Server] --uses--> [SyncMongoDBClient] --localhost--> [MongoDB]
```

UMN Proxy supports two modes of operation:

* The Proxy Mode
* The Standalone Mode

## The Proxy Mode

In this mode, the UMN Proxy only relies the synchronization commands to the actual database.
Therefore, you need to run both the UMN Proxy Server and the actual database server (such as MongoDB, Hazelcast or
Kafka) on your server machine. If you wish your setup to be simpler, use the Standalone Mode.

## The Standalone Mode

UMN Proxy Server is capable of not only acting as a proxy (relying sync commands to, say, a MongoDB server) -
it can be configured to also handle the duty of storing of the log (simply by using the `MVStoreGlobalLog`
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

## Admin Interface

UMN Proxy Server offers the administration interface as a REST endpoint. By default the interface
listens on http 8081 on localhost only, no credentials are required.

To control the UMN Proxy Server programatically, see the Java client: [umn-proxy-server-adminclient](../umn-proxy-server-adminclient).

### User management

The user management only works when the UMN Proxy Server is running in the Standalone mode.
The purpose of this API is to add, update and remove users that are allowed to sync via the UMN Proxy Client.
For every user a separate global log is created.

> *Note:* When the UMN Proxy Server is not running in standalone mode, the log is stored in a separate database such as MongoDB.
Use the database built-in mechanisms to manage users.

| Method+Endpoint | Explanation
| --------------- | -----------------------------
| GET /admin/users | Returns all users, as a JSON List of String usernames. Example: `curl -i localhost:8081/admin/users` should print `["user", "user25"]`
| PUT /admin/users | Creates a new user with given plaintext password. Requires two parameters, passed as query params: `username` and `password`. If the user already exists, its password is updated. Example: `curl -i -X PUT "localhost:8081/admin/users?username=user&password=example"` should print 204 NO CONTENT on success.
| DELETE /admin/users/:username | Deletes the user with given username. Example: `curl -i -X DELETE localhost:8081/admin/users/user` should print 204 NO CONTENT on success.

### Statistics

| Method+Endpoint | Explanation
| --------------- | -----------------------------
| GET /admin/stats/logsizes | Returns all users and their log sizes, e.g. `curl -i localhost:8081/admin/stats/logsizes` should print `{"user":25, "user2": 1645}`
