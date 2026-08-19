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
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestDetailMapper
{
    @Test
    void aggregatesTheLatestAttemptForEachCheck()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of())).isEqualTo(PullRequestDetail.CiStatus.NONE);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"),
                check("build", "completed", "failure")))).isEqualTo(PullRequestDetail.CiStatus.PASSING);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"),
                check("test", "in_progress", null)))).isEqualTo(PullRequestDetail.CiStatus.PENDING);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(check("build", "completed", "failure")))).isEqualTo(PullRequestDetail.CiStatus.FAILING);
    }

    @Test
    void mapsOnlyTheDetailFieldsTheRendererUses()
    {
        StoredPrDetail stored = new StoredPrDetail(
                raw(),
                List.of(new PrReviewState("alice", "APPROVED")),
                List.of(new PrTimelineEvent(
                        null,
                        "ci_autofix",
                        "bob",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        "verified push",
                        Optional.of("ci-autofix:event"))),
                List.of(check("build", "completed", "success")));

        PullRequestDetail detail = PullRequestDetailMapper.toPullRequestDetail("owner/repo", 5, stored, true);

        assertThat(detail.repo()).isEqualTo("owner/repo");
        assertThat(detail.body()).isEqualTo("body");
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

    private static PrCheckRunState check(String name, String status, String conclusion)
    {
        return new PrCheckRunState(1L, name, status, conclusion);
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 10, 2, 3, 0, List.of(),
                "head", "feature");
    }
}
