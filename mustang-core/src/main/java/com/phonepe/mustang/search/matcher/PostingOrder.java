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

import java.util.Comparator;

import org.apache.commons.lang3.ObjectUtils;

import com.phonepe.mustang.index.core.ConjunctionPostingEntry;
import com.phonepe.mustang.index.core.DisjunctionPostingEntry;

import lombok.experimental.UtilityClass;

/**
 * Order in which the matchers keep posting-list cursors, by each cursor's current entry. Exhausted cursors (null
 * entry) always sort last.
 */
@UtilityClass
class PostingOrder {

    /** CNF: id, then type, then disjunction order. */
    static final Comparator<DisjunctionPostingEntry> CNF = Comparator.nullsLast((a, b) -> {
        int c = ObjectUtils.compare(a.getIId(), b.getIId(), true);
        if (c == 0) {
            c = ObjectUtils.compare(a.getType(), b.getType(), true);
        }
        return c != 0 ? c : Integer.compare(a.getOrder(), b.getOrder());
    });

    /** DNF: id, then type ({@link ConjunctionPostingEntry#compareTo}). */
    static final Comparator<ConjunctionPostingEntry> DNF = Comparator.nullsLast(Comparator.naturalOrder());
}
