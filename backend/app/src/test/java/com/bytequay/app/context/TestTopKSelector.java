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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparingInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTopKSelector
{
    @Test
    void negativeLimitIsRejected()
    {
        assertThatThrownBy(() -> new TopKSelector(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLimitDoesNotReadTheSource()
    {
        TopKSelector selector = new TopKSelector(0);
        AtomicInteger reads = new AtomicInteger();
        Iterable<Integer> source = Iterables.transform(ImmutableList.of(1, 2), value -> reads.incrementAndGet());

        assertThat(selector.limit()).isZero();
        assertThat(ImmutableList.copyOf(selector.select(source))).isEmpty();
        try (Stream<Integer> selected = selector.select(Stream.generate(reads::incrementAndGet))) {
            assertThat(selected.collect(toImmutableList())).isEmpty();
        }
        assertThat(selector.select(Stream.of(2, 1), comparingInt(Integer::intValue)).collect(toImmutableList())).isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void rankedSelectionSortsBeforeTruncatingAndPreservesEqualRankOrder()
    {
        TopKSelector selector = new TopKSelector(3);
        Candidate slow = new Candidate("slow", 3);
        Candidate firstTie = new Candidate("first tie", 1);
        Candidate winner = new Candidate("winner", 0);
        Candidate secondTie = new Candidate("second tie", 1);
        AtomicInteger reads = new AtomicInteger();
        Stream<Candidate> selected = selector.select(
                ImmutableList.of(slow, firstTie, winner, secondTie).stream().peek(ignored -> reads.incrementAndGet()), comparingInt(Candidate::rank));

        assertThat(reads).hasValue(0);
        assertThat(selected.collect(toImmutableList())).containsExactly(winner, firstTie, secondTie);
        assertThat(reads).hasValue(4);
    }

    @Test
    void callerFilteringAndDeduplicationPrecedeTheLimit()
    {
        Stream<String> candidates = ImmutableList.of("skip", "alpha", "alpha", "beta", "gamma").stream()
                .filter(value -> !value.equals("skip")).distinct();

        assertThat(new TopKSelector(2).select(candidates).collect(toImmutableList())).containsExactly("alpha", "beta");
    }

    @Test
    void iterableSelectionIsLazyAndDoesNotReadPastTheLimit()
    {
        AtomicInteger reads = new AtomicInteger();
        Iterable<Integer> source = Iterables.transform(ImmutableList.of(1, 2, 3), value -> {
            reads.incrementAndGet();
            return value;
        });

        Iterable<Integer> selected = new TopKSelector(2).select(source);

        assertThat(reads).hasValue(0);
        assertThat(ImmutableList.copyOf(selected)).containsExactly(1, 2);
        assertThat(reads).hasValue(2);
    }

    @Test
    void streamSelectionIsLazyAndDoesNotReadPastTheLimit()
    {
        AtomicInteger reads = new AtomicInteger();
        try (Stream<Integer> selected = new TopKSelector(2).select(Stream.generate(reads::incrementAndGet))) {
            assertThat(reads).hasValue(0);
            assertThat(selected.collect(toImmutableList())).containsExactly(1, 2);
        }
        assertThat(reads).hasValue(2);
    }

    private record Candidate(String name, int rank) {}
}
