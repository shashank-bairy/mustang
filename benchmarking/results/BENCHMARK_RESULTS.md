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
