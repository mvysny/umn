# Decisions

Why this project is the way it is and not otherwise — FAQ-shaped: each entry is a question and
its current answer. Rewrite the answer when it changes; delete the entry when nobody asks any
more. An entry is earned by what it would cost to reverse — half the code base — or by research
the next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels
"because that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library,
the CI host, a version bump — a comment at the site of the choice, or nothing; nothing about
`design/` itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md`
is the index. The first entry is the ruler: every later one trims to its length — which is how
long this file gets, so keep it short. When you have written an entry, re-read it against the one
above, open the doc comments it touches and cut what they already say, then cut the fat.

---

## D_three_op_log — Why a UUID → blob map with three mutations rather than a queryable store?

UMN was built for the Aedict dictionary on Android, where it must be fast and must sync across
a user's devices. {UMN} is a map from UUID to a serialized document, mutated only by `create`,
`update` and `delete`; MVStore appends each write to its copy-on-write log. Three mutations mean
three {LogRecord} types, so the change history is a log any transport can carry and any node can
replay in global order — that is the whole sync design, and it is why backends stay thin
(**Sync over any server**). Indices, full-text and sync are wrappers, each keeping its own state
in node-local utility maps. Why not SQL or sqlite: 10-100× slower than MVStore, and a query
language has no replayable shape. Why not JSON documents: binary serialization is faster on a
phone, so the format is the caller's {DocumentSerializer}. The cost we carry: no transactions,
no query planner, and an index definition change does not reindex existing documents.
