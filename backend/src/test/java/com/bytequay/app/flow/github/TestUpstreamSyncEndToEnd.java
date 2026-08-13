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
package com.bytequay.app.flow.github;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.ci.CiObservationCoordinator;
import com.bytequay.app.flow.gate.UserGateRecords.UserGate;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.CiAutofixDispatcher;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.LocalChecks;
import com.bytequay.app.flow.runtime.LocalChecks.ProfileDefinition;
import com.bytequay.app.flow.runtime.NewFlowAgentBridgeConfiguration;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches;
import com.bytequay.app.flow.runtime.NewFlowConfiguration;
import com.bytequay.app.flow.runtime.NewFlowEngineResolver;
import com.bytequay.app.flow.runtime.UpstreamSyncCommands;
import com.bytequay.app.flow.runtime.UpstreamSyncConfiguration;
import com.bytequay.app.flow.runtime.UpstreamSyncPolicyPublisher;
import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.upstream.RunLinePublisher;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncClosureObserver;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PrResult;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RepairPlacementPolicy;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.SelectedCommit;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncViewConfiguration;
import com.bytequay.app.flow.upstream.UpstreamSyncViews;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_ACTIONS;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.ciObservationExecutor;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialLane;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialRepositoryObservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bridge: an upstream cherry-pick range reaches CI Autofix.
 *
 * <p>CI Autofix has exactly one entry point, and it is keyed on a
 * gate-authorized publish receipt. This trace exists to prove the range
 * arrives through that door rather than beside it — the picks land in the
 * Task's own worktree, publication goes through the ordinary
 * {@code INITIAL_PUBLISH} effect, and the receipt it produces installs the CI
 * observation watch by itself.
 */
@SuppressWarnings("StringConcatToTextBlock")
final class TestUpstreamSyncEndToEnd
{
    private static final Duration TTL = Duration.ofMinutes(30);

    @TempDir
    private Path temporaryDirectory;

    private String cleanCommit;
    private String secondConflictingCommit;
    private String conflictingCommit;
    private String alreadyPresentCommit;
    private List<String> confirmedRange;

    @Test
    void aPickedRangePublishesThroughTheGateAndCiAutofixAdoptsThePr()
            throws Exception
    {
        Path upstreamRepository = temporaryDirectory.resolve("upstream");
        Path repository = temporaryDirectory.resolve("target");
        Path worktrees = temporaryDirectory.resolve("worktrees");
        Path database = temporaryDirectory.resolve("new-flow.db");
        Path primaryDatabase = temporaryDirectory.resolve("primary.db");
        initializeRepositories(upstreamRepository, repository);
        assertThat(confirmedRange).allSatisfy(sha ->
                assertThat(hasCommit(repository, sha)).isFalse());

        DataSource primary = new DriverManagerDataSource(
                "jdbc:sqlite:" + primaryDatabase);
        new JdbcTemplate(primary).execute(
                "CREATE TABLE legacy_owner (value TEXT NOT NULL)");
        byte[] primaryBefore = Files.readAllBytes(primaryDatabase);

        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("octocat", "bytequay")).thenReturn(Optional.of(
                new WatchedRepo(
                        1, "octocat", "bytequay", 0,
                        repository.toString(), null, null)));
        TurnRunner runner = mock(TurnRunner.class);
        NewFlowEngineResolver engines = mock(NewFlowEngineResolver.class);
        UpstreamSyncPolicyPublisher policies = mock(
                UpstreamSyncPolicyPublisher.class);
        when(engines.resolve(any(FlowRuntimeRecords.AgentRun.class)))
                .thenReturn(NewFlowAgentLaunches.Config.api(
                        "openai", TurnSpec.Transport.OPENAI_COMPAT,
                        "https://models.example.test/v1/chat/completions",
                        "test-model", "medium", "openai", "default api",
                        1024, 2));
        CountDownLatch reviewerEntered = new CountDownLatch(1);
        CountDownLatch policyReady = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        AtomicReference<Throwable> modelFailure = new AtomicReference<>();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            try {
                return runModelTurn(
                        turns.incrementAndGet(), invocation.getArgument(1),
                        reviewerEntered, policyReady);
            }
            catch (Throwable failure) {
                modelFailure.set(failure);
                throw failure;
            }
        });

        // What a watcher would see while the repair turn is still going.
        List<String> liveLines = new CopyOnWriteArrayList<>();
        AtomicReference<FlowRuntime> runtimeRef = new AtomicReference<>();
        GitHubInitialRepositoryObserver initialObserver = mock(
                GitHubInitialRepositoryObserver.class);
        when(initialObserver.observe(anyString())).thenAnswer(invocation ->
                initialRepositoryObservation(
                        runtimeRef.get(), invocation.getArgument(0),
                        "101", "101", "repo-secret".toCharArray(),
                        new AtomicInteger()));

        new ApplicationContextRunner()
                .withBean("legacyPrimaryDataSource", DataSource.class,
                        () -> primary,
                        definition -> definition.setPrimary(true))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(
                        "testUpstreamSyncPolicyPublisher",
                        UpstreamSyncPolicyPublisher.class,
                        () -> policies,
                        definition -> definition.setPrimary(true))
                .withBean(WatchedRepoStore.class, () -> watched)
                .withBean(CredentialStore.class, TestUpstreamSyncEndToEnd::credentials)
                .withBean(TurnRunner.class, () -> runner)
                .withBean(RunLinePublisher.class,
                        () -> (runId, line) -> liveLines.add(line))
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(primary))
                .withBean(WorkspaceEngineSettings.class,
                        () -> mock(WorkspaceEngineSettings.class))
                .withBean(WorkModelService.class,
                        () -> mock(WorkModelService.class))
                .withBean(
                        "testEngineResolver", NewFlowEngineResolver.class,
                        () -> engines, definition -> definition.setPrimary(true))
                .withBean(
                        "testInitialRepositoryObserver",
                        GitHubInitialRepositoryObserver.class,
                        () -> initialObserver,
                        definition -> definition.setPrimary(true))
                .withPropertyValues(
                        "bytequay.new-flow.database-path=" + database,
                        "bytequay.new-flow.worktree-root=" + worktrees)
                .withUserConfiguration(
                        NewFlowConfiguration.class,
                        NewFlowAgentBridgeConfiguration.class,
                        UpstreamSyncConfiguration.class,
                        UpstreamSyncViewConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlowRuntime runtime = context.getBean(FlowRuntime.class);
                    runtimeRef.set(runtime);
                    context.getBean(GitHubInitialPublishDispatcher.class)
                            .close();
                    context.getBean(GitHubCiUpdateDispatcher.class).close();
                    context.getBean(GitHubCiObservationDispatcher.class)
                            .close();

                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                            "newFlowDataSource", DataSource.class));
                    context.getBean(LocalChecks.class).recordPolicy(
                            "octocat/bytequay", null,
                            "upstream-local-policy:v1",
                            "upstream-local-policy-digest:v1",
                            List.of(new ProfileDefinition(
                                    "compile", List.of(
                                            "/bin/sh", "-c",
                                            "test -f local-check-ready || "
                                                    + "{ echo missing local-check-ready; exit 1; }"), ".",
                                    List.of(), Duration.ofSeconds(5),
                                    List.of(
                                            GateIntent.INITIAL_PUBLISH,
                                            GateIntent.CI_UPDATE))));

                    UpstreamSyncCommands commands = context.getBean(
                            UpstreamSyncCommands.class);
                    UpstreamSync upstreamSync = context.getBean(
                            UpstreamSync.class);
                    UpstreamSyncCommands.StartReceipt started =
                            commands.startConfirmed(
                                    "upstream-request", "octocat/bytequay",
                                    "Bring the selected upstream commits onto "
                                            + "this fork",
                                    "octocat/upstream", "source-start",
                                    "upstream", "main",
                                    selection(confirmedRange),
                                    "user-1", upstreamRepository, repository);
                    Task task = started.task();
                    assertThat(confirmedRange).hasSize(103);
                    assertThat(confirmedRange).allSatisfy(sha ->
                            assertThat(hasCommit(repository, sha)).isTrue());
                    // Enqueue is idempotent on the request key: a repeated
                    // confirmation adopts the stored run, never a second range
                    // over the same Task.
                    assertThat(commands.startConfirmed(
                            "upstream-request", "octocat/bytequay",
                            "Bring the selected upstream commits onto this "
                                    + "fork",
                            "octocat/upstream", "source-start", "upstream",
                            "main", selection(confirmedRange), "user-1",
                            upstreamRepository, repository).run().runId())
                            .isEqualTo(started.run().runId());
                    assertThat(upstreamSync.repairPlacement(task.taskId()))
                            .isEqualTo(RepairPlacementPolicy.ATTRIBUTED_FIXUP);

                    try {
                        await(reviewerEntered);
                    }
                    catch (AssertionError stalled) {
                        // A stalled range says nothing on its own; the run's
                        // own records and the turn's error reference say why.
                        throw new AssertionError(
                                "run=" + upstreamSync.run(
                                        started.run().runId())
                                + " picks=" + upstreamSync.picks(
                                        started.run().runId())
                                + " agentErrors=" + jdbc.queryForList(
                                        "SELECT error_ref FROM "
                                                + "flow_runtime_agent_result",
                                        String.class)
                                + " turns=" + turns
                                + " modelFailure=" + modelFailure.get(),
                                stalled);
                    }
                    Task withPr = awaitValue(() -> runtime.task(task.taskId())
                            .filter(current -> current.prId() != null));
                    PullRequestSubject localPr = runtime.pullRequest(
                            withPr.prId()).orElseThrow();
                    CiAutofix autofix = context.getBean(CiAutofix.class);
                    autofix.recordPolicy(
                            task.repositoryId(), localPr.scopeKey(),
                            localPr.targetBaseRef(), "upstream-required-ci:v1",
                            "upstream-required-ci-digest:v1",
                            PolicyResolution.RESOLVED, null,
                            List.of("GITHUB_CHECK:7:build"),
                            List.of("SUCCESS"));
                    policyReady.countDown();

                    UserGates gates = context.getBean(UserGates.class);
                    UserGate initialGate;
                    try {
                        initialGate = awaitValue(() ->
                                gates.initialGate(localPr.prId()));
                    }
                    catch (AssertionError stalled) {
                        throw new AssertionError(
                                "run=" + upstreamSync.run(
                                        started.run().runId())
                                + " agentResults=" + jdbc.queryForList(
                                        "SELECT terminal_outcome, error_ref "
                                                + "FROM flow_runtime_agent_result")
                                + " turns=" + turns
                                + " modelFailure=" + modelFailure.get(),
                                stalled);
                    }

                    assertPickedSeries(upstreamSync, started.run().runId());
                    assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM flow_runtime_local_check_run",
                            Integer.class)).isEqualTo(1);
                    assertThat(history(Path.of(task.worktreePath())))
                            .doesNotContain("<<<<<<<", "=======", ">>>>>>>");

                    DisplayedGate display = displayed(jdbc, initialGate);
                    String verificationRef = upstreamSync.run(
                            started.run().runId()).orElseThrow()
                            .verificationRef();
                    assertThat(jdbc.queryForMap(
                            "SELECT owner_verification_run_id, "
                                    + "owner_verification_ref FROM "
                                    + "flow_user_gate_initial_publish_subject "
                                    + "WHERE subject_digest = ?",
                            display.subjectDigest()))
                            .containsEntry("owner_verification_run_id",
                                    started.run().runId())
                            .containsEntry("owner_verification_ref",
                                    verificationRef);
                    var authorization = gates.authorizeInitialPublish(
                            initialGate.gateId(), display.revision(),
                            display.subjectDigest(), display.actionDigest(),
                            "upstream-initial-authorization");
                    GitHubEffects effects = context.getBean(
                            GitHubEffects.class);
                    var plan = effects.initialPublishPlan(
                            authorization.planId()).orElseThrow();
                    Clock clock = context.getBean("newFlowClock", Clock.class);
                    var lane = initialLane(
                            runtime, gates, effects, plan, clock);
                    try (var dispatcher = new GitHubInitialPublishDispatcher(
                            runtime, gates, effects, lane.executor(),
                            new GitHubInitialPublishDispatcher.Config(
                                    "upstream-initial-publisher", TTL,
                                    Duration.ofMillis(10), 1))) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    assertThat(lane.pushes()).hasValue(1);
                    assertThat(lane.posts()).hasValue(1);

                    // THE BRIDGE. CI Autofix is reachable only through a
                    // gate-authorized publish receipt, and this is that
                    // receipt installing the watch by itself.
                    assertThat(effects.initialPublishStepReceipts(
                            plan.planId())).hasSize(2);
                    String receiptId = jdbc.queryForObject(
                            """
                            SELECT receipt_id
                            FROM flow_github_initial_publish_receipt
                            WHERE plan_id = ?
                            """,
                            String.class, plan.planId());
                    assertThat(jdbc.queryForObject(
                            """
                            SELECT COUNT(*) FROM flow_runtime_operation
                            WHERE kind = 'OBSERVE_CI'
                              AND owner_kind = 'GITHUB_EFFECT_RECEIPT'
                              AND owner_id = ?
                              AND task_id = ?
                              AND state IN ('READY', 'CLAIMED', 'WAITING')
                            """,
                            Integer.class, receiptId, task.taskId()))
                            .isEqualTo(1);

                    // And it is a live watch, not a row: one observation turns
                    // red remote CI into a repair round CI Autofix owns.
                    try (var dispatcher = new GitHubCiObservationDispatcher(
                            runtime,
                            ciObservationExecutor(
                                    runtime,
                                    context.getBean(
                                            CiObservationCoordinator.class),
                                    localPr.prId(), clock, FAILED_ACTIONS),
                            context.getBean(CiAutofixDispatcher.class),
                            "upstream-red-observer", TTL,
                            Duration.ofMillis(10), 1)) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    PullRequestSubject published = runtime.pullRequest(
                            localPr.prId()).orElseThrow();
                    var policy = autofix.currentPolicy(
                            published.repositoryId(), published.scopeKey())
                            .orElseThrow();
                    assertThat(autofix.round(
                            published.prId(), published.currentRemoteHead(),
                            policy.policyRevisionId()).orElseThrow().state())
                            .isEqualTo(RoundState.QUEUED);

                    assertThat(upstreamSync.run(started.run().runId())
                            .orElseThrow().state())
                            .isEqualTo(RunState.WAITING_INITIAL_PUBLISH);
                    assertThat(upstreamSync.run(started.run().runId())
                            .orElseThrow().remainingRepairTurns())
                            .isEqualTo(
                                    UpstreamSyncCommands.DEFAULT_REPAIR_TURN_BUDGET
                                            - 2);
                    // The Task Agent constructs and checks the range, the
                    // reviewer inspects it, then the same persistent Task
                    // Agent session accepts that exact result.
                    assertThat(turns).hasValue(3);

                    // An ordinary Task never appears in these records, so it
                    // resolves to the default placement without this
                    // component knowing it exists.
                    assertThat(upstreamSync.repairPlacement("task:ordinary"))
                            .isEqualTo(RepairPlacementPolicy.TIP);

                    UpstreamSyncViews views =
                            context.getBean(UpstreamSyncViews.class);
                    assertProjectedRun(views, started.run().runId());
                    assertWatchedTheRepairTurn(liveLines);
                    assertObservesTheEndOfThePullRequest(
                            upstreamSync, context.getBean(TaskViews.class),
                            views, started.run().runId());
                });

        assertThat(Files.readAllBytes(primaryDatabase))
                .containsExactly(primaryBefore);
    }

    /**
     * The live view of a turn that has not ended yet: the tool calls as the
     * agent makes them, and the turn's own end.
     */
    private static void assertWatchedTheRepairTurn(List<String> lines)
    {
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("\"type\":\"tool_use\"")
                .contains("commit_pick_repair"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("\"type\":\"result\""));
    }

    /**
     * Phase 3's trigger: merging is the user's act, so the run only learns of
     * it by being told, and the surfaces read the result from that.
     */
    private static void assertObservesTheEndOfThePullRequest(
            UpstreamSync upstreamSync,
            TaskViews taskViews,
            UpstreamSyncViews views,
            String runId)
    {
        assertThat(views.job(runId).orElseThrow().prResult()).isNull();
        new UpstreamSyncClosureObserver(
                upstreamSync, taskViews,
                (repositoryId, prNumber) -> Optional.of(PrResult.MERGED))
                .observeEndedPullRequests();

        UpstreamSyncViews.SyncJob ended = views.job(runId).orElseThrow();
        assertThat(ended.prResult()).isEqualTo("merged");
        assertThat(ended.closedAt()).isNotNull();

        // Written once: a merged pull request does not become closed.
        upstreamSync.recordPullRequestEnd(runId, PrResult.CLOSED);
        assertThat(views.job(runId).orElseThrow().prResult()).isEqualTo("merged");
    }

    /**
     * What the run surfaces read. The projection is the only thing standing
     * between these records and the list, so a wrong count here is a wrong
     * number on the page.
     */
    private void assertProjectedRun(UpstreamSyncViews views, String runId)
    {
        UpstreamSyncViews.SyncRunDetail detail =
                views.detail(runId).orElseThrow();
        assertThat(detail.job().runNumber()).isEqualTo(1);
        assertThat(detail.job().status()).isEqualTo("COMPLETED");
        assertThat(detail.job().requestedCount())
                .isEqualTo(confirmedRange.size());
        // Every commit but the already-carried last one landed; the
        // conflicted one is counted as carried rather than clean.
        assertThat(detail.job().appliedCount())
                .isEqualTo(confirmedRange.size() - 1);
        assertThat(detail.job().skippedCount()).isEqualTo(1);
        assertThat(detail.job().conflictedCount()).isEqualTo(2);
        assertThat(detail.job().prNumber()).isNotNull();
        assertThat(detail.commits().getFirst().state()).isEqualTo("applied");
        assertThat(detail.commits().get(detail.commits().size() - 3).state())
                .isEqualTo("conflicted");
        assertThat(detail.commits().get(detail.commits().size() - 2).state())
                .isEqualTo("conflicted");
        assertThat(detail.commits().getLast().state()).isEqualTo("skipped");
        // Resolving the pick itself is not a post-pick fork adaptation.
        assertThat(detail.fixups()).isEmpty();
        // The CI round the pull request opened after publication, counted for
        // the list's ROUNDS column and named in the run's own rail.
        assertThat(detail.job().roundCount()).isEqualTo(1);
        assertThat(detail.rounds()).singleElement().satisfies(round -> {
            assertThat(round.ordinal()).isEqualTo(1);
            assertThat(round.state()).isEqualTo("QUEUED");
        });
        assertThat(views.list("octocat/bytequay", 25))
                .extracting(UpstreamSyncViews.SyncJob::jobId)
                .containsExactly(runId);
    }

    private void assertPickedSeries(UpstreamSync upstreamSync, String runId)
    {
        List<UpstreamPick> picks = upstreamSync.picks(runId);
        assertThat(picks).hasSize(confirmedRange.size());
        assertThat(picks).extracting(UpstreamPick::upstreamSha)
                .containsExactlyElementsOf(confirmedRange);
        assertThat(picks.subList(0, picks.size() - 3))
                .allSatisfy(pick ->
                        assertThat(pick.state()).isEqualTo(PickState.CLEAN));
        assertThat(picks.get(picks.size() - 3).state())
                .isEqualTo(PickState.RESOLVED);
        assertThat(picks.get(picks.size() - 2).state())
                .isEqualTo(PickState.RESOLVED);
        assertThat(picks.getLast().state())
                .isEqualTo(PickState.SKIPPED_EMPTY);
        // A change-set revision per pick that landed a commit; the fork
        // already carried the last, so it has neither commit nor revision.
        assertThat(picks.get(0).changeSetRevisionId()).isNotNull();
        assertThat(picks.get(picks.size() - 2).changeSetRevisionId())
                .isNotNull();
        assertThat(picks.getLast().changeSetRevisionId()).isNull();
        assertThat(picks.get(0).provenanceVerified()).isTrue();
        assertThat(picks.get(picks.size() - 2).provenanceVerified()).isTrue();
        UpstreamPick first = picks.getFirst();
        assertThat(upstreamSync.recordPick(
                runId, first.ordinal(), first.upstreamSha(), first.preHead(),
                first.resultHead(), first.resultCommitSha(), first.state(),
                first.conflictedPaths(), first.provenanceVerified(),
                first.changeSetRevisionId())).isEqualTo(first);
        assertThat(upstreamSync.run(runId).orElseThrow().state())
                .isEqualTo(RunState.WAITING_INITIAL_PUBLISH);

        assertThat(upstreamSync.fixups(runId)).isEmpty();
        assertThat(upstreamSync.run(runId).orElseThrow().verificationRef())
                .isNotNull();
    }

    private static TurnResult runModelTurn(
            int turn,
            ToolExecutor tools,
            CountDownLatch reviewerEntered,
            CountDownLatch policyReady)
            throws Exception
    {
        switch (turn) {
            case 1 -> {
                for (String path : List.of(
                        "contested-two.txt", "contested.txt")) {
                    String context = call(
                            tools, "read_pick_conflict_context", "{}");
                    assertThat(context)
                            .contains("taskGoal=Bring the selected upstream")
                            .contains("preHead=")
                            .contains("sequencerActive=true")
                            .contains("upstreamDiff:")
                            .contains("conflictedPaths=" + path);
                    assertThat(call(tools, "read_file",
                            "{\"path\":\"" + path + "\"}"))
                            .contains("<<<<<<<");
                    call(tools, "write_file",
                            "{\"path\":\"" + path + "\","
                                    + "\"content\":\"merged rewrite\\n\"}");
                    call(tools, "commit_pick_repair", "{}");
                }
                assertThat(call(tools, "read_upstream_review_context", "{}"))
                        .contains("selectedUpstreamShas=");
                call(tools, "read_candidate_diff", "{}");
                call(tools, "write_file",
                        "{\"path\":\"local-check-ready\","
                                + "\"content\":\"ready\\n\"}");
                call(tools, "commit_initial_change", "{}");
                assertThat(callFails(tools, "run_checks", "{}"))
                        .contains("argument is invalid");
                String checkRequest = """
                        {"command":["/bin/sh","-c","test -f local-check-ready"],
                         "working_directory":"."}
                        """;
                assertThat(call(tools, "run_checks", checkRequest))
                        .contains(":PASSED");
                call(tools, "request_initial_review",
                        "{\"title\":\"Bring upstream range\","
                                + "\"body\":\"Confirmed upstream range\"}");
                return result(TurnResult.End.COMPLETED);
            }
            case 2 -> {
                call(tools, "read_diff", "{}");
                reviewerEntered.countDown();
                await(policyReady);
                return result(TurnResult.End.COMPLETED);
            }
            case 3 -> {
                assertThat(call(tools, "read_initial_review_context", "{}"))
                        .contains("reviewSummary=opaque");
                call(tools, "read_candidate_diff", "{}");
                call(tools, "ready_for_initial_publish", "{}");
                return result(TurnResult.End.COMPLETED);
            }
            default -> throw new AssertionError(
                    "unexpected model turn " + turn);
        }
    }

    private static String call(
            ToolExecutor tools, String name, String arguments)
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor.ToolCallResult result = tools.execute(new ToolCall(
                "call-" + name, name, arguments,
                mapper.readTree(arguments)));
        assertThat(result.isError())
                .as(name + ": " + result.text())
                .isFalse();
        return result.text();
    }

    private static String callFails(
            ToolExecutor tools, String name, String arguments)
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor.ToolCallResult result = tools.execute(new ToolCall(
                "call-" + name, name, arguments,
                mapper.readTree(arguments)));
        assertThat(result.isError()).as(name).isTrue();
        return result.text();
    }

    private static TurnResult result(TurnResult.End end)
    {
        return new TurnResult("opaque", 1, 1, 0, 1, end);
    }

    private static CredentialStore credentials()
    {
        CredentialStore credentials = mock(CredentialStore.class);
        Credential credential = new Credential(
                7, CredentialType.AI, "openai", "default api",
                "test", "***", null, true, null,
                Instant.parse("2026-08-12T00:00:00.123456789Z"),
                Instant.parse("2026-08-12T00:00:01.123456789Z"), null);
        when(credentials.find(CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of(credential));
        when(credentials.getSecret(
                CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of("ai-secret"));
        return credentials;
    }

    /** The picker confirms shas; the subjects beside them are display only. */
    private static List<SelectedCommit> selection(List<String> shas)
    {
        return shas.stream()
                .map(sha -> new SelectedCommit(sha, "upstream " + sha))
                .toList();
    }

    private static DisplayedGate displayed(JdbcTemplate jdbc, UserGate gate)
    {
        return jdbc.queryForObject(
                "SELECT revision, subject_digest, action_digest "
                        + "FROM flow_user_gate_revision "
                        + "WHERE gate_id = ? AND revision = ?",
                (result, row) -> new DisplayedGate(
                        result.getLong("revision"),
                        result.getString("subject_digest"),
                        result.getString("action_digest")),
                gate.gateId(), gate.currentRevision());
    }

    private record DisplayedGate(
            long revision, String subjectDigest, String actionDigest) {}

    private static <T> T awaitValue(Supplier<Optional<T>> supplier)
    {
        AtomicReference<T> value = new AtomicReference<>();
        awaitCondition(() -> {
            Optional<T> candidate = supplier.get();
            candidate.ifPresent(value::set);
            return candidate.isPresent();
        });
        return value.get();
    }

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(180, TimeUnit.SECONDS)) {
                throw new AssertionError("latch did not complete");
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitCondition(CheckedCondition condition)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        try {
            while (!condition.test()) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("condition did not become true");
                }
                Thread.sleep(10);
            }
        }
        catch (Exception failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new AssertionError(failure);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition
    {
        boolean test()
                throws Exception;
    }

    /**
     * A fork that has diverged from the upstream it tracks: clean commits it
     * is missing, two conflicts, and one commit it already carries.
     */
    private void initializeRepositories(Path upstream, Path target)
            throws IOException, InterruptedException
    {
        initializeBase(upstream);
        git(upstream, "tag", "source-start");
        git(upstream, "checkout", "-b", "upstream");
        write(upstream, "added.txt", "upstream addition\n");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-m", "Add a file the fork does not have");
        cleanCommit = git(upstream, "rev-parse", "HEAD").strip();

        List<String> selected = new ArrayList<>();
        selected.add(cleanCommit);
        Files.createDirectories(upstream.resolve("bulk"));
        for (int index = 0; index < 99; index++) {
            write(upstream, "bulk/commit-" + index + ".txt",
                    "upstream " + index + "\n");
            git(upstream, "add", "-A");
            git(upstream, "commit", "-m", "Add upstream file " + index);
            selected.add(git(upstream, "rev-parse", "HEAD").strip());
        }
        write(upstream, "contested-two.txt", "upstream rewrite two\n");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-m", "Rewrite another contested file upstream");
        secondConflictingCommit = git(upstream, "rev-parse", "HEAD").strip();
        selected.add(secondConflictingCommit);
        write(upstream, "contested.txt", "upstream rewrite\n");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-m", "Rewrite the contested file upstream");
        conflictingCommit = git(upstream, "rev-parse", "HEAD").strip();
        selected.add(conflictingCommit);
        write(upstream, "shared.txt", "shared v2\n");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-m", "Bump the shared file");
        alreadyPresentCommit = git(upstream, "rev-parse", "HEAD").strip();
        selected.add(alreadyPresentCommit);
        confirmedRange = List.copyOf(selected);

        initializeBase(target);
        write(target, "contested.txt", "fork rewrite\n");
        write(target, "contested-two.txt", "fork rewrite two\n");
        write(target, "shared.txt", "shared v2\n");
        git(target, "add", "-A");
        git(target, "commit", "-m", "Fork changes, one of them made upstream too");
        git(target, "remote", "add", "origin",
                "https://github.com/octocat/bytequay.git");
        String head = git(target, "rev-parse", "HEAD").strip();
        git(target, "update-ref", "refs/remotes/origin/main", head);
        git(target, "symbolic-ref", "refs/remotes/origin/HEAD",
                "refs/remotes/origin/main");
    }

    private static void initializeBase(Path root)
            throws IOException, InterruptedException
    {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "test@bytequay.invalid");
        write(root, "shared.txt", "shared v1\n");
        write(root, "contested.txt", "original\n");
        write(root, "contested-two.txt", "original two\n");
        git(root, "add", "-A");
        git(root, "commit", "-m", "base");
    }

    private static boolean hasCommit(Path root, String sha)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(
                "/usr/bin/git", "-C", root.toString(),
                "cat-file", "-e", sha + "^{commit}")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static void write(Path root, String path, String content)
            throws IOException
    {
        Files.writeString(
                root.resolve(path), content, StandardCharsets.UTF_8);
    }

    private static String git(Path root, String... arguments)
            throws IOException, InterruptedException
    {
        String[] command = new String[arguments.length + 3];
        command[0] = "/usr/bin/git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static String history(Path worktree)
    {
        try {
            return git(worktree, "log", "--all", "-p");
        }
        catch (IOException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }
}
