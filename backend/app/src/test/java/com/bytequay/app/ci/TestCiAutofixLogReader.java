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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCiAutofixLogReader
{
    private static final String HEAD_SHA = "a".repeat(40);

    @Test
    void readsTheTurnsOwnLatestAnswerNewestFirst()
    {
        AgentEventService events = mock(AgentEventService.class);
        CiAutofixLogReader reader = new CiAutofixLogReader(mock(GitHubActionsClient.class), events);
        TaskId taskId = TaskId.random();
        TurnId turnId = TurnId.random();
        AgentEventSource child = new AgentEventSource(Optional.of("child"), Optional.of("root"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        // The newest page holds a subagent's answer; the task's own answer is on the older page.
        when(events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty())).thenReturn(new AgentHistoryPage(
                ImmutableList.of(answer(taskId, 3, turnId, child, "Child: tests pass")), OptionalLong.of(3), OptionalLong.empty(), 3));
        when(events.readHistory(taskId, OptionalLong.of(3), OptionalLong.empty())).thenReturn(new AgentHistoryPage(
                ImmutableList.of(answer(taskId, 1, turnId, AgentEventSource.root(), "Draft"), answer(taskId, 2, turnId, AgentEventSource.root(), "Fixed the build")),
                OptionalLong.empty(), OptionalLong.empty(), 3));

        assertThat(reader.readFinalMessage(taskId, turnId)).contains("Fixed the build");
    }

    private static AgentEventEnvelope answer(TaskId taskId, long sequence, TurnId turnId, AgentEventSource source, String text)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, taskId, Optional.of(turnId), source, Instant.EPOCH,
                new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, text));
    }

    @Test
    void boundsParallelDownloadsAndKeepsOrderedEvidenceWhenOneLogIsUnavailable()
            throws Exception
    {
        GitHubActionsClient gitHub = mock(GitHubActionsClient.class);
        CiAutofixLogReader reader = new CiAutofixLogReader(gitHub, mock(AgentEventService.class));
        TaskId taskId = TaskId.random();
        CiHeadSnapshot snapshot = snapshot();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch laterDownloaded = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        when(gitHub.readLog(any())).thenAnswer(invocation -> {
            ReadCiLogQuery query = invocation.getArgument(0);
            assertThat(query.taskId()).isEqualTo(taskId);
            assertThat(query.maximumBytes()).isEqualTo(1024 * 1024);
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                if (query.job().id() == 1) {
                    assertThat(laterDownloaded.await(5, TimeUnit.SECONDS)).isTrue();
                }
                if (query.job().id() == 5) {
                    laterDownloaded.countDown();
                }
                return query.job().id() == 2 ? BoundedCiLog.unavailable("GitHub returned HTTP 404") : log(query.job().id());
            }
            finally {
                active.decrementAndGet();
            }
        });

        List<FailureEvidence> failures;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> reader.readFailures(taskId, snapshot));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(active.get()).isEqualTo(4);
            }
            finally {
                release.countDown();
            }
            failures = pending.get(10, TimeUnit.SECONDS);
        }

        assertThat(maximumActive.get()).isEqualTo(4);
        assertThat(active.get()).isZero();
        verify(gitHub, times(50)).readLog(any());
        assertThat(failures).extracting(FailureEvidence::jobId).containsExactlyElementsOf(LongStream.rangeClosed(1, 50).boxed().collect(toImmutableList()));
        assertThat(failures.get(1).log()).isEmpty();
        assertThat(failures.get(1).summary()).contains("Job 2 failed at Compile", "log unavailable: GitHub returned HTTP 404");
        assertThat(failures.get(2).log().orElseThrow().truncated()).isTrue();
        assertThat(failures.get(3).log().orElseThrow().truncated()).isTrue();
        assertThat(failures.get(4).log().orElseThrow().truncated()).isTrue();

        CiAutofixAttempt attempt = CiAutofixAttempt.create(CiAutofixAttemptId.random(), CiAutofixRunId.random(), 1, HEAD_SHA, failures, Instant.EPOCH);
        assertThat(new CiAutofixPromptBuilder().build(snapshot, attempt))
                .hasSizeLessThan(2600)
                .contains("read_pull_request_ci", "read_ci_raw_log", "untrusted evidence")
                .doesNotContain("GitHub returned HTTP 404", "Job 2", "Failed jobs:", "workflow run");
    }

    private static BoundedCiLog log(long jobId)
    {
        return new BoundedCiLog(
                Optional.of(new BlobRef("blobs/job-" + jobId, "text/plain", 10)),
                10,
                jobId == 4,
                jobId == 5,
                jobId == 3,
                ImmutableList.of("error: compilation failed"),
                Optional.empty(), Optional.empty(), true);
    }

    private static CiHeadSnapshot snapshot()
    {
        return new CiHeadSnapshot(
                PullRequestRef.of("bytequay", "sample", 1),
                true,
                "author",
                RepoRef.of("bytequay", "sample"),
                "feature",
                HEAD_SHA,
                "main",
                "b".repeat(40),
                "https://github.com/bytequay/sample/pull/1",
                ImmutableList.of(workflow(100, 1, 30), workflow(200, 31, 52)));
    }

    private static CiWorkflowRun workflow(long workflowId, long firstJob, long lastJob)
    {
        ImmutableList.Builder<CiJob> jobs = ImmutableList.builder();
        jobs.add(new CiJob(workflowId, workflowId, "Passing job", 1, "completed", Optional.of("success"), "https://github.com/actions/jobs/" + workflowId, ImmutableList.of()));
        for (long jobId = firstJob; jobId <= lastJob; jobId++) {
            jobs.add(new CiJob(
                    jobId,
                    workflowId,
                    "Job " + jobId,
                    1,
                    "completed",
                    Optional.of("failure"),
                    "https://github.com/actions/jobs/" + jobId,
                    ImmutableList.of(new CiStep(1, "Compile", "completed", Optional.of("failure")))));
        }
        return new CiWorkflowRun(workflowId, workflowId, "Build", 1, "completed", Optional.of("failure"), HEAD_SHA, "https://github.com/actions/runs/" + workflowId, jobs.build());
    }

    @Test
    void computesStableBoundedSignatureFromProgramFacts()
    {
        CiJob job = failedJob();
        CiWorkflowRun workflow = failedWorkflow(job);
        BoundedCiLog firstLog = errorLog(ImmutableList.of("error: cannot find symbol", "build failed"));
        BoundedCiLog sameLog = errorLog(ImmutableList.of("error: cannot find symbol", "build failed"));

        String first = CiAutofixLogReader.failureSignature(workflow, job, firstLog);
        String second = CiAutofixLogReader.failureSignature(workflow, job, sameLog);
        String changed = CiAutofixLogReader.failureSignature(workflow, job, errorLog(ImmutableList.of("error: incompatible types", "build failed")));

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("ci-d92f27942a14baa8c53c3021492e7eb7ab62d7ed5d69d24e7d0671fe99a0a06b");
        assertThat(changed).isNotEqualTo(first);
        assertThat(CiAutofixLogReader.failureSignature(workflow, job, errorLog(ImmutableList.of("错误: café 😀"))))
                .isEqualTo("ci-b23706f0dba15d1f83dab5c3f8978948bb92e07ef60a99afe00c3f87cde14a46");
    }

    private static CiWorkflowRun failedWorkflow(CiJob job)
    {
        return new CiWorkflowRun(900, 80, "Build", 2, "completed", Optional.of("failure"), HEAD_SHA, "https://github.com/actions/runs/900", ImmutableList.of(job));
    }

    private static CiJob failedJob()
    {
        return new CiJob(
                902,
                900,
                "Compile",
                2,
                "completed",
                Optional.of("failure"),
                "https://github.com/actions/jobs/902",
                ImmutableList.of(new CiStep(3, "Compile Java", "completed", Optional.of("failure"))));
    }

    private static BoundedCiLog errorLog(List<String> errors)
    {
        return new BoundedCiLog(Optional.of(new BlobRef("blobs/log", "text/plain", 10)), 10, false, false, false, errors, Optional.empty(), Optional.empty(), true);
    }
}
