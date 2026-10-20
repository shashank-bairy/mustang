# Mustang search benchmark: before vs after (heap cursors (`CursorHeap`))

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
- All posting-list cursors live in a binary min-heap ordered by current entry (CNF: id, then type, then order; DNF: id, then type), with exhausted lists last and list index as the tie-break.
- The matcher only reads the first few cursors in order (the k-th, and the run sharing the first id). Those are popped lazily into a small sorted "front" buffer.
- The heap caches each cursor's current entry, so the matcher checks (`cursors.id(i)` / `cursors.entry(i)`) never call `TreeMap.get`.
- `skipTo(nextID)`: front cursors below `nextID` (a prefix of the front) are advanced and pushed into the heap. Front cursors that didn't move stay in the front unless an advanced cursor overtakes them. Heap cursors below `nextID` surface at the root and are advanced in place.
- Cost per step is O((moved + returned) log n), with no allocation.

## Execution time and throughput
Throughput is in ops/s (higher is better), with the 3 measurement iterations in brackets. ms/search = 8 threads ÷ throughput.

| Form | Criteria | Before ops/s | After ops/s | Speed-up | ms/search (before → after) |
|---|---:|---|---|---:|---|
| CNF | 1,000 | 338 (349 / 333 / 333) | 2,990 (2,981 / 3,023 / 2,966) | **8.8×** | 23.6 → 2.7 |
| CNF | 10,000 | 6.2 (6.3 / 6.2 / 6.3) | 357 (376 / 365 / 330) | **57.1×** | 1,280.4 → 22.4 |
| DNF | 1,000 | 1,003 (1,010 / 1,000 / 998) | 4,525 (4,589 / 4,577 / 4,410) | **4.5×** | 8.0 → 1.8 |
| DNF | 10,000 | 15.5 (16.5 / 15.7 / 14.3) | 535 (499 / 566 / 539) | **34.5×** | 516.3 → 15.0 |

## Allocation
`gc.alloc.rate.norm` is the bytes allocated per search. GC count and time cover the measurement window (3 × 10 s, 8 threads). GC count is higher after the change only because many more searches complete in the same window. Per search, allocation drops, and GC time stays under 2% of run time.

| Form | Criteria | Before B/op | After B/op | Change | Before GC count / time | After GC count / time |
|---|---:|---:|---:|---:|---|---|
| CNF | 1,000 | 10.52 MB | 3.42 MB | 3.1× less | 319 / 189 ms | 488 / 319 ms |
| CNF | 10,000 | 213.32 MB | 17.52 MB | 12.2× less | 135 / 131 ms | 355 / 365 ms |
| DNF | 1,000 | 2.98 MB | 2.26 MB | 1.3× less | 323 / 175 ms | 582 / 357 ms |
| DNF | 10,000 | 22.82 MB | 12.93 MB | 1.8× less | 40 / 36 ms | 428 / 350 ms |

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
| CNF 1,000 | After (heap) | 2,712 µs (8.2× less) | 549 µs | 1,910 µs | 197 µs | 57 µs |
| CNF 10,000 | Before | 1,243.25 ms | 1,223.86 ms | 10.58 ms | 6,660 µs | 2,141 µs |
| CNF 10,000 | After (heap) | 21.78 ms (57.1× less) | 8,414 µs | 9,874 µs | 2,820 µs | 670 µs |
| DNF 1,000 | Before | 8,677 µs | 7,224 µs | 1,310 µs | 91 µs | 52 µs |
| DNF 1,000 | After (heap) | 1,749 µs (5.0× less) | 275 µs | 1,364 µs | 75 µs | 36 µs |
| DNF 10,000 | Before | 457.00 ms | 444.75 ms | 8,734 µs | 2,066 µs | 1,456 µs |
| DNF 10,000 | After (heap) | 14.28 ms (32.0× less) | 4,170 µs | 8,432 µs | 1,272 µs | 407 µs |

### Equality rules (repo's original benchmarks, `values` predicates)
These are the existing `MustangSearchBenchmark`, `MustangOnlyDNFSearchBenchmark` (indexes CNF) and `MustangOnlyCNFSearchBenchmark` (indexes DNF), each with its own JMH settings: 1 × 10 s measurement, so expect more noise. The per-call `prepareContext` setup is excluded.

| Workload | Build | CPU / search | Cursor ordering | Key matching | Matcher checks | Query | Other |
|---|---|---:|---:|---:|---:|---:|---:|
| CNF only 1,000 | Before | 923 µs | 238 µs | 191 µs | 435 µs | 47 µs | 13 µs |
| CNF only 1,000 | After (heap) | 492 µs (1.9× less) | 114 µs | 121 µs | 203 µs | 46 µs | 8 µs |
| CNF only 10,000 | Before | 10.48 ms | 3,676 µs | 1,522 µs | 4,497 µs | 705 µs | 77 µs |
| CNF only 10,000 | After (heap) | 4,423 µs (2.4× less) | 1,197 µs | 669 µs | 1,933 µs | 563 µs | 61 µs |
| DNF only 1,000 | Before | 150 µs | 10 µs | 112 µs | 8 µs | 12 µs | 7 µs |
| DNF only 1,000 | After (heap) | 155 µs (≈ same, within noise) | 6 µs | 126 µs | 5 µs | 12 µs | 6 µs |
| DNF only 10,000 | Before | 1,335 µs | 246 µs | 753 µs | 228 µs | 54 µs | 54 µs |
| DNF only 10,000 | After (heap) | 1,036 µs (1.3× less) | 106 µs | 762 µs | 117 µs | 41 µs | 10 µs |
| Mixed 1,000 | Before | 644 µs | 124 µs | 137 µs | 329 µs | 40 µs | 15 µs |
| Mixed 1,000 | After (heap) | 414 µs (1.6× less) | 64 µs | 172 µs | 136 µs | 34 µs | 8 µs |
| Mixed 10,000 | Before | 8,067 µs | 2,188 µs | 2,008 µs | 3,271 µs | 498 µs | 103 µs |
| Mixed 10,000 | After (heap) | 4,225 µs (1.9× less) | 983 µs | 1,516 µs | 1,315 µs | 389 µs | 23 µs |

### Findings
- **Range rules:** cursor ordering drops from 1,224 ms to 8.4 ms per search (CNF 10k). In the before build, the hottest methods are the comparator chain: `getIdSafely`, `getOrderSafely`, the comparator lambdas and `TreeMap.getEntry`. After the change, the cost is spread over `CursorHeap.advance` and `less`.
- **Equality rules:** a request value matches only a few keys, so sorting was a smaller share (7–35%) to begin with. The heap's main gain here is the matcher checks: `cursors.entry(i)` reads the cached entry instead of doing 2 `TreeMap.get` per list in `disjunctionEvaluationCheck` / `sameConjunctionCheck` (4.5 → 1.9 ms at CNF 10k).
- These profiles were recorded before the peek change in `CursorHeap.cursorAt` (the next rank is read from the heap root without popping). That change benchmarked neutral, within ±5%.
- **Remaining costs, not touched by this change:**
  - **Range keys:** `RangeDetail.of` re-parses each range key string on every check (`Double.parseDouble` and `String.split` are the top frames). Caching parsed bounds would cut the largest remaining chunk.
  - **Equality keys:** `getMatchingKeys` scans every key in the index and builds a `CaveatEnforcer` per key. A direct lookup by attribute and value would avoid the scan.
