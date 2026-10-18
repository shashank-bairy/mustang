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

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.MutablePair;

import com.phonepe.mustang.index.core.Key;

import lombok.experimental.UtilityClass;

/**
 * Ordering of posting-list cursors shared by {@link CNFMatcher} and {@link DNFMatcher}.
 *
 * <p>Each cursor is a posting list (TreeMap keyed by internal id) plus its current id (the pair's left; null once
 * exhausted). The matchers keep the cursors sorted by their current posting entry, with exhausted cursors last. The
 * {@code order} comparator passed in must place null entries last.
 */
@UtilityClass
class PostingListCursors {

    /**
     * Stable sort by current entry. Each entry is looked up once per sort rather than inside every comparison.
     */
    static <E> void sort(final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>>[] pLists,
            final Comparator<? super E> order) {
        if (pLists.length < 2) {
            return;
        }
        final Cursor<E>[] cursors = newCursors(pLists.length);
        for (int i = 0; i < pLists.length; i++) {
            cursors[i] = new Cursor<>(pLists[i], currentEntry(pLists[i]));
        }
        Arrays.sort(cursors, (a, b) -> order.compare(a.entry(), b.entry()));
        for (int i = 0; i < cursors.length; i++) {
            pLists[i] = cursors[i].list();
        }
    }

    /**
     * Advance every cursor whose current id is below {@code nextID}, then restore the sorted order.
     *
     * <p>pLists is sorted on entry, so the cursors to advance are exactly a PREFIX [0, m) and the suffix [m, n) is
     * untouched and still sorted. Only the m moved cursors are sorted, then each is binary-searched into the suffix.
     * The result is identical to a stable full sort: on ties a moved cursor goes before suffix cursors because it was
     * earlier originally.
     */
    static <E> void skipTo(final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>>[] pLists,
            final int nextID,
            final Comparator<? super E> order) {
        final int n = pLists.length;
        int m = 0;
        while (m < n && isBefore(pLists[m].getValue(), nextID)) {
            m++;
        }
        if (m == 0) {
            return;
        }
        final Cursor<E>[] moved = newCursors(m);
        for (int i = 0; i < m; i++) {
            // ceilingEntry gives the new id and its entry in one tree walk
            final Map.Entry<Integer, E> next = pLists[i].getValue()
                    .getValue()
                    .ceilingEntry(nextID);
            pLists[i].getValue()
                    .setLeft(next != null ? next.getKey() : null);
            moved[i] = new Cursor<>(pLists[i], next != null ? next.getValue() : null);
        }
        Arrays.sort(moved, (a, b) -> order.compare(a.entry(), b.entry()));

        // In-place merge: output index o = i + (j - m) <= j, so unread suffix entries are never overwritten.
        int j = m;
        int o = 0;
        for (final Cursor<E> cursor : moved) {
            final int pos = lowerBound(pLists, j, n, cursor.entry(), order);
            final int run = pos - j;
            if (run > 0) {
                System.arraycopy(pLists, j, pLists, o, run);
                o += run;
                j = pos;
            }
            pLists[o++] = cursor.list();
        }
        // remaining suffix [j, n) is already in place (o == j here)
    }

    private static boolean isBefore(final MutablePair<Integer, ?> cursor, final int nextID) {
        final Integer current = cursor.getKey();
        return current != null && current < nextID;
    }

    private static <E> E currentEntry(final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>> pList) {
        final Integer current = pList.getValue()
                .getKey();
        return current != null ? pList.getValue()
                .getValue()
                .get(current) : null;
    }

    /** First index in [lo, hi) whose current entry is >= {@code entry}. */
    private static <E> int lowerBound(final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>>[] pLists,
            int lo,
            int hi,
            final E entry,
            final Comparator<? super E> order) {
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (order.compare(currentEntry(pLists[mid]), entry) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @SuppressWarnings("unchecked")
    private static <E> Cursor<E>[] newCursors(final int size) {
        return new Cursor[size];
    }

    private record Cursor<E>(Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>> list, E entry) {
    }
}
