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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRawLogPage;
import com.bytequay.app.ci.CiRawLogStore;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestPullRequestCiBridgeResolverImpl
{
    private static final Instant NOW = Instant.EPOCH;
    private static final String HEAD = "b".repeat(40);
    private static final String NEXT = "c".repeat(40);
    private static final String BASE = "a".repeat(40);
    private static final String BRANCH = "alice/delta-metadata";
    private static final RepoRef FORK = new RepoRef("alice", "trino");
    private static final RepoRef UPSTREAM = new RepoRef("trinodb", "trino");
    private static final PullRequestRef PR = new PullRequestRef("trinodb", "trino", 31105);
    private static final String COMMIT_FAILURE = """
            Complete job name: check-commit-messages
            Commit subjects must not start with a lowercase letter or end with a period.
            Common past-tense leading verbs must use their imperative form.
            Commit subjects should be at most 50 characters; this check fails subjects over 60 characters.

            891680591dd8 Use Guava collector and identity function in Delta Lake metadata
              subject: 64 characters

            ##[error]Process completed with exit code 1.
            """;

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final ManagedWorktreeService worktrees = mock(ManagedWorktreeService.class, withSettings().mockMaker(MockMakers.INLINE));
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PullRequestService catalog = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final GitHubActionsClient actions = mock(GitHubActionsClient.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final CiRawLogStore rawLogs = mock(CiRawLogStore.class);
    private final CiAutofixStore ciRuns = mock(CiAutofixStore.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PullRequestCiBridgeResolverImpl resolver = new PullRequestCiBridgeResolverImpl(
            tasks, workspaces, worktrees, pullRequests, catalog, details, actions, logs, mapper, rawLogs, ciRuns, mock(AgentTurnStore.class), mock(ObjectProvider.class));
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", RepositoryIdentity.valueOf(FORK.fullName()),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, BASE);
    private final AgentTask task = AgentTask.created(taskId, true, "Fix Delta Lake metadata", TitleSource.USER, AgentLaneId.CODEX_CLI,
            worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    private final CiHeadSnapshot failing = snapshot(PR, HEAD, 1, "check-commit-messages", "failure");

    @BeforeEach
    void setUp()
    {
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(new Workspace(
                "workspace", "Trino", 1, FORK, Optional.of(2L), Optional.of(UPSTREAM), 2, UPSTREAM, Workspace.Topology.FORK,
                "master", "https://github.com/alice/trino.git", Optional.of("master"), Optional.of("https://github.com/trinodb/trino.git"),
                "/tmp/trino", Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW)));
        when(worktrees.currentBranch(worktree)).thenReturn(Optional.of(BRANCH));
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, HEAD, false, false));
        when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of(row(PR)));
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(failing);
    }

    @Test
    void readsTheForkPullRequestsFailingJobAndTheActualCommitSubjectDiagnostic()
            throws Exception
    {
        storeLog(COMMIT_FAILURE, false);
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        verifyNoInteractions(actions);

        JsonNode summary = read(bridge, Optional.empty());
        assertThat(summary.path("status").asText()).isEqualTo("available");
        assertThat(summary.path("untrusted").asBoolean()).isTrue();
        assertThat(summary.at("/pullRequest/repository").asText()).isEqualTo(UPSTREAM.fullName());
        assertThat(summary.at("/pullRequest/headRepository").asText()).isEqualTo(FORK.fullName());
        assertThat(summary.at("/pullRequest/number").asInt()).isEqualTo(PR.number());
        assertThat(summary.at("/pullRequest/headSha").asText()).isEqualTo(HEAD);
        assertThat(summary.at("/actions/green").asBoolean()).isFalse();
        assertThat(summary.at("/actions/requiredChecksVerified").asBoolean()).isFalse();
        assertThat(summary.at("/actions/workflows/0/jobs/0/name").asText()).isEqualTo("check-commit-messages");
        assertThat(summary.at("/actions/workflows/0/jobs/0/failedSteps").toString()).contains("Check commit messages");
        verify(actions, never()).readLog(any());

        long jobId = summary.at("/actions/workflows/0/jobs/0/id").asLong();
        JsonNode detail = read(bridge, Optional.of(jobId));
        assertThat(detail.path("status").asText()).isEqualTo("available");
        assertThat(detail.at("/log/excerpt").asText()).contains(
                "Commit subjects should be at most 50 characters; this check fails subjects over 60 characters.",
                "891680591dd8 Use Guava collector and identity function in Delta Lake metadata", "subject: 64 characters");
        assertThat(detail.at("/log/terminalErrorLines").toString()).contains("Process completed with exit code 1");
        ArgumentCaptor<ReadCiLogQuery> query = ArgumentCaptor.forClass(ReadCiLogQuery.class);
        verify(actions).readLog(query.capture());
        assertThat(query.getValue().taskId()).isEqualTo(taskId);
        assertThat(query.getValue().pullRequest()).isEqualTo(PR);
        assertThat(query.getValue().workflowRun().headSha()).isEqualTo(HEAD);
        assertThat(query.getValue().job().attempt()).isEqualTo(1);
        assertThat(query.getValue().maximumBytes()).isLessThanOrEqualTo(1024 * 1024);
        verify(actions, never()).rerunFailedJobs(any());
    }

    @Test
    void repositoryCapitalizationDoesNotHideTheTasksForkPullRequest()
            throws Exception
    {
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(new CiHeadSnapshot(
                PR, true, "alice", new RepoRef("ALIce", "TriNo"), BRANCH, HEAD, "master", BASE, url(PR), failing.workflowRuns()));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.empty());

        assertThat(result.path("status").asText()).isEqualTo("available");
        assertThat(result.at("/pullRequest/number").asInt()).isEqualTo(PR.number());
    }

    @Test
    void discoversAPullRequestCreatedAfterTheSameToolWasBound()
            throws Exception
    {
        when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of());
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(read(bridge, Optional.empty()).path("status").asText()).isEqualTo("no_pull_request");
        verify(catalog).syncRepositoryBranch(UPSTREAM, FORK.owner(), BRANCH);
        verifyNoInteractions(actions);

        when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of(row(PR)));
        assertThat(read(bridge, Optional.empty()).at("/pullRequest/number").asInt()).isEqualTo(PR.number());
    }

    @Test
    void discoversThePublishedBranchWithoutWaitingForThePullRequestPanelCache()
            throws Exception
    {
        when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of());
        doAnswer(ignored -> {
            when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of(row(PR)));
            return null;
        }).when(catalog).syncRepositoryBranch(UPSTREAM, FORK.owner(), BRANCH);

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.empty());

        assertThat(result.path("status").asText()).isEqualTo("available");
        assertThat(result.at("/pullRequest/number").asInt()).isEqualTo(PR.number());
        verify(catalog).syncRepositoryBranch(UPSTREAM, FORK.owner(), BRANCH);
        verify(actions).inspect(new InspectCiHeadQuery(PR));
    }

    @Test
    void refusesAmbiguousMatchingPullRequestsWithoutDownloadingLogs()
            throws Exception
    {
        PullRequestRef other = new PullRequestRef(UPSTREAM.owner(), UPSTREAM.repo(), PR.number() + 1);
        when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of(row(PR), row(other)));
        when(actions.inspect(new InspectCiHeadQuery(other))).thenReturn(snapshot(other, HEAD, 1, "checks", "failure"));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.path("status").asText()).isEqualTo("ambiguous_pull_request");
        verify(actions, never()).readLog(any());
    }

    @Test
    void aCachedMatchDoesNotHideAnotherPullRequestCreatedSinceThePanelRefreshed()
            throws Exception
    {
        PullRequestRef other = new PullRequestRef(UPSTREAM.owner(), UPSTREAM.repo(), PR.number() + 1);
        doAnswer(ignored -> {
            when(pullRequests.findByHeadRef(UPSTREAM.fullName(), BRANCH)).thenReturn(ImmutableList.of(row(PR), row(other)));
            return null;
        }).when(catalog).syncRepositoryBranch(UPSTREAM, FORK.owner(), BRANCH);
        when(actions.inspect(new InspectCiHeadQuery(other))).thenReturn(snapshot(other, HEAD, 1, "checks", "failure"));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.path("status").asText()).isEqualTo("ambiguous_pull_request");
        verify(actions, never()).readLog(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void onlyAnObservedFailedJobCanDownloadALog(long jobId)
            throws Exception
    {
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(snapshot(PR, HEAD, 1, "passing-check", "success"));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(jobId));

        assertThat(result.path("status").asText()).isEqualTo("unknown_job");
        verify(actions, never()).readLog(any());
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"head", "base", "attempt", "closed"})
    void discardsALogWhenItsPullRequestOrAttemptChangesDuringTheRead(String change)
            throws Exception
    {
        storeLog(COMMIT_FAILURE, false);
        CiHeadSnapshot after = switch (change) {
            case "head" -> snapshot(PR, NEXT, 1, "check-commit-messages", "failure");
            case "base" -> new CiHeadSnapshot(PR, true, "alice", FORK, BRANCH, HEAD, "master", NEXT, url(PR), failing.workflowRuns());
            case "attempt" -> snapshot(PR, HEAD, 2, "check-commit-messages", "failure");
            case "closed" -> new CiHeadSnapshot(PR, false, "alice", FORK, BRANCH, HEAD, "master", BASE, url(PR), failing.workflowRuns());
            default -> throw new AssertionError(change);
        };
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(failing, after);

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.path("status").asText()).isEqualTo("stale");
        assertThat(result.has("log")).isFalse();
        assertThat(result.toString()).doesNotContain("891680591dd8", "subject: 64 characters");
    }

    @Test
    void anOlderJobAttemptCannotDownloadUnderANewerWorkflowAttempt()
            throws Exception
    {
        CiWorkflowRun current = new CiWorkflowRun(100, 10, "Build", 2, "completed", Optional.of("failure"), HEAD,
                "https://github.com/trinodb/trino/actions/runs/100", failing.jobs());
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(new CiHeadSnapshot(
                PR, true, "alice", FORK, BRANCH, HEAD, "master", BASE, url(PR), ImmutableList.of(current)));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.path("status").asText()).isEqualTo("unknown_job");
        verify(actions, never()).readLog(any());
    }

    @Test
    void closingTheTaskRevokesAnAlreadyResolvedReader()
            throws Exception
    {
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        when(tasks.find(taskId)).thenReturn(Optional.of(task.fail("Closed", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)))));

        assertThat(resolver.resolve(taskId.toString())).isEmpty();
        assertThat(read(bridge, Optional.of(201L)).path("status").asText()).isEqualTo("unavailable");
        verifyNoInteractions(actions, logs, catalog);
    }

    @Test
    void doesNotReturnEvidenceWhenTheTaskClosesDuringTheDownload()
            throws Exception
    {
        storeLog(COMMIT_FAILURE, false);
        doAnswer(ignored -> {
            when(tasks.find(taskId)).thenReturn(Optional.of(task.fail("Closed", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)))));
            return BoundedCiLog.unavailable("Log expired");
        }).when(actions).readLog(any());

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.path("status").asText()).isEqualTo("unavailable");
        assertThat(result.has("log")).isFalse();
    }

    @Test
    void reportsUnavailableLogsWithoutInventingPassingChecks()
            throws Exception
    {
        when(actions.readLog(any())).thenReturn(BoundedCiLog.unavailable("This Actions log has expired"));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));

        assertThat(result.at("/log/unavailableReason").asText()).contains("expired");
        assertThat(result.path("status").asText()).isEqualTo("available");
        assertThat(result.at("/log/excerpt").asText()).isEmpty();
        verifyNoInteractions(logs);
    }

    @Test
    void doesNotExposeAuthenticatedProviderErrorsToTheAgent()
            throws Exception
    {
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenThrow(new IllegalStateException("Request failed: https://token-secret@github.com/trinodb/trino"));

        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.empty());

        assertThat(result.path("status").asText()).isEqualTo("unavailable");
        assertThat(result.toString()).doesNotContain("token-secret", "https://token-secret@", "Request failed");
        verify(actions, never()).readLog(any());
    }

    @Test
    void cachedPanelChecksCarryFreshnessAndCannotCrossHeadRevisions()
            throws Exception
    {
        when(details.find(PR.number())).thenReturn(Optional.of(cached(HEAD)));
        when(details.findSyncedAt(UPSTREAM.fullName(), PR.number())).thenReturn(Optional.of(NOW));
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();

        JsonNode sameHead = read(bridge, Optional.empty());
        assertThat(sameHead.at("/cachedChecks/available").asBoolean()).isTrue();
        assertThat(sameHead.at("/cachedChecks/cached").asBoolean()).isTrue();
        assertThat(sameHead.at("/cachedChecks/syncedAt").asText()).isEqualTo(NOW.toString());
        assertThat(sameHead.at("/cachedChecks/checksComplete").asBoolean()).isFalse();
        assertThat(sameHead.at("/cachedChecks/checks/0/name").asText()).isEqualTo("external-style-check");
        assertThat(sameHead.at("/cachedChecks/checks/0/status").asText()).isEqualTo("completed");
        assertThat(sameHead.at("/cachedChecks/checks/0/conclusion").asText()).isEqualTo("failure");
        assertThat(sameHead.at("/cachedChecks/checks/0/source").asText()).isEqualTo("status");

        when(details.find(PR.number())).thenReturn(Optional.of(cached(NEXT)));
        JsonNode otherHead = read(bridge, Optional.empty());
        assertThat(otherHead.at("/cachedChecks/available").asBoolean()).isFalse();
        assertThat(otherHead.toString()).doesNotContain("external-style-check");
    }

    @Test
    void boundsJsonEscapingAndKeepsTheFailureAtTheEndOfALargeLog()
            throws Exception
    {
        storeLog("\u0001".repeat(80_000) + COMMIT_FAILURE, true);
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();

        String output = bridge.read().apply(Optional.of(201L));
        JsonNode result = mapper.readTree(output);

        assertThat(output.length()).isLessThanOrEqualTo(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH);
        assertThat(result.at("/log/excerpt").asText()).contains("subject: 64 characters");
        assertThat(result.at("/log/truncated").asBoolean()).isTrue();
        assertThat(result.at("/log/downloadTruncated").asBoolean()).isTrue();
        assertThat(result.at("/log/excerptCompacted").asBoolean()).isTrue();
        assertThat(result.at("/log/excerptOmittedEnd").asBoolean()).isTrue();
    }

    @Test
    void boundsEscapedCheckNamesAndMarksOmittedWorkflows()
            throws Exception
    {
        List<CiWorkflowRun> workflows = IntStream.range(1, 501).mapToObj(id -> {
            CiJob job = new CiJob(1000 + id, id, "\u0001".repeat(400), 1, "completed", Optional.of("failure"),
                    "https://github.com/trinodb/trino/actions/runs/" + id, ImmutableList.of());
            return new CiWorkflowRun(id, id, "\u0001".repeat(400), 1, "completed", Optional.of("failure"), HEAD,
                    "https://github.com/trinodb/trino/actions/runs/" + id, ImmutableList.of(job));
        }).collect(ImmutableList.toImmutableList());
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(new CiHeadSnapshot(
                PR, true, "alice", FORK, BRANCH, HEAD, "master", BASE, url(PR), workflows));

        String output = resolver.resolve(taskId.toString()).orElseThrow().read().apply(Optional.empty());
        JsonNode result = mapper.readTree(output);

        assertThat(output.length()).isLessThanOrEqualTo(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH);
        assertThat(result.at("/actions/workflowsOmitted").asInt()).isGreaterThan(0);
        assertThat(result.path("untrusted").asBoolean()).isTrue();
    }

    @Test
    void aLargePassingWorkflowCannotHideTheFailingJobInALaterWorkflow()
            throws Exception
    {
        List<CiJob> jobs = IntStream.range(1, 501).mapToObj(id -> new CiJob(10_000 + id, 99, "\u0001".repeat(400), 1,
                "completed", Optional.of("success"), "https://github.com/trinodb/trino/actions/runs/99/job/" + id, ImmutableList.of()))
                .collect(ImmutableList.toImmutableList());
        CiWorkflowRun passing = new CiWorkflowRun(99, 9, "Passing workflow", 1, "completed", Optional.of("success"), HEAD,
                "https://github.com/trinodb/trino/actions/runs/99", jobs);
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(new CiHeadSnapshot(PR, true, "alice", FORK, BRANCH, HEAD, "master", BASE, url(PR),
                ImmutableList.of(passing, failing.workflowRuns().getFirst())));

        String output = resolver.resolve(taskId.toString()).orElseThrow().read().apply(Optional.empty());
        JsonNode result = mapper.readTree(output);

        assertThat(output.length()).isLessThanOrEqualTo(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH);
        assertThat(result.at("/actions/workflows/0/jobs/0/id").asLong()).isEqualTo(201);
        assertThat(result.at("/actions/workflows/0/jobs/0/name").asText()).isEqualTo("check-commit-messages");
        assertThat(result.at("/actions/jobsOmitted").asInt()).isGreaterThan(0);
    }

    @Test
    void keepsASelectedStackAbove32KiBIntactWithinTheToolResponse()
            throws Exception
    {
        String failure = "[ERROR] ExampleTest.testQuery <<< ERROR!\n"
                + "java.lang.IllegalStateException: worker unavailable\n"
                + "    at Worker.repeat(Worker.java:42)\n".repeat(1000)
                + "Caused by: java.net.ConnectException: refused\n    at Client.connect(Client.java:55)\n";
        storeLog("[INFO] noise\n".repeat(4000) + failure + "[INFO] done\n", false);
        String output = resolver.resolve(taskId.toString()).orElseThrow().read().apply(Optional.of(201L));
        assertThat(output.length()).isLessThanOrEqualTo(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH);
        assertThat(mapper.readTree(output).at("/log/excerpt").asText()).contains(failure);
    }

    @Test
    void rawToolReadsOnlyTaskCachedPagesAndRevokesAccessAfterCompletion()
            throws Exception
    {
        String id = "a".repeat(64);
        when(rawLogs.page(eq(taskId), eq(id), eq(0L), anyInt())).thenAnswer(invocation -> {
            int bytes = invocation.getArgument(3);
            return new CiRawLogPage("\u0001".repeat(bytes), bytes, 100000);
        });
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        String output = bridge.readRaw().apply(id, 0L);
        JsonNode first = mapper.readTree(output);
        assertThat(output.length()).isLessThanOrEqualTo(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH);
        assertThat(first.path("text").asText()).isEqualTo("\u0001".repeat(first.path("nextOffset").asInt()));
        assertThat(first.path("complete").asBoolean()).isFalse();
        long next = first.path("nextOffset").asLong();
        when(rawLogs.page(eq(taskId), eq(id), eq(next), anyInt())).thenReturn(new CiRawLogPage("remaining original text", 100000, 100000));
        JsonNode last = mapper.readTree(bridge.readRaw().apply(id, next));
        assertThat(last.path("complete").asBoolean()).isTrue();
        assertThat(last.path("nextOffset").isNull()).isTrue();
        assertThat(last.path("text").asText()).isEqualTo("remaining original text");
        verifyNoInteractions(actions, catalog, logs, pullRequests);
        when(tasks.find(taskId)).thenReturn(Optional.of(task.fail("Closed", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)))));
        assertThat(mapper.readTree(bridge.readRaw().apply(id, 0L)).path("status").asText()).isEqualTo("unavailable");
    }

    @Test
    void autofixToolsUseTheAuthorizedPullRequestAndHeadWithoutBranchDiscovery()
            throws Exception
    {
        AgentTask autofix = AgentTask.created(taskId, false, "Fix CI", TitleSource.USER, AgentLaneId.CODEX_CLI,
                worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.CI_AUTOFIX), NOW);
        CiAutofixRun run = mock(CiAutofixRun.class, withSettings().mockMaker(MockMakers.INLINE));
        when(run.scope()).thenReturn(new CiAutofixRun.Scope(PR, FORK, "workspace", Optional.of(FORK), "alice", BRANCH, "master", "codex-cli", Optional.empty()));
        when(run.expectedRemoteHead()).thenReturn(HEAD);
        when(run.status()).thenReturn(CiAutofixRun.Status.ACTIVE);
        when(tasks.find(taskId)).thenReturn(Optional.of(autofix));
        when(ciRuns.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(run));
        PullRequestCiBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(read(bridge, Optional.empty()).path("status").asText()).isEqualTo("available");
        verifyNoInteractions(catalog, pullRequests, details);
        when(actions.inspect(new InspectCiHeadQuery(PR))).thenReturn(snapshot(PR, NEXT, 1, "test", "failure"));
        assertThat(read(bridge, Optional.of(201L)).path("status").asText()).isEqualTo("stale");
        verify(actions, never()).readLog(any());
    }

    @Test
    void returnsRawIdentifierAndExplicitTailGuidanceWhenNoDiagnosticsMatched()
            throws Exception
    {
        String id = "a".repeat(64);
        String source = "original beginning\n" + "downloaded artifact\n".repeat(4000) + "command exited\n";
        BlobRef blob = new BlobRef("ci/excerpt.log", "text/plain", 0);
        when(actions.readLog(any())).thenReturn(new BoundedCiLog(Optional.of(blob), 0, false, false, true,
                ImmutableList.of(), Optional.empty(), Optional.of(id), false));
        when(rawLogs.read(taskId, id)).thenReturn(source);
        JsonNode result = read(resolver.resolve(taskId.toString()).orElseThrow(), Optional.of(201L));
        assertThat(result.at("/log/diagnosticsMatched").asBoolean()).isFalse();
        assertThat(result.at("/log/rawLogId").asText()).isEqualTo(id);
        assertThat(result.at("/log/message").asText()).contains("No diagnostic", "tail", "32 KiB", "read_ci_raw_log");
        assertThat(result.at("/log/excerpt").asText()).isEqualTo(source.substring(source.length() - 32768));
        verifyNoInteractions(logs);
    }

    private JsonNode read(PullRequestCiBridge bridge, Optional<Long> jobId)
            throws Exception
    {
        return mapper.readTree(bridge.read().apply(jobId));
    }

    private void storeLog(String text, boolean truncated)
    {
        byte[] bytes = text.getBytes(UTF_8);
        BlobRef blob = new BlobRef("ci/check-commit-messages.log", "text/plain", bytes.length);
        when(actions.readLog(any())).thenReturn(new BoundedCiLog(Optional.of(blob), bytes.length, truncated, false, false,
                ImmutableList.of("##[error]Process completed with exit code 1."), Optional.empty(), Optional.empty(), true));
        when(logs.readBlob(taskId, blob)).thenAnswer(ignored -> ByteBuffer.wrap(bytes));
    }

    private static CiHeadSnapshot snapshot(PullRequestRef ref, String head, int attempt, String name, String conclusion)
    {
        long workflowId = 100;
        CiJob job = new CiJob(200 + attempt, workflowId, name, attempt, "completed", Optional.of(conclusion),
                "https://github.com/trinodb/trino/actions/runs/100/job/" + (200 + attempt),
                ImmutableList.of(new CiStep(1, "Check commit messages", "completed", Optional.of(conclusion))));
        CiWorkflowRun workflow = new CiWorkflowRun(workflowId, 10, "Build", attempt, "completed", Optional.of(conclusion), head,
                "https://github.com/trinodb/trino/actions/runs/100", ImmutableList.of(job));
        return new CiHeadSnapshot(ref, true, "alice", FORK, BRANCH, head, "master", BASE, url(ref), ImmutableList.of(workflow));
    }

    private static StoredPrDetail cached(String head)
    {
        PrRawDetail raw = new PrRawDetail(null, null, null, null, null, null, null, null, "", ImmutableList.of(), null, ImmutableList.of(), null, true, false, "blocked", 1, 0, 1,
                0, ImmutableList.of(), head, BRANCH, null);
        PrCheckRunState check = new PrCheckRunState(400L, "external-style-check", "completed", "failure", "https://ci.example.invalid/400", "status", NOW, NOW);
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(check), ImmutableList.of(StoredPrDetail.CHECKS));
    }

    private static PullRequest row(PullRequestRef ref)
    {
        return new PullRequest(ref.number(), ref.owner() + "/" + ref.repo(), ref.number(), "Fix Delta Lake metadata", "alice", url(ref), NOW, NOW,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), true, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.FAILING,
                1, 0, 0, "open", null, null, false, "blocked", NOW, BRANCH, null, null, null, null);
    }

    private static String url(PullRequestRef ref)
    {
        return "https://github.com/" + ref.owner() + "/" + ref.repo() + "/pull/" + ref.number();
    }
}
