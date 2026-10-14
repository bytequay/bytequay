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
package com.bytequay.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommitEnrichment
{
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void rejectsPullRequestDetailsWithoutANumber()
    {
        assertThatThrownBy(() -> new CommitEnrichment(
                1,
                "abc",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("Title"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pullRequestTitle requires");
    }

    @Test
    void sharesPositiveReuseAndDailyRecentNegativeRetryBoundaries()
    {
        CommitEnrichment positive = association(Optional.of(42), Optional.empty());
        assertThat(CommitEnrichment.associationDue(null, Optional.of(positive), NOW)).isFalse();
        String oldCommit = NOW.minus(Duration.ofDays(100)).toString();
        assertThat(CommitEnrichment.associationDue(oldCommit, Optional.empty(), NOW)).isTrue();
        assertThat(CommitEnrichment.associationDue(oldCommit, Optional.of(association(Optional.empty(), Optional.empty())), NOW)).isTrue();

        CommitEnrichment due = association(Optional.empty(), Optional.of(NOW.minus(Duration.ofDays(1))));
        String windowStart = NOW.minus(Duration.ofDays(7)).toString();
        assertThat(CommitEnrichment.associationDue(windowStart, Optional.of(due), NOW)).isTrue();
        assertThat(CommitEnrichment.associationDue(NOW.minus(Duration.ofDays(7)).minusNanos(1).toString(), Optional.of(due), NOW)).isFalse();
        CommitEnrichment fresh = association(Optional.empty(), Optional.of(NOW.minus(Duration.ofDays(1)).plusNanos(1)));
        assertThat(CommitEnrichment.associationDue(windowStart, Optional.of(fresh), NOW)).isFalse();
        assertThat(CommitEnrichment.associationDue(null, Optional.of(due), NOW)).isTrue();
        assertThat(CommitEnrichment.associationDue("malformed", Optional.of(due), NOW)).isTrue();
        assertThat(CommitEnrichment.associationDue("malformed", Optional.of(fresh), NOW)).isFalse();
    }

    private static CommitEnrichment association(Optional<Integer> number, Optional<Instant> checkedAt)
    {
        return new CommitEnrichment(42, "a".repeat(40), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                number, Optional.empty(), Optional.empty(), Optional.empty(), checkedAt, NOW);
    }
}
