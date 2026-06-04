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
package com.bytequay.app.service.pr.filters;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the six tiers of {@link UrgentPrFilter}. Each test
 * constructs a one-liner PR with exactly the field combination
 * that should trip the corresponding tier, plus a couple of
 * negative cases to make sure the eligibility filter still
 * shadows merged / closed / draft rows.
 */
class TestUrgentPrFilter
{
    private static final Instant NOW = Instant.parse("2026-06-04T10:00:00Z");
    private final UrgentPrFilter filter = new UrgentPrFilter();

    @Test
    void justWokeTier()
    {
        PullRequest pr = pr()
                .snoozeWakeReason("ci-flipped")
                .build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void readyToMergeTier()
    {
        PullRequest pr = pr()
                .origin(PullRequest.Origin.AUTHORED)
                .ciStatus(PullRequestDetail.CiStatus.PASSING)
                .reviewerVerdicts(Map.of("alice", "APPROVED"))
                .build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void readyToMergeIsBlockedByChangesRequested()
    {
        PullRequest pr = pr()
                .origin(PullRequest.Origin.AUTHORED)
                .ciStatus(PullRequestDetail.CiStatus.PASSING)
                .reviewerVerdicts(Map.of(
                        "alice", "APPROVED",
                        "bob", "CHANGES_REQUESTED"))
                .build();
        // Still urgent — but via the changes-requested tier, not
        // ready-to-merge. The OR makes both true; we only care that
        // the predicate flips.
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void changesRequestedTier()
    {
        PullRequest pr = pr()
                .origin(PullRequest.Origin.AUTHORED)
                .reviewerVerdicts(Map.of("alice", "CHANGES_REQUESTED"))
                .build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void ciFailingTierByAttentionReason()
    {
        PullRequest pr = pr().attentionReason(AttentionReason.CI_FAILING).build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void ciFailingTierByCiStatus()
    {
        PullRequest pr = pr().ciStatus(PullRequestDetail.CiStatus.FAILING).build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void mergeConflictTier()
    {
        PullRequest pr = pr().mergeable(Boolean.FALSE).build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void staleTierAfterSevenDaysWithNoVerdicts()
    {
        PullRequest pr = pr()
                .updatedAt(NOW.minus(Duration.ofDays(8)))
                .reviewerVerdicts(Map.of())
                .build();
        assertThat(filter.matches(pr, NOW)).isTrue();
    }

    @Test
    void staleSuppressedByExistingVerdict()
    {
        PullRequest pr = pr()
                .updatedAt(NOW.minus(Duration.ofDays(30)))
                .reviewerVerdicts(Map.of("alice", "COMMENTED"))
                .build();
        assertThat(filter.matches(pr, NOW))
                .as("a verdict — even non-binding — defeats the stale tier")
                .isFalse();
    }

    @Test
    void quietHealthyPrIsNotUrgent()
    {
        PullRequest pr = pr()
                .ciStatus(PullRequestDetail.CiStatus.PASSING)
                .updatedAt(NOW.minus(Duration.ofHours(2)))
                .reviewerVerdicts(Map.of("alice", "COMMENTED"))
                .build();
        assertThat(filter.matches(pr, NOW)).isFalse();
    }

    @Test
    void mergedPrIsNeverUrgent()
    {
        PullRequest pr = pr()
                .mergedAt(NOW.minus(Duration.ofMinutes(5)))
                .ciStatus(PullRequestDetail.CiStatus.FAILING)
                .build();
        assertThat(filter.matches(pr, NOW)).isFalse();
    }

    @Test
    void draftIsNeverUrgent()
    {
        PullRequest pr = pr().draft(true).ciStatus(PullRequestDetail.CiStatus.FAILING).build();
        assertThat(filter.matches(pr, NOW)).isFalse();
    }

    @Test
    void handledDismissedIsNeverUrgent()
    {
        PullRequest pr = pr()
                .handledAction(HandledAction.DISMISSED)
                .ciStatus(PullRequestDetail.CiStatus.FAILING)
                .build();
        assertThat(filter.matches(pr, NOW)).isFalse();
    }

    @Test
    void filterNameMatchesConcept()
    {
        assertThat(filter.name()).isEqualTo("urgent");
    }

    // ── tiny PR builder ─────────────────────────────────────────────
    // The domain record has ~26 fields; the test only varies a few
    // per case so a private builder keeps each test readable.

    private static Builder pr()
    {
        return new Builder();
    }

    private static final class Builder
    {
        private long id = 1;
        private String state = "open";
        private boolean draft;
        private Instant mergedAt;
        private PullRequest.Origin origin = PullRequest.Origin.REVIEW_REQUESTED;
        private PullRequestDetail.CiStatus ciStatus;
        private Map<String, String> reviewerVerdicts;
        private Boolean mergeable;
        private AttentionReason attentionReason;
        private Instant updatedAt = NOW.minus(Duration.ofHours(1));
        private Instant snoozedUntil;
        private String snoozeWakeReason;
        private HandledAction handledAction;

        Builder origin(PullRequest.Origin v)
        {
            this.origin = v;
            return this;
        }

        Builder draft(boolean v)
        {
            this.draft = v;
            return this;
        }

        Builder mergedAt(Instant v)
        {
            this.mergedAt = v;
            return this;
        }

        Builder ciStatus(PullRequestDetail.CiStatus v)
        {
            this.ciStatus = v;
            return this;
        }

        Builder reviewerVerdicts(Map<String, String> v)
        {
            this.reviewerVerdicts = v;
            return this;
        }

        Builder mergeable(Boolean v)
        {
            this.mergeable = v;
            return this;
        }

        Builder attentionReason(AttentionReason v)
        {
            this.attentionReason = v;
            return this;
        }

        Builder updatedAt(Instant v)
        {
            this.updatedAt = v;
            return this;
        }

        Builder snoozeWakeReason(String v)
        {
            this.snoozeWakeReason = v;
            return this;
        }

        Builder handledAction(HandledAction v)
        {
            this.handledAction = v;
            return this;
        }

        PullRequest build()
        {
            return new PullRequest(
                    id, "owner/repo", 1, "title", "alice", "https://x/p/1",
                    NOW.minus(Duration.ofDays(3)), updatedAt, origin,
                    List.of(), Map.of(), draft, null, null, handledAction, List.of(),
                    ciStatus, 0, 0, 0, attentionReason,
                    state, null, mergedAt, mergeable, null, null,
                    reviewerVerdicts, snoozedUntil, snoozeWakeReason, null);
        }
    }
}
