# Full-text search over UMN documents

A full-text index as a UMN wrapper would be handy, e.g. for searching Aedict's notepad. UMN had
an experiment, `FullTextUMN` (2019), that was never released and was deleted before 0.16. To be
brainstormed later. The old code is in git as of `4501ee3`:

- `umn-core/src/main/kotlin/com/gitlab/mvysny/umn/core/fulltext/`: `FullTextUMN`,
  `FullText2UMN`, an `Analyzer` with an English Porter stemmer, and a merge/hash join over
  `UtilityMap`s, with tests under `umn-core/src/test/…/fulltext/`.
- `umn-performance-experiments/`: `FullTextAgainstLucene`, the benchmark against Lucene 3.6.2.

## Was the experiment a failure?

`7ad6b25` is titled "FullTextUMN probably a failure", but `FullTextUMN` wasn't one, judged by
the numbers in that commit. The benchmark indexed ~210k city names (OpenWeatherMap's
`city.list.json`, read from `~/Downloads`, gone now), and the commit recorded only ratios against
Lucene 3.6, force-merged:

| | `FullTextUMN` | `FullText2UMN` | Lucene |
|---|---|---|---|
| bulk write | 3× slower | 6× slower | — |
| file | 15 MB | 300 MB | 7 MB |
| query | same | 2× faster | — |

`FullText2UMN`, one `UtilityMap` per token, was the real failure: 300 MB. `FullTextUMN` kept one
map of `(token id, importance, doc UUID) → true` postings and matched Lucene on reads. Reads are
the part a user feels.

The write and size gaps are what a B-tree costs compared with Lucene's write-once, delta-encoded
segments, and they matter only in bulk. Each posting carries a 16-byte UUID where Lucene stores a
doc-number delta, often a byte. UMN's real data is small and written one document at a time, e.g.
Aedict's notepad: hundreds to thousands of documents of a few KB each. The bulk cases are a fresh
node pulling the whole log, `DocumentCreated2`'s initial population, and a reindex. Without
absolute numbers we don't know whether those take a second or a minute.

Lucene was also the wrong yardstick. A Lucene index beside UMN sits outside the wrapper chain:
nothing updates it when a synced record lands, and it has its own files, its own commit and its
own crash recovery. A full-text wrapper is one more node-local utility map, maintained with every
mutation, sync included, and in the same MVStore commit.

## What was wrong with it: the code, not the approach

- **String documents only.** Importance was `(document as String).length`, a benchmark shortcut:
  a `ClassCastException` on any real document.
- **A search wrote.** `search()` inserted every unknown query token into the token map, so the
  map grew with every typo, and reads weren't read-only.
- **Exact AND only.** No prefix match ("san" → "santa", search-as-you-type), no OR, and results
  were ordered by importance and then UUID, i.e. arbitrarily within one importance.
- **No reindex.** Wrapping a store that already has documents indexed none of them.
  `IndexingUMN` shares the limit (`D_three_op_log`), but for full-text it is the common case:
  search gets added to an app whose users already have data. It is also what makes the on-disk
  layout hard to change later. Without a rebuild, any layout change leaves every existing index
  stale.
- **No locking.** `IndexingUMN` serializes document and index updates under a lock; the
  experiment didn't.
- **English analyzer.** Porter stemmer, English stop words, `BreakIterator.getWordInstance()`.
  How it handles Japanese, Aedict's other language, was never checked.
- **The join stepped one key at a time.** A `@todo` asked for a cursor seek; `MVMap.ceilingKey`
  could leap instead.

## How it relates to `IndexingUMN`

`IndexDef.keyGetter` already returns a `Set<K>`, so a token index is one `IndexDef` away. But
`IndexingUMN` stores `key → Array<UUID>` and rewrites the whole array on each insert, so a token in
thousands of documents costs a full array copy per write. Its KDoc already says so: "If the index
is expected to have high number of collisions, use TODO." The experiment's
one-entry-per-posting layout is that TODO.

So one direction: a high-collision index kind in `IndexingUMN` with the posting layout, and
full-text as an `IndexDef` plus a join query. One wrapper, one lock, one reindex story, and the
same fix also helps any other many-documents-per-key index. The other direction is a standalone
wrapper again, fixed as listed above.

## Scope as first sketched (issue #7, 2019)

- **Queries:** AND on words first, OR later; never wildcards, fuzzy matching, fields or phrases.
  Inspiration: [Lucene's query syntax](https://lucene.apache.org/core/2_9_4/queryparsersyntax.html);
  on multi-word queries over an inverted index:
  [this StackOverflow answer](https://stackoverflow.com/questions/6032469/use-of-indexes-for-multi-word-queries-in-full-text-search-e-g-web-search).
- **A pluggable `Analyzer` interface:** splits text into words, drops stop words, normalizes
  (lowercase, stemming). The default: JDK `BreakIterator.getWordInstance()`, Lucene's English
  stop words, `String.lowercase()`, a Porter stemmer. The experiment hardwired that default; an
  interface is what lets Japanese plug in its own (`Q_fulltext_use_case`).

## Open questions

- `Q_fulltext_use_case`: what uses it first? If it is Aedict's notepad, which text is searched:
  an `EntryRef`'s embedded entry (Japanese, plus English glosses) or a `TagDoc`'s free text? The
  answer decides the analyzer, whether prefix match is needed, and whether ranking matters.
- `Q_fulltext_shape`: a posting-layout index kind in `IndexingUMN`, or a standalone wrapper?
- `Q_fulltext_bench`: measure absolute times this time. The city list is gone, so pick a dataset
  that can be committed or downloaded again, ideally Aedict-shaped.
- `Q_reindex`: rebuilding a node-local index from the documents, for `IndexingUMN` and full-text
  alike. It is the prerequisite either way. It would also fix a downgrade/upgrade round trip: a
  wrapper new since 0.15 misses what 0.15 wrote meanwhile.
