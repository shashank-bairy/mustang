/**
 * Copyright (c) 2022 PhonePe India Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.phonepe.mustang.search.matcher;

import com.google.common.collect.Maps;
import com.phonepe.mustang.criteria.Criteria;
import com.phonepe.mustang.index.core.DisjunctionPostingEntry;
import com.phonepe.mustang.index.core.Key;
import com.phonepe.mustang.index.core.impl.CNFInvertedIndex;
import com.phonepe.mustang.predicate.PredicateType;
import com.phonepe.mustang.preoperation.ChainOperator;
import com.phonepe.mustang.search.Query;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.tuple.MutablePair;

@Data
@Builder
@AllArgsConstructor
public class CNFMatcher {
    private final CNFInvertedIndex<DisjunctionPostingEntry> invertedIndex;
    private final Query query;
    private final Map<String, Criteria> allCriterias;
    private final Map<String, Object> pathValues;
    private final boolean score;

    public Map<String, Double> getMatches() {
        final Map<String, Double> result = Maps.newHashMap();
        final Map<Integer, Map<Key, TreeMap<Integer, DisjunctionPostingEntry>>> table = invertedIndex.getTable();
        final Map<Integer, Integer[]> disjunctionCounters = invertedIndex.getDisjunctionCounters();
        final int start = 0;
        final int end = table.keySet()
                .stream()
                .mapToInt(x -> x)
                .max()
                .orElse(0);
        IntStream.rangeClosed(start, end)
                .map(i -> end - i + start)
                .boxed()
                .forEach(k -> {
                    final TreeSet<Integer> links = invertedIndex.getLinkages()
                            .get(k);
                    final CursorHeap<DisjunctionPostingEntry> cursors = new CursorHeap<>(getPostingListsCNF(table, k),
                            PostingOrder.CNF);
                    /* Processing k = 0 and k = 1 are identical */
                    if (k == 0) {
                        k = 1;
                    }
                    if (cursors.size() < k) {
                        /* Too few posting lists for any conjunction to be satisfied */
                        return;
                    }
                    int nextID = 0;
                    while (canContinue(cursors, k)) {
                        /*
                         * Check if the first k posting lists have the same conjunction ID in their
                         * current entries
                         */
                        if (sameConjunctionCheck(cursors, k - 1)) {
                            /*
                             * For each disjunction in the current CNF, one counter is initialized to the
                             * negative number of EXCLUDED predicates
                             */
                            final Integer[] counters = getCounters(disjunctionCounters, cursors.id(0));

                            disjunctionEvaluationCheck(result, cursors, k, counters);

                            /* nextID is the smallest possible ID after current ID */
                            nextID = getNextHigherId(links, cursors.id(k - 1));

                        } else {
                            /* Skip first k-1 posting lists */
                            nextID = getNextId(links, cursors.id(k - 1), nextID);
                        }
                        cursors.skipTo(nextID);
                    }
                });

        return result;

    }

    @SuppressWarnings("unchecked")
    private Map.Entry<Key, MutablePair<Integer, TreeMap<Integer, DisjunctionPostingEntry>>>[] getPostingListsCNF(
            final Map<Integer, Map<Key, TreeMap<Integer, DisjunctionPostingEntry>>> table,
            final int k) {
        final Map<Key, TreeMap<Integer, DisjunctionPostingEntry>> map = table.getOrDefault(k, Collections.emptyMap());
        return getMatchingKeys(map).collect(Collectors.toMap(x -> x, x -> MutablePair.of(0, map.get(x))))
                .entrySet()
                .toArray(Entry[]::new);
    }

    private Stream<Key> getMatchingKeys(final Map<Key, TreeMap<Integer, DisjunctionPostingEntry>> map) {
        return map.keySet()
                .stream()
                .filter(key -> key.getCaveat()
                        .visit(new CaveatEnforcer(key,
                                ChainOperator.operate(key.getPreOps(), pathValues.get(key.getName())))));
    }

    private boolean canContinue(final CursorHeap<DisjunctionPostingEntry> cursors, final int k) {
        // an entry exists only for a non-exhausted cursor
        return Objects.nonNull(cursors.entry(k - 1));
    }

    private boolean sameConjunctionCheck(final CursorHeap<DisjunctionPostingEntry> cursors, final Integer k) {
        return Objects.nonNull(cursors.entry(0))
                && Objects.nonNull(cursors.entry(k))
                && cursors.id(0)
                        .equals(cursors.id(k));
    }

    private void disjunctionEvaluationCheck(final Map<String, Double> result,
            final CursorHeap<DisjunctionPostingEntry> cursors,
            final Integer k,
            final Integer[] counters) {
        for (int l = 0; ((l < cursors.size()) && sameConjunctionCheck(cursors, l)); l++) {
            /* Ignore entries in the Z posting list */
            final DisjunctionPostingEntry disjunctionPostingEntry = cursors.entry(l);
            if (Objects.isNull(disjunctionPostingEntry) || disjunctionPostingEntry.getOrder() == -1) {
                continue;
            }
            if (PredicateType.EXCLUDED.equals(disjunctionPostingEntry.getType())) {
                counters[disjunctionPostingEntry.getOrder()]++;
            } else {
                /* Disjunction is satisfied */
                counters[disjunctionPostingEntry.getOrder()] = 1;
            }
        }
        if (Arrays.stream(counters)
                .allMatch(i -> i != 0)) {
            final DisjunctionPostingEntry disjunctionPostingEntry = cursors.entry(k - 1);
            if (Objects.nonNull(disjunctionPostingEntry)) {
                checkAndAdd(result, disjunctionPostingEntry);
            }
        }
    }

    private void checkAndAdd(final Map<String, Double> result, final DisjunctionPostingEntry postingEntry) {
        // Check to see if the current entry represents criteria's latest version.
        if (invertedIndex.getInternalIdFromCache(postingEntry.getEId())
                .equals(postingEntry.getIId())) {
            result.put(postingEntry.getEId(), computeScore(postingEntry.getEId()));
        }
    }

    private double computeScore(final String cId) {
        if (score) {
            return allCriterias.get(cId)
                    .getScore(query.getRequestContext());
        }
        return 0;
    }

    private int getNextHigherId(final TreeSet<Integer> links, final Integer internalId) {
        return Optional.ofNullable(links.higher(internalId))
                .orElse(internalId + 1);
    }

    private int getNextId(final TreeSet<Integer> links, final Integer internalId, final int nextId) {
        if (nextId != internalId) {
            return internalId;
        }
        return getNextHigherId(links, internalId);
    }

    private Integer[] getCounters(final Map<Integer, Integer[]> disjunctionCounters, final int iId) {
        return Arrays.stream(disjunctionCounters.get(iId))
                .map(x -> -1 * x)
                .toArray(Integer[]::new);
    }

}
