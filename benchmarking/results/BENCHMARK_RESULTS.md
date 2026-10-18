# Mustang benchmark results: before and after the perf fixes

> **Update:** fixes 1 and 2 were later reverted. The code now has **only 3a and 3b, applied to both `CNFMatcher` and `DNFMatcher`** (see section 6). Sections 1–4 below are from the run with all four fixes. Section 5 is the range-predicate run with 3a and 3b only.

Fixes applied from `MUSTANG_PERF_AGENT_GUIDE.md`: 1 (parse the request JSON once per `RequestContext`, cache compiled JsonPaths), 2 (cache parsed range bounds), 3a (precomputed sort keys in the CNF/DNF matchers), 3b (incremental re-sort in `CNFMatcher.skipTo`).
Not applied (marked optional in the guide): regex `Pattern` cache, DNF `skipTo` incremental re-sort.

## Setup

| | |
|---|---|
| Machine | Apple M1, 8 cores, 16 GB |
| JVM | OpenJDK 17.0.20.1 (Homebrew), default flags |
| Harness | JMH 1.37, `benchmarking/` module unchanged, `java -jar target/benchmarks.jar` |
| JMH config (from the annotations) | Throughput mode, 8 threads, 1 warmup fork + 1 measured fork, 1 × 10 s warmup, 1 × 10 s measurement |
| Mustang | `3.0.0-SNAPSHOT` @ `97235e5` (baseline) and the same commit plus the patch (after) |
| Raw output | `baseline.json` / `baseline.log`, `after.json` / `after.log` in this folder |

Each benchmark has only **one measured iteration**, so JMH reports no error bars. Treat differences under roughly ±10% as noise.

> Naming note: in the repo, `MustangOnlyCNFSearchBenchmark` indexes **DNF** criteria and `MustangOnlyDNFSearchBenchmark` indexes **CNF** criteria. The README has the same swap. The tables use the class names and add the real index contents.

## 1. Baseline (before the fixes)

Throughput in ops/s (higher is better). Latency is derived as `8 threads / throughput`, in µs per search.

| Benchmark | Index contents | indexSize | ops/s | µs/op |
|---|---|---:|---:|---:|
| MustangOnlyCNFSearchBenchmark | DNF only | 10 | 650,555 | 12.3 |
| | | 100 | 257,165 | 31.1 |
| | | 1,000 | 36,771 | 217.6 |
| | | 10,000 | 4,556 | 1,755.8 |
| MustangOnlyDNFSearchBenchmark | CNF only | 10 | 397,292 | 20.1 |
| | | 100 | 97,193 | 82.3 |
| | | 1,000 | 8,332 | 960.2 |
| | | 10,000 | 714 | 11,200.3 |
| MustangSearchBenchmark | CNF + DNF (2 × indexSize) | 10 | 478,270 | 16.7 |
| | | 100 | 131,067 | 61.0 |
| | | 1,000 | 11,745 | 681.2 |
| | | 10,000 | 965 | 8,291.7 |

## 2. After the fixes

| Benchmark | Index contents | indexSize | Before ops/s | After ops/s | Speed-up | Before µs/op | After µs/op |
|---|---|---:|---:|---:|---:|---:|---:|
| MustangOnlyCNFSearchBenchmark | DNF only | 10 | 650,555 | 665,103 | 1.02× | 12.3 | 12.0 |
| | | 100 | 257,165 | 251,991 | 0.98× (noise) | 31.1 | 31.7 |
| | | 1,000 | 36,771 | 40,298 | 1.10× | 217.6 | 198.5 |
| | | 10,000 | 4,556 | 5,169 | **1.13×** | 1,755.8 | 1,547.8 |
| MustangOnlyDNFSearchBenchmark | CNF only | 10 | 397,292 | 565,055 | 1.42× | 20.1 | 14.2 |
| | | 100 | 97,193 | 115,982 | 1.19× | 82.3 | 69.0 |
| | | 1,000 | 8,332 | 10,690 | 1.28× | 960.2 | 748.3 |
| | | 10,000 | 714 | 1,017 | **1.42×** | 11,200.3 | 7,865.0 |
| MustangSearchBenchmark | CNF + DNF | 10 | 478,270 | 568,574 | 1.19× | 16.7 | 14.1 |
| | | 100 | 131,067 | 167,081 | 1.27× | 61.0 | 47.9 |
| | | 1,000 | 11,745 | 12,617 | 1.07× | 681.2 | 634.1 |
| | | 10,000 | 965 | 1,201 | **1.24×** | 8,291.7 | 6,662.6 |

## 3. Reading the numbers

- **CNF search is up to 1.4× faster at 10k criteria, and mixed search is up 1.24×.** This is where fixes 3a and 3b apply: `skipTo` no longer re-sorts every posting list on every step, and the sort comparators no longer call `TreeMap.get` inside each comparison.
- **DNF-only search moves only about 1.1×.** For DNF only fix 3a applies (DNF `skipTo` was left as it was), and the DNF comparator did just 2 lookups per comparison to begin with.
- **The gain is smaller than the guide's 11× (search) and 70× (scan).** That is expected, because this harness does not exercise the costs the guide profiled:
  - It measures only `search`. The guide's biggest win (fix 1, about 97% of `scan`) speeds up per-predicate evaluation in `scan`/`evaluate`/`debug`/scoring. In `search`, the request JSON was parsed only once per call even before the change.
  - It uses only `IncludedPredicate`/`ExcludedPredicate` with equality values, no `RangeDetail`, so fix 2 never runs.
  - Criteria are small (1–2 predicates per disjunction, 1–3 per conjunction) over 26 attributes, with ~100 values each. That gives few matching posting lists per k, so the `O(n log n)`-per-step re-sort that fix 3b removes is cheap here. The guide's workload was 20k criteria, 411 range keys and 45% matching.
  - `@Setup(Level.Invocation)` builds a fresh `RequestContext` (with `valueToTree`) before every call. At the small index sizes that setup cost and timer overhead are a large share of each measurement.
- To see the scan/evaluate gain, add a `scan` benchmark with range predicates, as described in the guide's §6.3.

## 4. Correctness checks

- `mvn -pl mustang-models,mustang-core -am install`: models **220/220**, core **253/253** (the 250 existing tests plus 3 new ones), `BUILD SUCCESS`.
- `mvn -pl mustang-dw-bundle -am install`: dw-bundle **8/8**. Jackson deserialization of `RequestContext` still works because `@ConstructorProperties({"node"})` is kept on the new explicit constructor. Lombok used to add it automatically through `lombok.config`.
- New `mustang-core/src/test/java/com/phonepe/mustang/search/SearchEquivalenceTest.java`:
  - `testContextCacheInvalidatedOnSetNode`: `setNode` invalidates the cached parse, for evaluate, search and scan.
  - `testSearchUnchangedAndScanMatchesBruteForce`: 2,000 random CNF/DNF criteria over 20 numeric attributes (range, equality and excluded predicates), 50 events.
    - `scan` must equal brute-force `evaluate`.
    - `search` must equal a fingerprint (hash of all sorted result sets plus the total count, 26,632 matches) **recorded on the unpatched build**.
    - Search is pinned to the old output, not to brute force, because the unpatched index already differs from `evaluate()` for a few ids in both directions on this data. That is existing behaviour and is left unchanged.
  - `testTiesInPostingListOrder`: many posting lists share the same (id, type, order), which exercises the stable tie-breaking in the new `skipTo`.

## 5. Range predicates (`<` / `>`) with fixes 3a and 3b only

### Why this workload
With equality predicates, the request value maps straight to one index key. A numeric range key can't be looked up that way. Every distinct range key on a path is checked against the value, and here about half of them match. That gives many posting lists per search, which is the merge-and-sort work that 3a and 3b reduce.

### Benchmark: `MustangRangeSearchBenchmark` (new)
- Every predicate is `IncludedPredicate` with a `RangeDetail`: either `$.x > t` (`lowerBound`, exclusive) or `$.x < t` (`upperBound`, exclusive).
- Thresholds `t` are in [1, 99]. There are 26 attributes, so about 5k distinct range keys in total.
- CNF rules: 1–3 disjunctions × 1–2 range predicates each. DNF rules: 1 conjunction × 1–3 range predicates.
- Events have all 26 attributes, with values in [1, 99]. 1,024 events are generated once per trial, and each call picks a random one. There is no per-call setup.
- Seed is fixed, so both builds index the same rules and see the same events.
- JMH settings: Throughput mode, 8 threads, 1 fork, 2 × 10 s warmup, 3 × 10 s measurement.
- Before = `97235e5` unpatched. After = 3a + 3b. I checked that only the after jar contains `CNFMatcher$CnfSortKey`.
- Raw results: `range-before.{json,log}`, `range-after.{json,log}`.

### Results

| Form | indexSize | Before ops/s (3 iterations) | After ops/s (3 iterations) | Speed-up | Before ms/search | After ms/search |
|---|---:|---|---|---:|---:|---:|
| CNF | 1,000 | 341.5 (359.9 / 358.4 / 306.1) | 2,580.8 (2,563.8 / 2,711.8 / 2,466.7) | **7.6×** | 23.4 | 3.1 |
| CNF | 10,000 | 6.6 (6.7 / 6.8 / 6.4) | 282.1 (278.0 / 281.7 / 286.6) | **42.6×** | 1,206.9 | 28.4 |
| DNF | 1,000 | 942.6 (957.9 / 933.8 / 936.2) | 856.3 (857.2 / 857.7 / 854.1) | 0.91× | 8.5 | 9.3 |
| DNF | 10,000 | 17.3 (17.5 / 17.3 / 17.2) | 15.5 (15.4 / 15.6 / 15.5) | 0.89× | 461.6 | 515.9 |

ms/search = 8 threads ÷ throughput × 1000. JMH's 99.9% error bars are wide with only 3 samples, so I show the individual iterations instead; they are tightly grouped.

### Reading the numbers
- **CNF range search: 7.6× faster at 1k rules, 42.6× at 10k rules.** At 10k rules, one search went from ~1.2 s to ~28 ms.
  - The old `skipTo` re-sorted every matching posting list (thousands of them) after each step, with up to 6 `TreeMap.get` calls per comparison. That was `O(steps × n log n)` lookups.
  - Now only the lists that moved are sorted, and each is binary-searched into the rest (3b). Each list's entry is looked up once per sort (3a).
  - The gain grows with index size because both the number of lists and the number of steps grow.
- **DNF range search: about 10% slower, consistently.** DNF received only 3a, not the 3b-style `skipTo` change.
  - `DNFMatcher.skipTo` still re-sorts every list after each step.
  - That array is almost sorted, so TimSort needs only about `n` comparisons, i.e. about `2n` `TreeMap.get` calls. Precomputing keys doesn't save much there.
  - 3a adds an allocation of `n` key objects plus a copy-back on every sort, which likely costs more than the lookups it saves.
- **DNF is now the slow path:** about 0.5 s per search at 10k range rules, against 28 ms for CNF. The guide's optional follow-up (apply the 3b incremental re-sort to `DNFMatcher.skipTo`) should give DNF a similar large gain.

### Correctness (3a + 3b only)
- models 220/220, core 253/253 (including `SearchEquivalenceTest`, whose search fingerprint was recorded on the unpatched build), `BUILD SUCCESS`.

## 6. Range predicates with 3a and 3b in both CNF and DNF (current code)

The 3b incremental re-sort is now also in `DNFMatcher.skipTo`. The same precondition holds there:
- `ConjunctionPostingEntry.compareTo` orders by `iId` first, then `type`, with null entries last.
- Each posting map is keyed by `iId`.
- `setLeft` is only called in `initializeCurrentEntriesDNF` and `skipTo`.

So the lists to advance are always a prefix of the array. Same benchmark, settings and unpatched baseline as section 5. Raw results: `range-after-dnf3b.{json,log}`.

| Form | indexSize | Before ops/s | After ops/s (3 iterations) | Speed-up | Before ms/search | After ms/search |
|---|---:|---:|---|---:|---:|---:|
| CNF | 1,000 | 341.5 | 3,021.7 (3,082.9 / 3,015.7 / 2,966.6) | **8.8×** | 23.4 | 2.6 |
| CNF | 10,000 | 6.6 | 276.2 (269.6 / 274.5 / 284.5) | **41.7×** | 1,206.9 | 29.0 |
| DNF | 1,000 | 942.6 | 4,395.9 (4,459.7 / 4,354.0 / 4,374.0) | **4.7×** | 8.5 | 1.8 |
| DNF | 10,000 | 17.3 | 460.0 (462.7 / 462.3 / 455.0) | **26.5×** | 461.6 | 17.4 |

- **DNF: the ~10% loss from 3a alone is now a 4.7× gain at 1k rules and 26.5× at 10k rules.** At 10k rules, one search dropped from ~0.46 s to ~17 ms.
- **CNF code is unchanged since section 5.** The 1k figure (2,581 then, 3,022 now) gives an idea of run-to-run variance between separate JVM runs, roughly ±15%. The 10k figure matches within 2%.
- Tests: models 220/220, core 253/253, `BUILD SUCCESS`. `SearchEquivalenceTest` covers about 1,000 random DNF rules (half range predicates) against the search fingerprint recorded on the unpatched build.

## 7. After refactor: shared `PostingListCursors` helper (current code)

The CNF and DNF matchers now share one package-private helper, `PostingListCursors`, for the stable sort and the incremental `skipTo` merge. Each matcher passes only its own `POSTING_ORDER` comparator. Two small optimizations came with it:
- `skipTo` uses `ceilingEntry` (one tree walk for both the new id and its entry).
- The binary search no longer allocates a sort key on each probe.

Same benchmark and settings as sections 5 and 6. Raw results: `range-after-refactor.{json,log}`.

| Form | indexSize | Before (unpatched) ops/s | Section 6 ops/s | After refactor ops/s (3 iterations) | vs unpatched | vs section 6 | ms/search |
|---|---:|---:|---:|---|---:|---:|---:|
| CNF | 1,000 | 341.5 | 3,021.7 | 3,021.2 (3,126.5 / 2,985.4 / 2,951.7) | **8.8×** | 1.00× | 2.6 |
| CNF | 10,000 | 6.6 | 276.2 | 299.5 (304.8 / 298.2 / 295.4) | **45.2×** | 1.08× | 26.7 |
| DNF | 1,000 | 942.6 | 4,395.9 | 4,400.2 (4,201.9 / 4,477.6 / 4,521.2) | **4.7×** | 1.00× | 1.8 |
| DNF | 10,000 | 17.3 | 460.0 | 436.3 (443.2 / 447.0 / 418.7) | **25.2×** | 0.95× | 18.3 |

- **The refactor doesn't change performance.** The differences from section 6 are +8% and −5%, inside the roughly ±15% run-to-run variance seen between separate JVM runs.
- Tests: models 220/220, core 253/253, `BUILD SUCCESS`.

## 8. Sorted array vs heap for the posting-list cursors

### What was compared
- **Sorted array (current code, `PostingListCursors`):**
  - The cursor array is kept fully sorted.
  - On each `skipTo`, the lists to advance are a prefix of the array. Only that prefix is sorted, then each moved list is binary-searched into the untouched suffix, which is shifted with `System.arraycopy`.
- **Heap (experiment, `CursorHeap`; built in a scratch copy of the repo, not in the working tree):**
  - All cursors sit in a binary min-heap keyed by current entry, with the list index as tie-break.
  - The matcher only reads the first few cursors in order (the k-th, and the run of lists sharing the first id). Those are popped lazily into a small sorted "front" buffer.
  - On `skipTo`, the front goes back into the heap. Every cursor below `nextID` surfaces at the root and is advanced in place, then sifted down.
  - Cost per step is O((front + moved) · log n), with no array shifting.
  - The matchers had to read cursors through `pLists.get(i)` instead of indexing the array.
- Both pass the full suite (models 220/220, core 253/253), including the `SearchEquivalenceTest` fingerprint recorded on the unpatched build. Tie order among equal entries does not change results: tied lists share the same id, type and order, and the matcher counts the whole run either way.
- Same benchmark and settings as section 5. The two jars ran back to back in one session. Raw results: `range-array.{json,log}`, `range-heap.{json,log}`.

### Results (ops/s, 3 iterations each)

| Form | indexSize | Unpatched | Sorted array | Heap | Heap vs array | ms/search (array → heap) |
|---|---:|---:|---|---|---:|---|
| CNF | 1,000 | 341.5 | 2,813.5 (2,987.1 / 2,647.4 / 2,806.1) | 2,514.6 (2,232.1 / 2,632.4 / 2,679.3) | 0.89× | 2.8 → 3.2 |
| CNF | 10,000 | 6.6 | 299.7 (299.9 / 301.7 / 297.6) | 293.8 (295.0 / 301.1 / 285.4) | 0.98× | 26.7 → 27.2 |
| DNF | 1,000 | 942.6 | 4,555.3 (4,571.7 / 4,550.2 / 4,544.0) | 4,611.3 (4,566.3 / 4,638.6 / 4,628.9) | 1.01× | 1.8 → 1.7 |
| DNF | 10,000 | 17.3 | 479.0 (481.4 / 478.0 / 477.8) | 538.8 (507.1 / 554.7 / 554.5) | **1.12×** | 16.7 → 14.8 |

### Reading the numbers
- **The two approaches are about even.** Both are 5–45× faster than the unpatched build, so most of the gain comes from no longer re-sorting every list on every step, not from the exact data structure.
- **The heap is ahead only for DNF at 10k (+12%).** DNF conjunctions advance many lists per step, so the array version shifts large blocks, which the heap avoids.
- **The heap is a little behind for CNF at 1k (−11%).** The iterations overlap partly. With few lists, `arraycopy` is very cheap, and the heap pays for extra comparisons on every push and pop.
- **CNF at 10k and DNF at 1k are equal within noise.**
- **What dominates now:** in both versions, `TreeMap` lookups (`get` and `ceilingEntry`) and comparisons take most of the time. The next real gain is the guide's §8 idea: posting lists as `int[]`, with each cursor's current entry stored next to it.

### Recommendation
Keep the **sorted array**.
- **Same speed:** throughput is within ±12% of the heap in all four cases.
- **Simpler change:** the matchers keep indexing a plain array, and the diff is smaller.
- **Same order as the old full sort:** it reproduces the old stable order exactly, rather than relying on ties not affecting results.

Revisit the heap only if production indexes are DNF-heavy with many lists that advance per step.

## 9. Allocations: unpatched vs sorted array vs heap

Measured with JMH's GC profiler (`-prof gc`) on the same range benchmark: 1 × 10 s warmup and 2 × 10 s measurement, which is shorter than earlier sections, so ops/s here is only a rough guide. `B/op` is bytes allocated per search, averaged over all 8 threads. Raw results: `alloc-{before,array,heap}.{json,log}`.

| Form | indexSize | Unpatched B/op | Array B/op | Heap B/op | Array vs unpatched | Heap vs array |
|---|---:|---:|---:|---:|---:|---:|
| CNF | 1,000 | 10.5 MB | 3.51 MB | 3.42 MB | 3.0× less | 2.7% less |
| CNF | 10,000 | **213.0 MB** | 18.3 MB | 17.5 MB | **11.6× less** | 4.5% less |
| DNF | 1,000 | 2.98 MB | 2.33 MB | 2.26 MB | 1.3× less | 3.0% less |
| DNF | 10,000 | 22.8 MB | 13.6 MB | 12.9 MB | 1.7× less | 4.6% less |

GC time over the measurement window was 28–286 ms across all runs, out of 20 s × 8 threads, so under 2% everywhere. Allocation rate (MB/s) is higher after the change only because more searches finish per second.

### Reading the numbers
- **The unpatched code allocated the most, mainly from the full re-sort on every step.**
  - `Arrays.sort` on n > 32 objects allocates a TimSort temp array of up to n/2 references on every call.
  - The old `skipTo` built an `IntStream` pipeline every step.
  - At CNF with 10k rules that adds up to ~213 MB per search.
  - Sorting only the moved prefix removes almost all of it.
- **Heap vs array: only 3–5% fewer bytes with the heap**, about 0.1–0.8 MB per search.
  - That difference is the array version's per-step `Cursor[m]` array, the m `Cursor` records and the comparator lambda.
  - The heap avoids these by storing `int` indexes, and preallocates its three arrays once per k-level.
- **Most remaining allocation is outside the cursor code and the same in both versions:**
  - `getPostingLists` (a `HashMap` plus one `MutablePair` per matching key)
  - one `CaveatEnforcer` per key checked
  - `RangeDetail.of` re-parsing range keys (fix 2, now reverted)
  - `getCounters` (a stream plus an `Integer[]`)
  - `TreeMap.ceilingEntry` returning a new `SimpleImmutableEntry` for each advanced list (both versions)
- **Conclusion:** allocation does not change the section 8 recommendation. If allocation matters, these have far more effect than switching to a heap:
  - Re-apply fix 2 (range-key cache): removes the per-check `split` and the `RangeDetail` objects.
  - Reuse a single `CaveatEnforcer`, or check range keys without one.
  - Reuse a scratch `Cursor[]` in the array version.
  - Use `ceilingKey` instead of `ceilingEntry`.
