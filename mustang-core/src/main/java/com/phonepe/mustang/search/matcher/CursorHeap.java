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
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.MutablePair;

import com.phonepe.mustang.index.core.Key;

/**
 * Posting-list cursors of one matcher pass, ordered by current posting entry, shared by {@link CNFMatcher} and
 * {@link DNFMatcher}.
 *
 * <p>Each cursor is a posting list (TreeMap keyed by internal id) plus its current id (the pair's left; null once
 * exhausted). Cursors are addressed by rank: {@code id(0)} / {@code entry(0)} is the smallest cursor. The
 * {@code order} comparator must place null entries (exhausted cursors) last; ties are broken by list index.
 *
 * <p>Layout: a small sorted {@code front} holding the smallest cursors, then a binary min-heap with the rest. Every
 * front cursor is smaller than every heap cursor. The matcher only reads the first few ranks, so cursors are popped
 * from the heap into the front lazily, on demand; the next rank after the front is read from the heap root without
 * popping it.
 */
final class CursorHeap<E> {
    private final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>>[] lists;
    private final Comparator<? super E> order;
    /** Current posting entry of each list (by list index), so reads and comparisons never touch the TreeMap. */
    private final E[] entries;
    private final int[] front;
    private int frontSize;
    private final int[] heap;
    private int heapSize;

    /** Positions every cursor at the first entry of its posting list and orders them. */
    @SuppressWarnings("unchecked")
    CursorHeap(final Entry<Key, MutablePair<Integer, TreeMap<Integer, E>>>[] lists,
            final Comparator<? super E> order) {
        final int n = lists.length;
        this.lists = lists;
        this.order = order;
        this.entries = (E[]) new Object[n];
        this.front = new int[n];
        this.heap = new int[n];
        for (int i = 0; i < n; i++) {
            // start every cursor at the first entry of its posting list
            final Map.Entry<Integer, E> first = lists[i].getValue()
                    .getValue()
                    .firstEntry();
            lists[i].getValue()
                    .setLeft(first.getKey());
            entries[i] = first.getValue();
            heap[i] = i;
        }
        heapSize = n;
        for (int i = (n >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    int size() {
        return lists.length;
    }

    /** Current id of the cursor at {@code rank}; null if exhausted. */
    Integer id(final int rank) {
        return currentId(cursorAt(rank));
    }

    /** Current posting entry of the cursor at {@code rank}; null if exhausted. */
    E entry(final int rank) {
        return entries[cursorAt(rank)];
    }

    /**
     * Advance every cursor whose current id is below {@code nextID}, keeping the order.
     *
     * <p>The front is sorted, so its cursors to advance are a PREFIX; they are advanced and pushed into the heap. If
     * any front cursor stays, every heap cursor is already >= nextID; otherwise cursors below nextID surface at the
     * heap root and are advanced in place. The cursors left in front stay only while they are still smaller than the
     * heap minimum. Cost per step: O((moved + returned) log n); no allocation and no shifting of the n-sized cursor array.
     */
    void skipTo(final int nextID) {
        int moved = 0;
        while (moved < frontSize && isBefore(front[moved], nextID)) {
            advance(front[moved], nextID);
            push(front[moved]);
            moved++;
        }
        frontSize -= moved;
        System.arraycopy(front, moved, front, 0, frontSize);
        if (frontSize == 0) {
            while (heapSize > 0 && isBefore(heap[0], nextID)) {
                advance(heap[0], nextID);
                siftDown(0);
            }
            return;
        }
        // advanced cursors may now sort before some of the remaining front; return those to the heap
        while (frontSize > 0 && heapSize > 0 && less(heap[0], front[frontSize - 1])) {
            push(front[--frontSize]);
        }
    }

    private int cursorAt(final int rank) {
        while (frontSize < rank) {
            front[frontSize++] = pop();
        }
        // the next rank is the heap root: peek it, so a cursor only looked at is never popped and pushed back
        return rank < frontSize ? front[rank] : heap[0];
    }

    private Integer currentId(final int list) {
        return lists[list].getValue()
                .getKey();
    }

    private boolean isBefore(final int list, final int nextID) {
        final Integer current = currentId(list);
        return current != null && current < nextID;
    }

    private void advance(final int list, final int nextID) {
        // ceilingEntry gives the new id and its entry in one tree walk
        final Map.Entry<Integer, E> next = lists[list].getValue()
                .getValue()
                .ceilingEntry(nextID);
        lists[list].getValue()
                .setLeft(next != null ? next.getKey() : null);
        entries[list] = next != null ? next.getValue() : null;
    }

    private boolean less(final int a, final int b) {
        final int c = order.compare(entries[a], entries[b]);
        return c < 0 || (c == 0 && a < b);
    }

    private int pop() {
        final int top = heap[0];
        heap[0] = heap[--heapSize];
        siftDown(0);
        return top;
    }

    private void push(final int list) {
        int i = heapSize++;
        while (i > 0) {
            final int parent = (i - 1) >>> 1;
            if (!less(list, heap[parent])) {
                break;
            }
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = list;
    }

    private void siftDown(int i) {
        final int list = heap[i];
        final int half = heapSize >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            if (child + 1 < heapSize && less(heap[child + 1], heap[child])) {
                child++;
            }
            if (!less(heap[child], list)) {
                break;
            }
            heap[i] = heap[child];
            i = child;
        }
        heap[i] = list;
    }
}
