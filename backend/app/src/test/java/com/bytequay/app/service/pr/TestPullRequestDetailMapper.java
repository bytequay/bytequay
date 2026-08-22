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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestDetailMapper
{
    @Test
    void aggregatesTheLatestAttemptForEachCheck()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of())).isEqualTo(PullRequestDetail.CiStatus.NONE);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                check("build", "completed", "success"),
                check("build", "completed", "failure")))).isEqualTo(PullRequestDetail.CiStatus.PASSING);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                check("build", "completed", "success"),
                check("test", "in_progress", null)))).isEqualTo(PullRequestDetail.CiStatus.PENDING);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(check("build", "completed", "failure")))).isEqualTo(PullRequestDetail.CiStatus.FAILING);
    }

    @Test
    void mapsOnlyTheDetailFieldsTheRendererUses()
    {
        StoredPrDetail stored = new StoredPrDetail(
                raw(),
                ImmutableList.of(new PrReviewState("alice", "APPROVED")),
                ImmutableList.of(new PrTimelineEvent(
                        null,
                        "ci_autofix",
                        "bob",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        "verified push",
                        Optional.of("ci-autofix:event"))),
                ImmutableList.of(check("build", "completed", "success")));

        PullRequestDetail detail = PullRequestDetailMapper.toPullRequestDetail("owner/repo", 5, stored, true);

        assertThat(detail.repo()).isEqualTo("owner/repo");
        assertThat(detail.body()).isEqualTo("body");
        assertThat(detail.mergeable()).isTrue();
        assertThat(detail.mergeableState()).isEqualTo("clean");
        assertThat(detail.approvalCount()).isEqualTo(1);
        assertThat(detail.ciStatus()).isEqualTo(PullRequestDetail.CiStatus.PASSING);
        assertThat(detail.recentActivity()).singleElement().satisfies(item -> {
            assertThat(item.actor()).isEqualTo("bob");
            assertThat(item.eventType()).isEqualTo("ci_autofix");
            assertThat(item.body()).isEqualTo("verified push");
        });
        assertThat(detail.checkRuns()).singleElement().satisfies(check -> assertThat(check.name()).isEqualTo("build"));
        assertThat(detail.viewerCanWrite()).isTrue();
    }

    @Test
    void mapsACompleteHeaderAndChronologicalTypedTimeline()
    {
        PrTimelinePayload label = new PrTimelinePayload(
                "https://avatars/bot", true, null, null, "docs", "d4c5f9",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, ImmutableList.of(), ImmutableList.of());
        StoredPrDetail stored = new StoredPrDetail(
                richRaw(),
                ImmutableList.of(
                        new PrReviewState("bob", "APPROVED", Instant.parse("2026-08-20T10:00:00Z")),
                        new PrReviewState("bob", "CHANGES_REQUESTED", Instant.parse("2026-08-20T11:00:00Z"))),
                ImmutableList.of(
                        new PrTimelineEvent(2L, "renamed", "alice", Instant.parse("2026-08-20T10:02:00Z"), null, java.util.Optional.empty()),
                        new PrTimelineEvent(1L, "labeled", "github-actions[bot]", Instant.parse("2026-08-20T10:01:00Z"), null, label, java.util.Optional.empty())),
                ImmutableList.of(
                        new PrCheckRunState(1L, "build", "completed", "failure"),
                        new PrCheckRunState(2L, "license", "completed", "success")),
                ImmutableList.of("reviewThreads"));

        PullRequestDetail detail = PullRequestDetailMapper.toPullRequestDetail("owner/repo", 42, stored, false);

        assertThat(detail.title()).isEqualTo("Overview");
        assertThat(detail.state()).isEqualTo("open");
        assertThat(detail.baseRef()).isEqualTo("main");
        assertThat(detail.labels()).singleElement().satisfies(value -> assertThat(value.color()).isEqualTo("d4c5f9"));
        assertThat(detail.reviewers()).extracting(PullRequestDetail.Reviewer::login).containsExactly("carol", "bob");
        assertThat(detail.approvalCount()).isZero();
        assertThat(detail.recentActivity()).extracting(PullRequestDetail.ActivityItem::eventType)
                .containsExactly("labeled", "renamed");
        assertThat(detail.checkRuns()).extracting(PullRequestDetail.CheckRun::category)
                .containsExactly("failing", "successful");
        assertThat(detail.unavailableSections()).containsExactly("reviewThreads");
    }

    private static PrCheckRunState check(String name, String status, String conclusion)
    {
        return new PrCheckRunState(1L, name, status, conclusion);
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 2, 3, 0, ImmutableList.of(),
                "head", "feature");
    }

    private static PrRawDetail richRaw()
    {
        return new PrRawDetail(
                "Overview",
                "https://github.com/owner/repo/pull/42",
                "open",
                "alice",
                "https://avatars/alice",
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T11:00:00Z"),
                null,
                "body",
                ImmutableList.of("docs"),
                ImmutableMap.of("docs", "d4c5f9"),
                ImmutableList.of("dave"),
                "v1",
                false,
                true,
                "clean",
                10,
                2,
                3,
                1,
                ImmutableList.of("carol"),
                "head",
                "feature",
                "main");
    }
}
