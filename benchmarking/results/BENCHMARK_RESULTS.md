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
