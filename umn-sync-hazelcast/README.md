# UMN Hazelcast Client

Syncs UMN via a [Hazelcast](https://hazelcast.com/), connecting to a Hazelcast
server using Hazelcast Client. The client synchronizes over a shared Hazelcast `IList`
instance.

## Tutorial

TBD

## Security

Unknown.

## Disadvantages:

* Free Hazelcast only supports in-memory data storage
* Android compatibility: you will probably need to use older Hazelcast (3.4.x) client:
  https://github.com/hazelcast/hazelcast/issues/6102
