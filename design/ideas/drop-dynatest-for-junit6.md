# Drop DynaTest, use JUnit 6

Replace DynaTest (`com.github.mvysny.dynatest:dynatest` 0.25) with plain JUnit Jupiter 6
(`org.junit:junit-bom` 6.1.3, latest on 2026-10-18). JUnit 6 needs Java 17, so this idea
can go ahead now that the build targets Java 17.

## What uses DynaTest today

There are 23 files across all modules except the perf experiments: 20 `DynaTest({...})`
classes, about 161 `test(...)`, 68 `group(...)`, 29 `beforeEach` / 20 `afterEach`, and
4 `beforeGroup` / 4 `afterGroup`. Assertions are `kotlin.test.expect` (257 uses, which
isn't DynaTest), plus DynaTest's `expectList` (101) and `expectThrows` (20).

The hard part is the **test batteries**: `DynaNodeGroup` extension functions that take a
producer lambda and get run once per implementation.

- `umn-core` tests: `genericUmnTestBattery`, `indexingUmnTestBattery`,
  `announcingTestBattery`, `mutationLogTestBattery`, `ackFifoBufferTestBattery`. For example,
  `MvstoreUMNTest` runs `genericUmnTestBattery` twice, once with an in-memory store and once
  with a file-backed one.
- `umn-sync-tester` main (shipped to the backend modules as `api(libs.dynatest)`):
  `globalLogClientTestBattery`, `syncTestBattery`, `writerReaderTestBattery`. Every
  `GlobalLogClient` implementation runs these.

Batteries also nest groups, and a group's `beforeEach` applies to every test inside it.

## How a battery maps onto JUnit 6

- **Option A — `@ParameterizedClass`** (Jupiter 5.13+, so it's in 6): the battery becomes
  a class parameterized by the producer, with `@Nested inner class`es for its groups and
  `@BeforeEach` / `@AfterEach` for the lifecycle. Each implementation contributes one
  `@MethodSource` entry. This is closest to today's structure and keeps proper lifecycle and
  IDE re-run. The question is whether a class in `umn-sync-tester` main can be parameterized
  by the module that uses it; it probably can, through a subclass that only supplies the
  source (`Q_battery_shape`).
- **Option B — an abstract battery class** with `abstract fun createUmn(): UMN`, and a
  subclass per implementation. This is plain JUnit with no new features, but it is
  inheritance for sharing test code. The COP rules allow that for tests, as the framework's
  own extension point, but it's worth a look.
- **Option C — `@TestFactory` + `DynamicTest`**: the closest syntactic match to DynaTest,
  but dynamic tests get no per-test `@BeforeEach`, so `beforeEach` / `afterEach` would have
  to be inlined into each test. Rejected unless A doesn't work.

## Assertions

- `kotlin.test.expect` keeps working; it binds to JUnit via `kotlin-test-junit5`. Does the
  2.4.x `kotlin-test-junit5` work against Jupiter 6 (`Q_kotlin_test_junit6`)? If not,
  switch to `assertEquals`.
- `expectList(a, b) { … }` → `expect(listOf(a, b)) { … }`; a mechanical sed.
- `expectThrows<T>("msg") { … }` → `assertThrows<T> { … }` plus a check that the message
  contains `"msg"`. A tiny `expectThrows` helper in `umn-sync-tester` would keep the call
  sites unchanged (`Q_keep_helpers`).

## Build

- Drop `dynatest` from `libs.versions.toml`, and add `junit-bom` with `junit-jupiter`
  (`testImplementation` everywhere, `api` in `umn-sync-tester`).
- `umn-sync-tester` declares `runtimeOnly("org.junit.platform:junit-platform-launcher")`
  without a version. The BOM should supply it.
- The `useJUnitPlatform()` in the root `build.gradle.kts` stays.

## Open questions

- `Q_battery_shape`: Option A or B for the batteries? Prototype A on
  `genericUmnTestBattery` (2 producers) and `globalLogClientTestBattery` (cross-module)
  first.
- `Q_kotlin_test_junit6`: keep `kotlin.test` assertions, or go all-in on JUnit's
  `Assertions`?
- `Q_keep_helpers`: carry small `expectList` / `expectThrows` helpers so the 121 call sites
  don't change, or rewrite them?
- `Q_order`: do this before or after the big dependency upgrades? Doing it first gives a
  trustworthy test suite to upgrade against; the Java 17 toolchain it needs is in.
