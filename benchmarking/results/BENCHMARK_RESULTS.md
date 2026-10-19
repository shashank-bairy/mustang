# Mustang search benchmark: before vs after (sorted-array cursors (`PostingListCursors`))

**Before:** unpatched `main` (`97235e5`). **After:** this branch.

## Workload: `MustangRangeSearchBenchmark`
- Every predicate is a numeric range: `$.x > t` or `$.x < t` (`IncludedPredicate` with a `RangeDetail`). Unlike equality, a range key can't be looked up directly from the request value. Every distinct range key on a path is checked, and about half of them match, so each search has many posting lists to merge.
- Thresholds `t` are in [1, 99] over 26 attributes, about 5k distinct range keys in total.
- CNF rules: 1–3 disjunctions × 1–2 predicates. DNF rules: 1 conjunction × 1–3 predicates.
- 1,024 events (all 26 attributes, values in [1, 99]) are built once per trial, and each call picks a random one. Seed is fixed, so before and after index the same rules and see the same events.

## Setup
| | |
|---|---|
| Machine | Apple M1, 8 cores, 16 GB |
| JVM | OpenJDK 17.0.20.1 (Homebrew), default flags |
| JMH | 1.37, Throughput mode, 8 threads, 1 fork, 2 × 10 s warmup, 3 × 10 s measurement, `-prof gc` |
| Raw output | `before.{json,log}`, `after.{json,log}` in this folder |

## How the after version works
- Posting-list cursors are kept in a sorted array, ordered by current entry (id, then type, then order for CNF; id, then type for DNF), with exhausted lists last.
- `sort()` looks up each list's current entry once per sort, instead of up to 6 `TreeMap.get` calls inside every comparison.
- `skipTo(nextID)`: the lists below `nextID` are always a prefix of the array. Only that prefix is advanced (`ceilingEntry`, one tree walk) and sorted. Each moved list is then binary-searched into the untouched sorted suffix and merged in place (`System.arraycopy`).
- The result is identical to the old stable full re-sort; on ties a moved list goes first.

## Execution time and throughput
Throughput is in ops/s (higher is better), with the 3 measurement iterations in brackets. ms/search = 8 threads ÷ throughput.

| Form | Criteria | Before ops/s | After ops/s | Speed-up | ms/search (before → after) |
|---|---:|---|---|---:|---|
| CNF | 1,000 | 338 (349 / 333 / 333) | 2,572 (2,569 / 2,432 / 2,716) | **7.6×** | 23.6 → 3.1 |
| CNF | 10,000 | 6.2 (6.3 / 6.2 / 6.3) | 262 (271 / 259 / 255) | **41.9×** | 1,280.4 → 30.6 |
| DNF | 1,000 | 1,003 (1,010 / 1,000 / 998) | 4,279 (4,209 / 4,260 / 4,370) | **4.3×** | 8.0 → 1.9 |
| DNF | 10,000 | 15.5 (16.5 / 15.7 / 14.3) | 419 (424 / 426 / 406) | **27.0×** | 516.3 → 19.1 |

## Allocation
`gc.alloc.rate.norm` is the bytes allocated per search. GC count and time cover the measurement window (3 × 10 s, 8 threads). GC count is higher after the change only because many more searches complete in the same window. Per search, allocation drops, and GC time stays under 2% of run time.

| Form | Criteria | Before B/op | After B/op | Change | Before GC count / time | After GC count / time |
|---|---:|---:|---:|---:|---|---|
| CNF | 1,000 | 10.52 MB | 3.51 MB | 3.0× less | 319 / 189 ms | 590 / 365 ms |
| CNF | 10,000 | 213.32 MB | 18.34 MB | 11.6× less | 135 / 131 ms | 297 / 290 ms |
| DNF | 1,000 | 2.98 MB | 2.33 MB | 1.3× less | 323 / 175 ms | 658 / 397 ms |
| DNF | 10,000 | 22.82 MB | 13.55 MB | 1.7× less | 40 / 36 ms | 351 / 295 ms |

## Correctness
- `mustang-models` 220/220, `mustang-core` 253/253, `BUILD SUCCESS`.
- `SearchEquivalenceTest` checks search results against a fingerprint recorded on the unpatched build: 2,000 random CNF/DNF criteria and 50 events. It also checks that scan equals brute-force `evaluate()`, and covers ties.
- No change to index layout, scoring or results.

## CPU profile (JFR)

### How it was measured
- Java Flight Recorder (`-prof jfr`), recording the measurement iterations only. Before and after use the same benchmark, settings and seed.
- **CPU per search** = process CPU from `jdk.CPULoad` (about 7.4–7.8 of 8 cores busy) ÷ throughput.
- **Phases:** each CPU sample (`jdk.ExecutionSample`, full stack) is assigned to the first phase method found walking down from the top of its stack:
  - **Cursor ordering:** sorting posting lists and `skipTo`. This is what this change targets.
  - **Key matching:** `getPostingLists` / `getMatchingKeys` / `CaveatEnforcer` (for range keys this includes `RangeDetail.of`).
  - **Matcher checks:** `canContinue`, `sameConjunctionCheck`, `disjunctionEvaluationCheck`, `getCounters` and similar.
  - **Query:** building the query and parsing the request with JsonPath.

### Range rules (`MustangRangeSearchBenchmark`)

| Workload | Build | CPU / search | Cursor ordering | Key matching | Matcher checks | Other |
|---|---|---:|---:|---:|---:|---:|
| CNF 1,000 | Before | 22.11 ms | 19.91 ms | 1,758 µs | 357 µs | 83 µs |
| CNF 1,000 | After (array) | 2,925 µs (7.6× less) | 639 µs | 1,912 µs | 318 µs | 56 µs |
| CNF 10,000 | Before | 1,243.25 ms | 1,223.86 ms | 10.58 ms | 6,660 µs | 2,141 µs |
| CNF 10,000 | After (array) | 28.65 ms (43.4× less) | 13.11 ms | 10.44 ms | 4,226 µs | 874 µs |
| DNF 1,000 | Before | 8,677 µs | 7,224 µs | 1,310 µs | 91 µs | 52 µs |
| DNF 1,000 | After (array) | 1,847 µs (4.7× less) | 331 µs | 1,368 µs | 98 µs | 50 µs |
| DNF 10,000 | Before | 457.00 ms | 444.75 ms | 8,734 µs | 2,066 µs | 1,456 µs |
| DNF 10,000 | After (array) | 17.77 ms (25.7× less) | 6,637 µs | 9,449 µs | 1,249 µs | 437 µs |

### Equality rules (repo's original benchmarks, `values` predicates)
These are the existing `MustangSearchBenchmark`, `MustangOnlyDNFSearchBenchmark` (indexes CNF) and `MustangOnlyCNFSearchBenchmark` (indexes DNF), each with its own JMH settings: 1 × 10 s measurement, so expect more noise. The per-call `prepareContext` setup is excluded.

| Workload | Build | CPU / search | Cursor ordering | Key matching | Matcher checks | Query | Other |
|---|---|---:|---:|---:|---:|---:|---:|
| CNF only 1,000 | Before | 923 µs | 238 µs | 191 µs | 435 µs | 47 µs | 13 µs |
| CNF only 1,000 | After (array) | 699 µs (1.3× less) | 69 µs | 121 µs | 454 µs | 42 µs | 12 µs |
| CNF only 10,000 | Before | 10.48 ms | 3,676 µs | 1,522 µs | 4,497 µs | 705 µs | 77 µs |
| CNF only 10,000 | After (array) | 7,090 µs (1.5× less) | 903 µs | 1,359 µs | 4,163 µs | 600 µs | 65 µs |
| DNF only 1,000 | Before | 150 µs | 10 µs | 112 µs | 8 µs | 12 µs | 7 µs |
| DNF only 1,000 | After (array) | 179 µs (≈ same, within noise) | 7 µs | 132 µs | 12 µs | 17 µs | 10 µs |
| DNF only 10,000 | Before | 1,335 µs | 246 µs | 753 µs | 228 µs | 54 µs | 54 µs |
| DNF only 10,000 | After (array) | 1,262 µs (≈ same, within noise) | 104 µs | 829 µs | 230 µs | 45 µs | 53 µs |
| Mixed 1,000 | Before | 644 µs | 124 µs | 137 µs | 329 µs | 40 µs | 15 µs |
| Mixed 1,000 | After (array) | 637 µs (≈ same, within noise) | 51 µs | 178 µs | 350 µs | 43 µs | 15 µs |
| Mixed 10,000 | Before | 8,067 µs | 2,188 µs | 2,008 µs | 3,271 µs | 498 µs | 103 µs |
| Mixed 10,000 | After (array) | 6,144 µs (1.3× less) | 619 µs | 1,789 µs | 3,188 µs | 417 µs | 131 µs |

### Findings
- **Range rules:** cursor ordering drops from 1,224 ms to 13.1 ms per search (CNF 10k). In the before build, the hottest methods are the comparator chain: `getIdSafely`, `getOrderSafely`, the comparator lambdas and `TreeMap.getEntry`. After the change, the biggest single cost is `PostingListCursors.skipTo` itself (the merge and `arraycopy`).
- **Equality rules:** a request value matches only a few keys, so sorting was a smaller share (7–35%) to begin with. Cursor ordering still drops 2–4×, but the matcher checks are unchanged. CNF's `disjunctionEvaluationCheck` / `sameConjunctionCheck` still do 2 `TreeMap.get` per list (4.5 → 4.2 ms at CNF 10k).
- **Remaining costs, not touched by this change:**
  - **Range keys:** `RangeDetail.of` re-parses each range key string on every check (`Double.parseDouble` and `String.split` are the top frames). Caching parsed bounds would cut the largest remaining chunk.
  - **Equality keys:** `getMatchingKeys` scans every key in the index and builds a `CaveatEnforcer` per key. A direct lookup by attribute and value would avoid the scan.
