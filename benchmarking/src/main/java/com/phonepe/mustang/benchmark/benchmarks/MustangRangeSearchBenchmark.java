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
package com.phonepe.mustang.benchmark.benchmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.phonepe.mustang.MustangEngine;
import com.phonepe.mustang.common.RequestContext;
import com.phonepe.mustang.composition.impl.Conjunction;
import com.phonepe.mustang.composition.impl.Disjunction;
import com.phonepe.mustang.criteria.Criteria;
import com.phonepe.mustang.criteria.impl.CNFCriteria;
import com.phonepe.mustang.criteria.impl.DNFCriteria;
import com.phonepe.mustang.detail.impl.RangeDetail;
import com.phonepe.mustang.predicate.Predicate;
import com.phonepe.mustang.predicate.impl.IncludedPredicate;
import com.phonepe.mustang.benchmark.utils.Utils;

import lombok.Getter;

/**
 * Search over criteria made only of numeric {@code <} / {@code >} predicates. Unlike equality, a range key cannot be
 * looked up directly from the request value: every distinct range key on a path is checked against the value, and a
 * large share of posting lists match, which stresses the matcher's merge loop.
 */
public class MustangRangeSearchBenchmark {

    private static final int EVENTS = 1024;

    @Getter
    @State(Scope.Benchmark)
    public static class BenchmarkContext {
        @Param({ "1000", "10000" })
        private int indexSize;

        @Param({ "CNF", "DNF" })
        private String form;

        private final ObjectMapper mapper = new ObjectMapper();
        private MustangEngine engine;
        private RequestContext[] requestContexts;

        @Setup(Level.Trial)
        public void setUp() {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            engine = MustangEngine.builder()
                    .mapper(mapper)
                    .build();

            // fixed seed so before/after runs index the same criteria and see the same events
            final Random random = new Random(7);
            for (int i = 0; i < indexSize; i++) {
                engine.add(Utils.INDEX_NAME, "CNF".equals(form) ? cnf("C" + i, random) : dnf("D" + i, random));
            }

            requestContexts = new RequestContext[EVENTS];
            for (int e = 0; e < EVENTS; e++) {
                final Map<String, Object> event = Maps.newHashMap();
                // values in [1, 100): RangeDetail's default lower bound is Double.MIN_VALUE, which excludes 0
                Utils.PATHS.forEach(path -> event.put(path, 1 + random.nextInt(99)));
                requestContexts[e] = RequestContext.builder()
                        .node(mapper.valueToTree(event))
                        .build();
            }
        }

        private Criteria cnf(final String id, final Random random) {
            final CNFCriteria.CNFCriteriaBuilder builder = CNFCriteria.builder()
                    .id(id);
            final int disjunctions = 1 + random.nextInt(3);
            for (int d = 0; d < disjunctions; d++) {
                builder.disjunction(Disjunction.builder()
                        .predicates(rangePredicates(random, 1 + random.nextInt(2)))
                        .build());
            }
            return builder.build();
        }

        private Criteria dnf(final String id, final Random random) {
            return DNFCriteria.builder()
                    .id(id)
                    .conjunction(Conjunction.builder()
                            .predicates(rangePredicates(random, 1 + random.nextInt(3)))
                            .build())
                    .build();
        }

        private List<Predicate> rangePredicates(final Random random, final int count) {
            final List<String> paths = new ArrayList<>(Utils.PATHS);
            Collections.shuffle(paths, random);
            return paths.subList(0, count)
                    .stream()
                    .map(path -> {
                        final int threshold = 1 + random.nextInt(99);
                        final RangeDetail detail = random.nextBoolean()
                                ? RangeDetail.builder() // $.path > threshold
                                        .lowerBound(threshold)
                                        .build()
                                : RangeDetail.builder() // $.path < threshold
                                        .upperBound(threshold)
                                        .build();
                        return (Predicate) IncludedPredicate.builder()
                                .lhs("$." + path)
                                .detail(detail)
                                .build();
                    })
                    .collect(Collectors.toList());
        }

        public RequestContext nextContext() {
            return requestContexts[ThreadLocalRandom.current()
                    .nextInt(EVENTS)];
        }
    }

    @Benchmark
    @Fork(value = 1)
    @Warmup(iterations = 2, time = 10)
    @Measurement(iterations = 3, time = 10)
    @Threads(Threads.MAX)
    @BenchmarkMode(Mode.Throughput)
    public void search(final Blackhole blackhole, final BenchmarkContext context) {
        blackhole.consume(context.getEngine()
                .search(Utils.INDEX_NAME, context.nextContext(), false));
    }

}
