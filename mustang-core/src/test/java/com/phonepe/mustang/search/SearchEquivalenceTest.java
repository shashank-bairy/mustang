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
package com.phonepe.mustang.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.phonepe.mustang.MustangEngine;
import com.phonepe.mustang.common.RequestContext;
import com.phonepe.mustang.composition.impl.Conjunction;
import com.phonepe.mustang.composition.impl.Disjunction;
import com.phonepe.mustang.criteria.Criteria;
import com.phonepe.mustang.criteria.impl.CNFCriteria;
import com.phonepe.mustang.criteria.impl.DNFCriteria;
import com.phonepe.mustang.detail.impl.RangeDetail;
import com.phonepe.mustang.predicate.Predicate;
import com.phonepe.mustang.predicate.impl.ExcludedPredicate;
import com.phonepe.mustang.predicate.impl.IncludedPredicate;

/**
 * Guards the matcher performance changes (precomputed sort keys and incremental re-sort in skipTo). Scan must equal
 * brute-force evaluation; search must equal the output recorded on the pre-change build (the index already differs
 * from evaluate() for a few ids on this data, in both directions, so search is pinned to the old results rather than
 * to brute force).
 */
public class SearchEquivalenceTest {

    private static final String INDEX = "equivalence";
    private static final int ATTRIBUTES = 20;
    private static final int CRITERIAS = 2000;
    private static final int EVENTS = 50;

    /* Fingerprint of all search results for the fixed seed below, recorded on the build before the change. */
    private static final int EXPECTED_SEARCH_FINGERPRINT = -733771372;
    private static final int EXPECTED_TOTAL_MATCHES = 26632;

    private final ObjectMapper mapper = new ObjectMapper();
    private MustangEngine engine;

    @Before
    public void setUp() {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        engine = MustangEngine.builder()
                .mapper(mapper)
                .build();
    }

    @Test
    public void testSetNodeIsReflected() {
        final Criteria criteria = CNFCriteria.builder()
                .id("C1")
                .disjunction(Disjunction.builder()
                        .predicate(IncludedPredicate.builder()
                                .lhs("$.a")
                                .detail(RangeDetail.builder()
                                        .lowerBound(10)
                                        .upperBound(20)
                                        .includeLowerBound(true)
                                        .build())
                                .build())
                        .build())
                .build();
        engine.add(INDEX, criteria);

        final RequestContext context = RequestContext.builder()
                .node(mapper.valueToTree(Map.of("a", 15)))
                .build();
        assertThat(engine.evaluate(criteria, context), is(true));
        assertThat(engine.search(INDEX, context), is(Set.of("C1")));

        context.setNode(mapper.valueToTree(Map.of("a", 25)));
        assertThat(engine.evaluate(criteria, context), is(false));
        assertThat(engine.search(INDEX, context), is(Set.of()));
        assertThat(engine.scan(INDEX, context), is(Set.of()));

        context.setNode(mapper.valueToTree(Map.of("a", 10)));
        assertThat(engine.evaluate(criteria, context), is(true));
        assertThat(engine.scan(INDEX, context), is(Set.of("C1")));
    }

    @Test
    public void testSearchUnchangedAndScanMatchesBruteForce() {
        final Random random = new Random(42);
        final List<Criteria> criterias = new ArrayList<>();
        for (int i = 0; i < CRITERIAS; i++) {
            final Criteria criteria = random.nextBoolean() ? randomCnf("C" + i, random) : randomDnf("D" + i, random);
            criterias.add(criteria);
            engine.add(INDEX, criteria);
        }

        final StringBuilder fingerprint = new StringBuilder();
        int totalMatches = 0;
        for (int e = 0; e < EVENTS; e++) {
            final RequestContext context = RequestContext.builder()
                    .node(mapper.valueToTree(randomEvent(random)))
                    .build();
            final Set<String> expected = criterias.stream()
                    .filter(c -> c.evaluate(context))
                    .map(Criteria::getId)
                    .collect(Collectors.toSet());

            final Set<String> searched = engine.search(INDEX, context, false);
            assertThat("scan, event " + e, engine.scan(INDEX, context), is(expected));

            fingerprint.append(new TreeSet<>(searched))
                    .append('|');
            totalMatches += searched.size();
        }
        assertThat(totalMatches, is(EXPECTED_TOTAL_MATCHES));
        assertThat(fingerprint.toString()
                .hashCode(), is(EXPECTED_SEARCH_FINGERPRINT));
    }

    @Test
    public void testTiesInPostingListOrder() {
        /*
         * Every criteria has disjunctions with several same-type predicates over different keys, so many posting lists
         * share the same (id, type, order) and exercise the stable tie-breaking during skipTo.
         */
        final List<Criteria> criterias = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final CNFCriteria.CNFCriteriaBuilder builder = CNFCriteria.builder()
                    .id("T" + i);
            for (int d = 0; d < 1 + (i % 3); d++) {
                final int base = d * 3;
                builder.disjunction(Disjunction.builder()
                        .predicate(IncludedPredicate.builder()
                                .lhs("$.k" + base)
                                .values(Sets.newHashSet(i % 4, (i + 1) % 4))
                                .build())
                        .predicate(IncludedPredicate.builder()
                                .lhs("$.k" + (base + 1))
                                .values(Sets.newHashSet(i % 5))
                                .build())
                        .predicate(i % 2 == 0
                                ? ExcludedPredicate.builder()
                                        .lhs("$.k" + (base + 2))
                                        .values(Sets.newHashSet(i % 3))
                                        .build()
                                : IncludedPredicate.builder()
                                        .lhs("$.k" + (base + 2))
                                        .values(Sets.newHashSet(i % 3, 7))
                                        .build())
                        .build());
            }
            final Criteria criteria = builder.build();
            criterias.add(criteria);
            engine.add(INDEX, criteria);
        }

        for (int v = 0; v < 6; v++) {
            final Map<String, Object> event = Maps.newHashMap();
            for (int k = 0; k < 9; k++) {
                event.put("k" + k, (v + k) % 5);
            }
            final RequestContext context = RequestContext.builder()
                    .node(mapper.valueToTree(event))
                    .build();
            final Set<String> expected = criterias.stream()
                    .filter(c -> c.evaluate(context))
                    .map(Criteria::getId)
                    .collect(Collectors.toSet());
            assertThat("ties, event " + v, engine.search(INDEX, context, false), is(expected));
        }

        final RequestContext all = RequestContext.builder()
                .node(mapper.valueToTree(IntStream.range(0, 9)
                        .boxed()
                        .collect(Collectors.toMap(k -> "k" + k, k -> 1))))
                .build();
        final Set<String> expected = criterias.stream()
                .filter(c -> c.evaluate(all))
                .map(Criteria::getId)
                .collect(Collectors.toSet());
        assertThat(engine.search(INDEX, all, false), containsInAnyOrder(expected.toArray()));
    }

    private Map<String, Object> randomEvent(final Random random) {
        final Map<String, Object> event = Maps.newHashMap();
        for (int a = 0; a < ATTRIBUTES; a++) {
            if (random.nextInt(10) != 0) {
                event.put("a" + a, random.nextInt(10));
            }
        }
        return event;
    }

    private Criteria randomCnf(final String id, final Random random) {
        final CNFCriteria.CNFCriteriaBuilder builder = CNFCriteria.builder()
                .id(id);
        final int disjunctions = 1 + random.nextInt(3);
        for (int d = 0; d < disjunctions; d++) {
            builder.disjunction(Disjunction.builder()
                    .predicates(randomPredicates(random))
                    .build());
        }
        return builder.build();
    }

    private Criteria randomDnf(final String id, final Random random) {
        final DNFCriteria.DNFCriteriaBuilder builder = DNFCriteria.builder()
                .id(id);
        final int conjunctions = 1 + random.nextInt(2);
        for (int c = 0; c < conjunctions; c++) {
            builder.conjunction(Conjunction.builder()
                    .predicates(randomPredicates(random))
                    .build());
        }
        return builder.build();
    }

    private List<Predicate> randomPredicates(final Random random) {
        final List<Integer> attributes = IntStream.range(0, ATTRIBUTES)
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(attributes, random);
        final int count = 1 + random.nextInt(3);
        final List<Predicate> predicates = new ArrayList<>();
        for (int p = 0; p < count; p++) {
            final String lhs = "$.a" + attributes.get(p);
            final int kind = random.nextInt(10);
            if (kind < 5) {
                final int lower = random.nextInt(8);
                predicates.add(IncludedPredicate.builder()
                        .lhs(lhs)
                        .detail(RangeDetail.builder()
                                .lowerBound(lower)
                                .upperBound(lower + 1 + random.nextInt(4))
                                .includeLowerBound(random.nextBoolean())
                                .includeUpperBound(random.nextBoolean())
                                .build())
                        .build());
            } else if (kind < 8) {
                predicates.add(IncludedPredicate.builder()
                        .lhs(lhs)
                        .values(randomValues(random))
                        .build());
            } else {
                predicates.add(ExcludedPredicate.builder()
                        .lhs(lhs)
                        .values(randomValues(random))
                        .build());
            }
        }
        return predicates;
    }

    private Set<Object> randomValues(final Random random) {
        final Set<Object> values = new HashSet<>();
        final int count = 1 + random.nextInt(3);
        for (int v = 0; v < count; v++) {
            values.add(random.nextInt(10));
        }
        return values;
    }
}
