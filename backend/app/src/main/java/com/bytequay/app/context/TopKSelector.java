/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.context;

import com.google.common.collect.Iterables;

import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;

/** Bounds element selection; callers retain responsibility for filtering, deduplication, source fetches and byte budgets. */
record TopKSelector(int limit)
{
    TopKSelector
    {
        checkArgument(limit >= 0, "limit is negative");
    }

    /** Lazily takes at most {@link #limit()} elements in the stream's encounter order. */
    <T> Stream<T> select(Stream<T> candidates)
    {
        return candidates.limit(limit);
    }

    /** Ranks before truncating, preserving encounter order for equal-ranked elements of an ordered stream. Sorting reads all candidates. */
    <T> Stream<T> select(Stream<T> candidates, Comparator<? super T> order)
    {
        return select(candidates.sorted(order));
    }

    /** Lazily iterates over at most {@link #limit()} elements without collecting an intermediate list. */
    <T> Iterable<T> select(Iterable<T> candidates)
    {
        return Iterables.limit(candidates, limit);
    }
}
