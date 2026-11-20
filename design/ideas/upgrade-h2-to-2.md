# Upgrade H2 to 2.x

The owner, 2026-11-20: `D_frozen_0_15_formats` lets the disk format move one way, so 2.x is allowed. But
2.x opens no 1.4 file and H2 brings no migration (`R_h2_reads_one_format`): the migrator is ours,
and apps carry it for years. Not before a reason worth that, say a 1.4.200 bug fixed only in 2.x;
none of 2.0.202's so far (`R_h2_1_4_200_drop_in`).

## The migrator

- **Both H2s in one process:** h2-mvstore 1.4.x relocated, in an artifact of its own; R8 strips it
  to the read path. An app keeps it as long as an install may jump from a 1.4-era release to the
  latest; for Aedict, practically forever.
- **Detect without H2:** the header's first line, `format:1`.
- **Crash-safe:** write a sibling file, sync it, rename it over; a kill restarts the migration.
  Twice the disk while it runs. Aedict-Reader syncs nowhere, so its file is the only copy: never
  delete the old one first, never fall back to a resync.
- **Copy:** `ROOT2` entry by entry. The outgoing buffer, its marker and the log pointer exactly;
  they hold `ObjectDataType` primitives. A lost pointer costs a full replay (idempotent, slow); a
  lost buffer loses edits, a re-sent one brings deleted documents back (`survive-a-cloned-node.md`).
  Index maps: skip, `IndexingUMN` rebuilds them.
- `Q_app_maps`: an app's utility map with its own `DataType` can't be decoded by a generic
  migrator. Drop it (then "each node rebuilds its own" must hold for app maps too), or the app
  passes the old and the new type?
- `Q_h2_free_api`: the API breaks anyway, as `UMN.openUtilityMap` takes H2's `DataType`, another
  interface in 2.x, and `CompositeKey` reproduces 1.4's `Object[]` order. Take H2's types out of
  the public API while at it?
- **The proxy server** migrates at startup, beside `StartupCompaction`.
- **minSdk 22:** the API level 2.x needs is unchecked; 1.4.200 runs on 22 (`R_h2_1_4_200_drop_in`).
- **The pitch's 10-100×:** measure again on 2.x.

## On graduation

- Proxy-server README: a pre-migration backup restored rewinds the log under nodes that read past
  it (`survive-a-restored-log.md`).
- `umn-compat-0.15`: a battery opens a 1.4 file with HEAD, a kill mid-migration included.
- CHANGELOG `Breaking:` line: the API.
