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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_FOR_TASK;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;
import static com.bytequay.persistence.sqlite.entity.JsonGuard.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

class TestJsonGuard
{
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void roundTripsAnApprovalTaskScope()
    {
        ApprovalRequest request = request(ImmutableList.of(ALLOW_ONCE, ALLOW_FOR_TASK, DENY), Optional.of("gh issue list"));
        assertThat(AGENT.read(AGENT.write(request, "approval"), ApprovalRequest.class, "approval")).isEqualTo(request);
    }

    @Test
    void decodesAnApprovalStoredBeforeTaskScopeExisted()
    {
        ApprovalRequest request = request(ImmutableList.of(ALLOW_ONCE, DENY), Optional.empty());
        String json = AGENT.write(request, "approval");
        assertThat(json).contains("\"taskScope\":null");
        assertThat(AGENT.read(json.replace(",\"taskScope\":null", ""), ApprovalRequest.class, "approval")).isEqualTo(request);
    }

    @Test
    void decodesReviewRunsStoredWithLimitations()
    {
        ReviewSummary summary = REVIEW.read("""
                {"revision":1,"purpose":"Fix the parser reset","commits":[],"combinedResult":"Sound","evidence":null,
                 "criticalFindingIds":[],"limitations":["No local build"]}""", ReviewSummary.class, "summary");
        ReviewCoverage coverage = REVIEW.read("""
                {"complete":true,"omissions":[],"limitations":["No local build"],"recordedAt":"2026-09-14T00:00:00Z"}""", ReviewCoverage.class, "coverage");

        assertThat(summary.purpose()).isEqualTo("Fix the parser reset");
        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.warnings()).isEmpty();
    }

    @Test
    void decodesReviewValidationStoredWithoutWarnings()
    {
        ReviewValidation validation = REVIEW.read("""
                {"investigationStep":1,"candidates":[],"newCandidates":[],"selectedFindingIds":[],"selectionExplanation":"No supported findings",
                 "coverage":{"complete":true,"omissions":[],"recordedAt":"2026-09-14T00:00:00Z"}}""", ReviewValidation.class, "validation");

        assertThat(validation.coverage().complete()).isTrue();
        assertThat(validation.coverage().warnings()).isEmpty();
        assertThat(validation.selectedFindingIds()).isEmpty();
        assertThat(validation.selectionExplanation()).isEqualTo("No supported findings");
    }

    private static ApprovalRequest request(List<ProviderApprovalRequest.Decision> decisions, Optional<String> taskScope)
    {
        return new ApprovalRequest(
                ApprovalId.random(),
                TaskId.random(),
                TurnId.random(),
                new ProviderApprovalRequest(
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Allow Codex command",
                        "/bin/zsh -lc 'gh issue list --repo t/t'",
                        ImmutableList.of("/bin/zsh -lc 'gh issue list --repo t/t'"),
                        decisions,
                        taskScope,
                        new ProviderAgentEventSource(Optional.of("thread-1"), Optional.empty(), Optional.empty(), Optional.of("turn-1"), Optional.of("item-1")),
                        NOW),
                NOW);
    }
}
