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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewLessonDraftService
{
    @TempDir
    private Path temporaryDirectory;

    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final RunningTurn turn = mock(RunningTurn.class);
    private final TaskId taskId = TaskId.random();
    private final ReviewLessonDraftService service = new ReviewLessonDraftService(reviews, tasks, drivers, resources, new ObjectMapper());

    @BeforeEach
    void setUp()
            throws Exception
    {
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        AgentReview review = new AgentReview("review-1", "workspace-1", "bytequay/sample", OptionalInt.of(7), OptionalLong.empty(), taskId.toString(), Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, earlier, earlier, Optional.empty());
        when(reviews.findReview("review-1")).thenReturn(Optional.of(review));

        ReviewEvidence evidence = new ReviewEvidence("E1", "Frozen test", "Saved run evidence", "The old test missed parent IDs.", "Observed in the review");
        ReviewRun run = new ReviewRun("run-1", "review-1", 1, ReviewRun.Scope.FULL, "main", "base", "base", "abc123456789",
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING,
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(),
                ImmutableMap.of("E1", evidence), Optional.empty(), Optional.empty(), 0, earlier, earlier, Optional.empty());
        when(reviews.findRun("run-1")).thenReturn(Optional.of(run));

        ReviewMessage question = new ReviewMessage("question", "review-1", Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.USER,
                "When nested fields change, are parent IDs checked?", 1, "run-1", Optional.empty(), Optional.empty(), 0, earlier);
        ReviewMessage answer = new ReviewMessage("source", "review-1", Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.AGENT,
                "Yes, the parent ID check is missing.", 1, "run-1", Optional.empty(), Optional.empty(), 0, earlier.plusSeconds(1));
        when(reviews.findMessage("source")).thenReturn(Optional.of(answer));
        when(reviews.listEarlierMessages("review-1", "run-1", Optional.of("finding-1"), Optional.empty(), 1, answer.createdAt(), answer.id(), 3))
                .thenReturn(ImmutableList.of(question));

        ReviewFinding finding = new ReviewFinding("finding-1", "review-1", "run-1", 1, "Nested field parent IDs",
                "The new branch omits the parent ID assertion.", Optional.empty(),
                Optional.of(new ReviewAnchor("src/Parser.java", "RIGHT", 3, 3, ImmutableList.of("parentId"))), ImmutableList.of(evidence),
                1, ReviewFinding.State.OPEN, Optional.empty(), run.headSha(), false, 0, earlier, earlier);
        when(reviews.findFinding("finding-1")).thenReturn(Optional.of(finding));

        AgentTask task = AgentTask.created(taskId, Optional.of("workspace-1"), true, "Review task", new AgentLaneId("codex"),
                new RepositoryIdentity("bytequay", "sample"), temporaryDirectory,
                new AgentTaskDefaults(Optional.of("review-model"), Optional.of("high"), AgentPermissionPolicy.AUTO), earlier);
        when(tasks.find(taskId)).thenReturn(Optional.of(task));

        AgentProviderStatus status = new AgentProviderStatus(new AgentLaneId("codex"), AgentProviderStatus.Readiness.READY,
                Optional.empty(), "Ready", AgentCapabilities.none());
        when(drivers.require(new AgentLaneId("codex"))).thenReturn(driver);
        when(driver.probe()).thenReturn(status);

        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Files.createDirectories(runtimeRoot);
        when(resources.allocate(any())).thenAnswer(invocation -> {
            TaskId helper = invocation.getArgument(0);
            Path runtime = runtimeRoot.resolve(helper.toString());
            Path provider = runtime.resolve("provider");
            Files.createDirectories(provider);
            return new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), provider);
        });
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        completed("{\"title\":\"Check parent IDs\",\"summary\":\"When nested fields change, check parent IDs.\","
                + "\"body\":\"Verify the parent ID assertion at the reviewed head.\"}");
    }

    @Test
    void proposesFromTheOriginalRunInAnIsolatedTextOnlySession()
    {
        assertThat(service.draft("review-1", "source")).isEqualTo(new ReviewLessonDraft(
                "Check parent IDs", "When nested fields change, check parent IDs.", "Verify the parent ID assertion at the reviewed head."));

        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any(AgentEventSink.class));
        assertThat(context.getValue().textOnly()).isTrue();
        assertThat(context.getValue().sessionReference()).isEmpty();
        assertThat(context.getValue().codeGraph()).isEmpty();
        assertThat(context.getValue().managedInstructions()).isEmpty();
        assertThat(context.getValue().model()).contains("review-model");
        assertThat(context.getValue().effort()).contains("high");
        assertThat(context.getValue().worktree()).startsWith(temporaryDirectory.resolve("runtime"));

        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        assertThat(command.getValue().message())
                .contains("the parent ID check is missing", "When nested fields change", "The old test missed parent IDs", "The new branch omits",
                        "200 UTF-8 bytes", "1000 UTF-8 bytes", "8192 UTF-8 bytes")
                .doesNotContain("Ignore this unrelated summary discussion");
        verify(session).close();
        verify(resources).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\"}\n```",
            "{\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\"} trailing prose",
            "{\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"scopeId\":\"project\"}",
            "{\"title\":\"T\",\"summary\":\"S\",\"body\":\"B\",\"title\":\"replacement\"}",
            "{\"title\":\"   \",\"summary\":\"S\",\"body\":\"B\"}"
    })
    void rejectsAnyNonconformingModelResponse(String output)
    {
        completed(output);
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("invalid lesson draft");
        verify(resources).delete(any());
    }

    @Test
    void reportsInsufficientContextWithoutAProposal()
    {
        completed("{\"insufficient\":true}");
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("does not support a clear reusable lesson");
    }

    @Test
    void excludesOtherTargetSummaryWhenTheSelectedFindingRevisionIsGone()
    {
        ReviewFinding finding = reviews.findFinding("finding-1").orElseThrow();
        when(reviews.findFinding("finding-1")).thenReturn(Optional.of(finding.revised(
                finding.title(), finding.body(), finding.severity(), finding.anchor(), finding.evidence(), finding.validatedHeadSha(), finding.updatedAt().plusSeconds(1))));
        ReviewRun run = reviews.findRun("run-1").orElseThrow();
        ReviewSummary summary = new ReviewSummary(1, "Unrelated summary purpose", ImmutableList.of(),
                "Unrelated summary outcome", Optional.empty(), ImmutableList.of());
        when(reviews.findRun("run-1")).thenReturn(Optional.of(run.withSummary(summary, run.updatedAt())));
        service.draft("review-1", "source");
        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        assertThat(command.getValue().message()).contains("\"reviewCardVersionAvailable\":false")
                .doesNotContain("The new branch omits the parent ID assertion", "Unrelated summary purpose", "Unrelated summary outcome");
    }

    @Test
    void rejectsARequestForAnotherReviewsMessageBeforeStartingAProvider()
    {
        assertThatThrownBy(() -> service.draft("review-1", "not-in-this-review"))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageContaining("does not belong");
        verify(driver, never()).open(any(), any());
        verify(resources, never()).allocate(any());
    }

    @Test
    void rejectsATaskFromAnotherWorkspaceBeforeStartingAProvider()
    {
        AgentTask task = tasks.find(taskId).orElseThrow();
        AgentTask otherWorkspace = AgentTask.created(task.id(), Optional.of("workspace-2"), true,
                task.title(), task.laneId(), task.repository(), task.worktree(), task.defaults(), task.createdAt());
        when(tasks.find(taskId)).thenReturn(Optional.of(otherWorkspace));

        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("review agent is unavailable");
        verify(driver, never()).open(any(), any());
        verify(resources, never()).allocate(any());
    }

    @Test
    void acceptsAProviderDefaultModelWithoutAnEffortOverride()
    {
        AgentTask task = tasks.find(taskId).orElseThrow();
        AgentTask providerDefault = AgentTask.created(task.id(), task.workspaceId(), true,
                task.title(), task.laneId(), task.repository(), task.worktree(),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO), task.createdAt());
        when(tasks.find(taskId)).thenReturn(Optional.of(providerDefault));

        assertThat(service.draft("review-1", "source")).isNotNull();
        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any());
        assertThat(context.getValue().model()).isEmpty();
        assertThat(context.getValue().effort()).isEmpty();
    }

    @Test
    void retainsScratchResourcesWhenProviderShutdownCannotBeVerified()
    {
        doThrow(new AgentUnavailableException("process remained active")).when(session).close();
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("could not be verified stopped");
        verify(resources, never()).delete(any());
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("previous lesson draft agent could not be verified stopped");
        verify(driver).open(any(), any());
    }

    @Test
    void rejectsOversizedModelOutputBeforeParsing()
    {
        completed("x".repeat(20 * 1024 + 1));
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("oversized lesson draft");
    }

    @Test
    void closesAndDeletesScratchWhenStartingTheProviderTurnFails()
    {
        when(session.start(any())).thenThrow(new AgentUnavailableException("Unable to start"));
        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to start");
        verify(session).close();
        verify(resources).delete(any());
    }

    @Test
    void deletesScratchAndAllowsRetryWhenOpeningTheProviderFails()
    {
        when(driver.open(any(), any())).thenThrow(new AgentUnavailableException("Unable to open")).thenReturn(session);

        assertThatThrownBy(() -> service.draft("review-1", "source"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to open");
        assertThat(service.draft("review-1", "source")).isNotNull();
        verify(resources, times(2)).delete(any());
    }

    private void completed(String output)
    {
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(
                AgentTurnResult.State.COMPLETED, Optional.of(output), Optional.empty(), Duration.ofMillis(20))));
    }
}
