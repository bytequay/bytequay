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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.review.CliReviewRunner.McpEndpoint;
import com.bytequay.app.service.review.CliReviewRunner.Provider;
import com.bytequay.app.service.review.CliReviewRunner.Result;
import com.bytequay.app.service.review.InvestigationReviewContext.Snapshot;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewKnowledge;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.threads.AgentScheduler;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestInvestigationReviewRunnerProjectKnowledge
{
    private final CliReviewRunner cli = mock(CliReviewRunner.class);
    private final AgentScheduler scheduler = mock(AgentScheduler.class);
    private final SessionKnowledgeProvider knowledge = mock(SessionKnowledgeProvider.class);
    private InvestigationReviewRunner runner;

    @BeforeEach
    void setUp()
            throws Exception
    {
        runner = new InvestigationReviewRunner(
                mock(TurnRunner.class), cli, mock(ReviewProviderEndpoints.class),
                mock(ReviewPassService.class), mock(InvestigationReviewTools.class),
                scheduler, knowledge, new ObjectMapper());
        when(scheduler.invokeCli(any())).thenAnswer(invocation -> {
            Callable<?> work = invocation.getArgument(0);
            return work.call();
        });
        when(cli.runWithSchedulerCapacity(
                eq(Provider.CLAUDE), anyString(), isNull(), any(Path.class),
                any(McpEndpoint.class), anyInt()))
                .thenReturn(new Result("done", null, 0));
    }

    @Test
    void testInvestigationUsesSnapshotFilesAndWarnsToVerifyGuidance()
    {
        Snapshot snapshot = snapshot(
                List.of(new DiffFile(
                        "backend/src/main/java/acme/Scheduler.java",
                        "modified", 1, 1, null)),
                "diff --git a/frontend/Wrong.tsx b/frontend/Wrong.tsx\n");
        when(knowledge.renderForRepository(eq("acme/widget"), anyString()))
                .thenReturn("# Project capsule\n\n## Note\n\nRelease slots in finally.");

        runner.investigate(
                new ProviderChoice("claude-cli", "cli", "anthropic"),
                "review-1", "assignment-1", snapshot, List.of(),
                "coverage", null, 25);

        ArgumentCaptor<String> hint = ArgumentCaptor.forClass(String.class);
        verify(knowledge).renderForRepository(eq("acme/widget"), hint.capture());
        assertThat(hint.getValue())
                .contains("backend/src/main/java/acme/Scheduler.java")
                .contains("Fix scheduler cancellation")
                .contains("Release capacity when work aborts")
                .doesNotContain("frontend/Wrong.tsx");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).runWithSchedulerCapacity(
                eq(Provider.CLAUDE), prompt.capture(), isNull(), any(Path.class),
                any(McpEndpoint.class), eq(25));
        assertThat(prompt.getValue())
                .contains("Project Intelligence guidance")
                .contains("verify every claim against the reviewed code at this SHA")
                .contains("Release slots in finally.")
                .contains("Only call record_finding for severity 4 or 5")
                .contains("warrants REQUEST_CHANGES")
                .contains("Never record trivial, nit, informational, suggestion, warning")
                .contains("precise and ADHD-friendly")
                .contains("at most 80 words")
                .contains("override persona and project guidance");
    }

    @Test
    void testVerificationDropsFeedbackBelowTheRequestChangesThreshold()
    {
        runner.verify(
                new ProviderChoice("claude-cli", "cli", "anthropic"),
                "review-1", "assignment-1", snapshot(List.of(), ""), "verifier-1",
                "finding", null, null, 25);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).runWithSchedulerCapacity(
                eq(Provider.CLAUDE), prompt.capture(), isNull(), any(Path.class),
                any(McpEndpoint.class), eq(25));
        assertThat(prompt.getValue())
                .contains("Only severity 4 or 5 findings that warrant REQUEST_CHANGES are publishable")
                .contains("revise its severity below 4 so it is dropped")
                .contains("precise and ADHD-friendly")
                .contains("at most 80 words total");
    }

    @Test
    void testRetrievalHintFallsBackToDiffHeadersWhenFileManifestIsEmpty()
    {
        Snapshot snapshot = snapshot(List.of(), """
                diff --git a/backend/src/Old.java b/backend/src/New.java
                @@ -1 +1 @@
                -old
                +new
                """);

        assertThat(InvestigationReviewRunner.retrievalHint(snapshot))
                .startsWith("backend/src/New.java\n")
                .contains("Fix scheduler cancellation")
                .contains("Release capacity when work aborts");
    }

    @Test
    void testReviewKnowledgeKeepsPlannerProvenance()
    {
        Snapshot snapshot = snapshot(
                List.of(new DiffFile(
                        "plugin/trino-iceberg/src/main/java/Metadata.java",
                        "modified", 1, 1, null)), "");
        KnowledgeItem item = new KnowledgeItem(
                "k-1", "ws-1", "acme/widget", "compatibility-contract", null,
                "Preserve metadata compatibility.", null, List.of("review"),
                "high", "active", null, null, "pr-learning",
                KnowledgeItemStore.statementDigest("Preserve metadata compatibility."),
                "{}", 1, 2);
        KnowledgeItem.Applicability module = new KnowledgeItem.Applicability(
                "module", "plugin/trino-iceberg");
        when(knowledge.reviewKnowledgeForRepository(eq("acme/widget"), anyString()))
                .thenReturn(List.of(new SessionKnowledgeProvider.RepositoryKnowledge(
                        item, List.of(module), "exact scope match")));

        List<ReviewKnowledge> result = runner.reviewKnowledge(snapshot);

        assertThat(result).containsExactly(new ReviewKnowledge(
                "k-1", "compatibility-contract", "Preserve metadata compatibility.",
                List.of(module), 2));
    }

    @Test
    void testInvestigationContinuesWhenProjectKnowledgeIsUnavailable()
    {
        Snapshot snapshot = snapshot(List.of(), "");
        when(knowledge.renderForRepository(eq("acme/widget"), anyString()))
                .thenThrow(new IllegalStateException("index unavailable"));

        runner.investigate(
                new ProviderChoice("claude-cli", "cli", "anthropic"),
                "review-1", "assignment-1", snapshot, List.of(),
                "coverage", null, 25);

        verify(cli).runWithSchedulerCapacity(
                eq(Provider.CLAUDE), anyString(), isNull(), any(Path.class),
                any(McpEndpoint.class), eq(25));
    }

    private static Snapshot snapshot(List<DiffFile> files, String diff)
    {
        PR pr = PR.createExternal(
                "pr-1", "acme/widget", 17, "https://example.test/17", "octocat",
                "feature", "main", "Fix scheduler cancellation",
                "Release capacity when work aborts", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        return new Snapshot(pr, "base", "head", diff, files, null);
    }
}
