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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.context.ContextSpecCheck.Candidate;
import com.bytequay.app.context.ContextSpecCheck.Duplicate;
import com.bytequay.app.context.ContextSpecCheck.Predicted;
import com.bytequay.app.context.ContextSpecCheck.Status;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.memory.InMemoryMemoryStore;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.GoalSpecChecks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestContextSpecCheckService
{
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.random();
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPO = RepoRef.of("owner", "repository");
    private static final String SPEC = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";
    private static final String GUIDE = "https://docs.delta.io/latest/delta-batch.html";
    private static final String TEST = "plugin/trino-delta-lake/src/test/java/TestCheckpointKeys.java";
    private static final String COMMAND = "./mvnw -pl plugin/trino-delta-lake test -Dtest=TestCheckpointKeys";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path worktree;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final ContextSpecCheckStore store = new ContextSpecCheckStore(new InMemoryAppSettingsStore(), mapper);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final ContextBuildStore buildStore = mock(ContextBuildStore.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
    private final MemoryService memories = new MemoryService(memoryStore, new InMemoryAppSettingsStore(), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ContextSpecCheckService service = new ContextSpecCheckService(workspaces, git, store, builds, buildStore, mock(GitHubRepoClient.class),
            mock(CredentialService.class), tasks, logs, worktrees, memories, new DefaultListableBeanFactory().getBeanProvider(GoalSpecChecks.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        when(logs.findLatest(eq(TASK), any(), anyInt())).thenAnswer(call -> {
            Predicate<AgentEventEnvelope> matches = call.getArgument(1);
            return Lists.reverse(events).stream().filter(matches).findFirst();
        });
        TaskWorktree tree = new TaskWorktree(TASK, WORKSPACE, new RepositoryIdentity("owner", "repository"), Path.of("/tmp/bytequay-context", TASK.toString()),
                "bytequay/task/" + TASK, SHA);
        when(tasks.find(TASK)).thenReturn(Optional.of(AgentTask.created(TASK, false, "Check owner/repository", TitleSource.USER, AgentLaneId.CODEX_CLI, tree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW)));
        when(worktrees.ownedCheckout(tree)).thenReturn(worktree);
    }

    @Test
    void aCheckStartsFromADirectoryOnTheMonitoredBranchHeadAndKeepsItsEarlierFindings()
    {
        Path repository = Path.of("/tmp/bytequay-repository");
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, repository, 1, REPO, OptionalLong.empty(), REPO,
                "origin", "origin", "main", ImmutableList.of()));
        when(builds.baseline(WORKSPACE)).thenReturn(Optional.of(new ContextBuildService.Baseline("main", SHA)));
        when(git.run(repository, "cat-file", "-t", SHA + ":plugin/trino-delta-lake")).thenReturn(new GitCommandRunner.Result(0, "tree\n", false));
        when(git.run(repository, "cat-file", "-t", SHA + ":plugin/missing")).thenReturn(new GitCommandRunner.Result(128, "", false));
        when(git.runLocalBoundedStdout(eq(repository), any(), anyInt(), any()))
                .thenReturn(new GitCommandRunner.Result(0, SPEC + "#checkpoints\n" + SPEC + "#checkpoints\n", false));
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.DONE, ImmutableList.of(candidate("c1", 3, Status.CONFIRMED),
                candidate("c2", 2, Status.NOT_REPRODUCED))));
        when(builds.check(eq(WORKSPACE), any(), any(), any())).thenReturn(new ContextBuildService.View(1, false, Optional.empty()));

        assertThat(service.sources(WORKSPACE, "plugin/trino-delta-lake/")).extracting(ContextSpecSource::url, ContextSpecSource::links).containsExactly(tuple(SPEC, 2));
        assertThatThrownBy(() -> service.sources(WORKSPACE, "plugin/missing/")).hasMessageContaining("plugin/missing/ is not a directory on main");

        ContextSpecCheckService.Started started = service.start(WORKSPACE,
                new ContextSpecCheckService.StartRequest("start-key", 0, "plugin/trino-delta-lake/", ImmutableList.of(SPEC), 3));

        assertThat(started.check().directory()).isEqualTo("plugin/trino-delta-lake/");
        assertThat(started.check().state()).isEqualTo(ContextSpecCheck.State.CHECKING);
        assertThat(started.check().candidates()).extracting(Candidate::id).containsExactly("c1");
        assertThat(store.find(WORKSPACE)).hasSize(1);
        verify(builds).check(eq(WORKSPACE), argThat(request -> request.requestKey().equals("start-key") && request.expectedBuildSequence() == 0), eq(SHA),
                argThat(check -> check.directory().equals("plugin/trino-delta-lake/") && check.reproduction().isEmpty()
                        && check.brief().contains("Directory: plugin/trino-delta-lake/")));
    }

    @Test
    void candidatesAreTypedByTheirPredictionAndAnOpenReportIsLinkedInsteadOfReproduced()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.CHECKING, ImmutableList.of()));

        ContextSpecCheckService.Saved saved = service.saveCandidates(build(Optional.empty()), ImmutableList.of(
                found(Predicted.SKIPS_SHOULD, "Checkpoint omits the recommended stats", 1, Optional.of(100)),
                found(Predicted.DIVERGES, "Checkpoints key removes by path only", 3, Optional.empty()),
                found(Predicted.REFUSES, "Writes to catalog-managed tables are refused", 2, Optional.of(31252))),
                Optional.of(ImmutableList.of(SPEC)), ImmutableList.of("Deletion Vectors"), ImmutableList.of("Column Mapping"), this::searched);

        assertThat(saved.saved()).containsExactly("c1", "c2", "c3");
        assertThat(saved.pending()).isEqualTo(2);
        assertThat(saved.alreadyReported()).isEqualTo(1);
        ContextSpecCheck check = current();
        assertThat(check.candidates()).extracting(Candidate::type).containsExactly(ContextSpecCheck.Type.IMPROVEMENT, ContextSpecCheck.Type.BUG,
                ContextSpecCheck.Type.FEATURE_REQUEST);
        assertThat(check.candidates()).extracting(Candidate::status).containsExactly(Status.PENDING, Status.PENDING, Status.ALREADY_REPORTED);
        assertThat(check.candidate("c1").duplicate()).map(Duplicate::number).contains(100);
        assertThat(check.checkedAgainst()).containsExactly(SPEC);
        assertThat(check.covered()).containsExactly("Deletion Vectors");
        assertThat(check.next()).containsExactly("Column Mapping");
        assertThat(check.nextToReproduce()).map(Candidate::id).contains("c2");

        ContextSpecCheckService.Saved again = service.saveCandidates(build(Optional.empty()),
                ImmutableList.of(found(Predicted.DIVERGES, "checkpoints key removes by path only", 3, Optional.empty())), Optional.empty(), ImmutableList.of(),
                ImmutableList.of(), this::searched);
        assertThat(again.saved()).isEmpty();
        assertThat(again.skipped()).hasSize(1);
        assertThat(current().covered()).containsExactly("Deletion Vectors");
        assertThat(current().checkedAgainst()).containsExactly(SPEC);

        assertThat(memories.list(WORKSPACE)).singleElement().satisfies(memory -> {
            assertThat(memory.kind()).isEqualTo(Memory.Kind.SPEC);
            assertThat(memory.text()).isEqualTo("Implements the specification at " + SPEC + ".");
            assertThat(memory.scope()).containsExactly(current().directory());
            assertThat(memory.sources()).extracting(MemorySource::url).containsExactly(Optional.of(SPEC));
            assertThat(memory.origin().taskId()).contains(TASK.toString());
        });
        service.saveCandidates(build(Optional.empty()), ImmutableList.of(), Optional.of(ImmutableList.of(SPEC)), ImmutableList.of(), ImmutableList.of(), this::searched);
        assertThat(memories.list(WORKSPACE)).hasSize(1);
    }

    @Test
    void candidatesCiteTheCheckedSpecsTheDirectorysCodeAndOnlyReportsTheTurnFound()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.CHECKING, ImmutableList.of()));
        ContextBuild build = build(Optional.empty());

        assertThatThrownBy(() -> service.saveCandidates(build, ImmutableList.of(new ContextSpecCheckService.Found(Predicted.DIVERGES, "Other spec", "MUST x",
                "https://iceberg.apache.org/spec/#manifests", ImmutableList.of("plugin/trino-delta-lake/src/Writer.java:1-2"), "why", 2, Optional.empty())),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), this::searched))
                .hasMessageContaining("is not a spec of this check");
        assertThatThrownBy(() -> service.saveCandidates(build, ImmutableList.of(new ContextSpecCheckService.Found(Predicted.DIVERGES, "Elsewhere", "MUST x",
                SPEC + "#checkpoints", ImmutableList.of("core/trino-main/src/Planner.java:1-2"), "why", 2, Optional.empty())),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), this::searched))
                .hasMessageContaining("cite at least one location in plugin/trino-delta-lake/");
        assertThatThrownBy(() -> service.saveCandidates(build, ImmutableList.of(new ContextSpecCheckService.Found(Predicted.DIVERGES, "No lines", "MUST x",
                SPEC + "#checkpoints", ImmutableList.of("plugin/trino-delta-lake/src/Writer.java"), "why", 2, Optional.empty())),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), this::searched))
                .hasMessageContaining("path:start-end");
        assertThatThrownBy(() -> service.saveCandidates(build, ImmutableList.of(found(Predicted.DIVERGES, "Unknown report", 2, Optional.of(7))),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), this::searched))
                .hasMessageContaining("#7 was not returned by this turn's report search");
        assertThatThrownBy(() -> service.saveCandidates(build, ImmutableList.of(), Optional.of(ImmutableList.of("https://iceberg.apache.org/spec/")),
                ImmutableList.of(), ImmutableList.of(), this::searched))
                .hasMessageContaining("specs: https://iceberg.apache.org/spec/ is not a page of this check");
        assertThat(current().candidates()).isEmpty();
    }

    @Test
    void aCheckMayProposeAtMostTwoSpecificationsTheCodeDoesNotLinkOnceItReadThem()
    {
        String iceberg = "https://iceberg.apache.org/spec/";
        String parquet = "https://parquet.apache.org/docs/file-format/";
        String avro = "https://avro.apache.org/docs/1.11.1/specification/";
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.CHECKING, ImmutableList.of()));
        ContextBuild read = build(Optional.empty(), new ContextBuild.Capture(ImmutableMap.of("page:" + iceberg, "capture-1", "page:" + parquet, "capture-2",
                "page:" + avro, "capture-3"), ImmutableList.of(), ImmutableList.of()));

        assertThatThrownBy(() -> service.saveCandidates(read, ImmutableList.of(), Optional.of(ImmutableList.of(SPEC, iceberg, parquet, avro)), ImmutableList.of(),
                ImmutableList.of(), this::searched)).hasMessage("specs: propose at most 2 specifications the code doesn't link");
        service.saveCandidates(read, ImmutableList.of(), Optional.of(ImmutableList.of(SPEC, iceberg, parquet)), ImmutableList.of(), ImmutableList.of(), this::searched);
        assertThat(current().checkedAgainst()).containsExactly(SPEC, iceberg, parquet);
        // A directory whose code links no page still starts a check, which proposes the specification.
        assertThat(new ContextSpecCheckService.StartRequest("key", 0, "plugin/trino-iceberg/", ImmutableList.of(), 3).specs()).isEmpty();
    }

    @Test
    void aCheckReproducesNoMoreCandidatesThanTheUserAllowed()
    {
        ContextSpecCheck limited = new ContextSpecCheck("check-1", "plugin/trino-delta-lake/", SHA, "check-request", ImmutableList.of(SPEC), ImmutableList.of(),
                ImmutableList.of(), ContextSpecCheck.State.CHECKING, ImmutableList.of(candidate("c1", 3, Status.PENDING), candidate("c2", 2, Status.PENDING)),
                ImmutableList.of(), ImmutableList.of(), 0, 1, Optional.empty(), NOW, Optional.empty());

        ContextSpecCheck reproducing = limited.reproducing(limited.nextToReproduce().orElseThrow());
        assertThat(reproducing.candidate("c1").status()).isEqualTo(Status.REPRODUCING);
        assertThat(reproducing.nextToReproduce()).isEmpty();
        assertThat(reproducing.ended(ContextSpecCheck.State.DONE, Optional.empty(), NOW).candidate("c2").reason())
                .contains("Not reached: this check reproduces at most 1 candidate, most severe first");
        assertThatThrownBy(() -> new ContextSpecCheckService.StartRequest("key", 0, "plugin/trino-delta-lake/", ImmutableList.of(SPEC), 9))
                .hasMessage("A check reproduces 1 to 8 candidates");
    }

    @Test
    void aConfirmedGapNeedsAFailingRunOfItsTestInThisTurnAndDraftsTheIssueFromIt()
            throws Exception
    {
        reproducing();
        Files.createDirectories(worktree.resolve(TEST).getParent());
        Files.writeString(worktree.resolve(TEST), "class TestCheckpointKeys { @Test void keysByDeletionVector() {} }\n");
        ContextBuild build = build(Optional.of(new ContextBuild.Reproduction("c1", "Checkpoints key removes by path only")));
        ran(TurnId.random(), "/bin/zsh -lc '" + COMMAND + "'", 1, "from another turn");
        ran(TURN, "/bin/zsh -lc '" + COMMAND + "'", 0, "BUILD SUCCESS");

        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed(COMMAND, ImmutableList.of())))
                .hasMessageContaining("The test passed (exit 0)");
        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed(COMMAND + " | tail -50", ImmutableList.of())))
                .hasMessageContaining("run the test as one command");
        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed("./mvnw -pl plugin/trino-delta-lake test", ImmutableList.of())))
                .hasMessageContaining("does not name the test");
        ran(TURN, COMMAND, 1, "[ERROR] COMPILATION ERROR : cannot find symbol");
        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed(COMMAND, ImmutableList.of())))
                .hasMessageContaining("failed before the test ran");
        ran(TURN, COMMAND, 1, "[ERROR] Failures:\n[ERROR]   TestCheckpointKeys.keysByDeletionVector expected: 2 but was: 1\nBUILD FAILURE");
        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed(COMMAND, ImmutableList.of("performance"))))
                .hasMessageContaining("[performance] are not labels of this repository");
        assertThat(current().candidate("c1").status()).isEqualTo(Status.REPRODUCING);

        Candidate settled = service.saveResult(build, TURN, confirmed(COMMAND, ImmutableList.of("delta-lake")));

        assertThat(settled.status()).isEqualTo(Status.CONFIRMED);
        assertThat(settled.test().orElseThrow().exitCode()).isEqualTo(1);
        assertThat(settled.test().orElseThrow().command()).isEqualTo(COMMAND);
        assertThat(settled.test().orElseThrow().source()).contains("keysByDeletionVector");
        ContextSpecCheck.Draft draft = settled.draft().orElseThrow();
        assertThat(draft.title()).isEqualTo("Checkpoints key removes by path only");
        assertThat(draft.labels()).containsExactly("delta-lake");
        assertThat(draft.body())
                .startsWith("A checkpoint keeps one of two removes.")
                .contains("> The checkpoint MUST contain the remove actions keyed by path and deletion vector.")
                .contains(SPEC + "#checkpoints")
                .contains("- [`plugin/trino-delta-lake/src/Writer.java:10-20`](https://github.com/owner/repository/blob/" + SHA
                        + "/plugin/trino-delta-lake/src/Writer.java#L10-L20)")
                .contains("### Impact\n\nReaders see a deleted row again.")
                .contains("```java\nclass TestCheckpointKeys")
                .contains("`" + COMMAND + "` exits with 1:")
                .contains("expected: 2 but was: 1")
                .contains("### Proposed fix\n\nKey removes by path and deletion vector.");
        assertThat(current().candidate("c1")).isEqualTo(settled);
        assertThatThrownBy(() -> service.saveResult(build, TURN, confirmed(COMMAND, ImmutableList.of())))
                .hasMessageContaining("already has its result");
    }

    @Test
    void aRefutedRuleNeedsAPassingRun()
            throws Exception
    {
        reproducing();
        Files.createDirectories(worktree.resolve(TEST).getParent());
        Files.writeString(worktree.resolve(TEST), "class TestCheckpointKeys {}\n");
        ContextBuild build = build(Optional.of(new ContextBuild.Reproduction("c1", "Checkpoints key removes by path only")));
        ran(TURN, COMMAND, 1, "Tests run: 1, Failures: 1");

        assertThatThrownBy(() -> service.saveResult(build, TURN, refuted()))
                .hasMessageContaining("The test failed (exit 1)");

        ran(TURN, COMMAND, 0, "Tests run: 1, Failures: 0\nBUILD SUCCESS");
        Candidate settled = service.saveResult(build, TURN, refuted());

        assertThat(settled.status()).isEqualTo(Status.REFUTED);
        assertThat(settled.draft()).isEmpty();
    }

    @Test
    void aNotReproducedCandidateNeedsOnlyItsReason()
    {
        reproducing();

        Candidate settled = service.saveResult(build(Optional.of(new ContextBuild.Reproduction("c1", "Checkpoints key removes by path only"))), TURN,
                new ContextSpecCheckService.Result(Status.NOT_REPRODUCED, "Needs a Hive metastore container", Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of()));

        assertThat(settled.status()).isEqualTo(Status.NOT_REPRODUCED);
        assertThat(settled.reason()).contains("Needs a Hive metastore container");
        assertThat(settled.test()).isEmpty();
    }

    @Test
    void theCheckReproducesTheMostSevereCandidateNextAndEndsWhenNoneIsLeft()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.CHECKING, ImmutableList.of(
                candidate("c1", 1, Status.PENDING), candidate("c2", 3, Status.PENDING), candidate("c3", 3, Status.ALREADY_REPORTED))));
        turnEnded(build(Optional.empty()), ContextBuildService.Outcome.SAVED);

        service.continueCheck(WORKSPACE);

        verify(builds).check(eq(WORKSPACE), argThat(request -> request.requestKey().equals("check-request:1") && request.expectedBuildSequence() == 1), eq(SHA),
                argThat(check -> check.reproduction().map(ContextBuild.Reproduction::candidateId).equals(Optional.of("c2")) && check.directory().equals("plugin/trino-delta-lake/")
                        && check.brief().contains("Rule: \"The checkpoint MUST contain")));
        assertThat(current().state()).isEqualTo(ContextSpecCheck.State.REPRODUCING);
        assertThat(current().candidate("c2").status()).isEqualTo(Status.REPRODUCING);
        assertThat(current().reproductions()).isEqualTo(1);

        // The reproduction turn ended without reporting: its candidate is not reproduced and the check moves on.
        turnEnded(build(Optional.of(new ContextBuild.Reproduction("c2", "Gap c2"))), ContextBuildService.Outcome.FAILED);
        service.continueCheck(WORKSPACE);

        assertThat(current().candidate("c2").status()).isEqualTo(Status.NOT_REPRODUCED);
        assertThat(current().candidate("c2").reason().orElseThrow()).startsWith("The reproduction turn ended without a result");
        assertThat(current().candidate("c1").status()).isEqualTo(Status.REPRODUCING);

        ContextSpecCheck reproduced = current();
        store.save(WORKSPACE, Optional.of(reproduced), reproduced.withCandidate(reproduced.candidate("c1").withResult(Status.REFUTED, Optional.empty(),
                Optional.of("Follows the rule"), Optional.empty())));
        turnEnded(build(Optional.of(new ContextBuild.Reproduction("c1", "Gap c1"))), ContextBuildService.Outcome.SAVED);
        service.continueCheck(WORKSPACE);

        assertThat(current().state()).isEqualTo(ContextSpecCheck.State.DONE);
        assertThat(current().endedAt()).contains(NOW);
        assertThat(current().candidate("c3").status()).isEqualTo(Status.ALREADY_REPORTED);
    }

    @Test
    void aPlanningTurnThatSavedNothingFailsTheCheckAndARunningTurnChangesNothing()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.CHECKING, ImmutableList.of()));
        turnEnded(build(Optional.empty()), ContextBuildService.Outcome.RUNNING);

        service.continueCheck(WORKSPACE);
        assertThat(current().state()).isEqualTo(ContextSpecCheck.State.CHECKING);

        turnEnded(build(Optional.empty()), ContextBuildService.Outcome.FAILED);
        service.continueCheck(WORKSPACE);

        assertThat(current().state()).isEqualTo(ContextSpecCheck.State.FAILED);
        assertThat(current().detail().orElseThrow()).startsWith("The check turn ended without saving candidates");
        verify(builds, never()).check(any(), any(), any(), any());
    }

    @Test
    void dismissingCanRememberTheUsersRuleForTheFindingsFileInTheDirectory()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.DONE, ImmutableList.of(candidate("c1", 3, Status.CONFIRMED),
                candidate("c2", 2, Status.CONFIRMED))));

        ContextSpecCheck dismissed = service.dismiss(WORKSPACE, "check-1", "c1", "Not a real gap",
                Optional.of("The writer keys removes by path on purpose until deletion vectors are enabled."));
        assertThat(dismissed.candidate("c1").dismissed()).contains("Not a real gap");
        assertThat(memories.list(WORKSPACE)).singleElement().satisfies(memory -> {
            assertThat(memory.text()).isEqualTo("The writer keys removes by path on purpose until deletion vectors are enabled.");
            assertThat(memory.scope()).containsExactly("plugin/trino-delta-lake/src/Writer.java");
            assertThat(memory.origin().user()).isTrue();
            assertThat(memory.sources().getFirst().user()).contains(new MemorySource.UserPart(MemorySource.Where.DISMISS, "check-1", "c1"));
        });

        assertThatThrownBy(() -> service.dismiss(WORKSPACE, "check-1", "c2", "Not a real gap", Optional.of(" "))).hasMessage("Write the reason to remember it");
        assertThat(current().candidate("c2").dismissed()).isEmpty();
        service.dismiss(WORKSPACE, "check-1", "c2", "Not worth filing", Optional.empty());
        assertThat(memories.list(WORKSPACE)).hasSize(1);
    }

    @Test
    void anEndedCheckKeepsItsFindingsAndSaysWhyTheRestWasNotReproduced()
    {
        ContextSpecCheck check = check(ContextSpecCheck.State.REPRODUCING, ImmutableList.of(candidate("c1", 3, Status.CONFIRMED),
                candidate("c2", 2, Status.REPRODUCING), candidate("c3", 1, Status.PENDING), candidate("c4", 1, Status.REFUTED)));

        ContextSpecCheck stopped = check.ended(ContextSpecCheck.State.STOPPED, Optional.of("Stopped"), NOW);

        assertThat(stopped.candidates()).extracting(Candidate::status).containsExactly(Status.CONFIRMED, Status.NOT_REPRODUCED, Status.NOT_REPRODUCED,
                Status.REFUTED);
        assertThat(stopped.candidate("c3").reason()).contains("The check was stopped before reproducing it");
        assertThat(stopped.kept()).extracting(Candidate::id).containsExactly("c1", "c4");
    }

    @Test
    void thePlanningBriefAsksTheCheckToFindTheSpecsAmongTheLinkedPages()
    {
        ContextSpecCheck check = check(ContextSpecCheck.State.CHECKING, ImmutableList.of());
        String scala = "https://github.com/delta-io/delta/blob/f5ebfbb/spark/src/main/scala/Checkpoints.scala";
        ContextSpecCheck earlier = new ContextSpecCheck("check-0", "plugin/trino-delta-lake/", SHA, "old",
                ImmutableList.of(SPEC), ImmutableList.of(SPEC), ImmutableList.of(), ContextSpecCheck.State.DONE, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), 0,
                8, Optional.empty(), NOW, Optional.of(NOW));
        ContextSpecCheck withCode = new ContextSpecCheck(check.id(), check.directory(), check.baselineSha(), check.requestKey(), ImmutableList.of(SPEC, GUIDE, scala),
                ImmutableList.of(), check.labels(), check.state(), check.candidates(),
                check.covered(), check.next(), 0, 8, Optional.empty(), NOW, Optional.empty());

        String brief = ContextSpecCheckService.planningBrief(withCode, Optional.of(earlier), ImmutableList.of(
                new ContextSpecSource(SPEC, ContextSpecSource.Kind.PAGE, 32, ImmutableList.of("checkpoints", "deletion-vectors")),
                new ContextSpecSource(scala, ContextSpecSource.Kind.REFERENCE_CODE, 3, ImmutableList.of())));

        String propose = "The directory may implement a public specification its code never links, such as a table or file format, a protocol or an RFC. "
                + "Propose up to 2 such addresses: read each with PAGE, and pass it in specs only when its text states rules the code must follow, never for a guide.\n";
        assertThat(brief).startsWith("Pages the user kept from the links in the directory's code, most cited first. Decide which are specifications "
                + "the code implements; open a page only when its address and citations do not settle that, and read the most cited specification first:\n"
                + "- " + SPEC + " · cited 32 times · sections the code links: #checkpoints, #deletion-vectors\n"
                + "- " + GUIDE + " · added by the user\n"
                + "Reference implementations in another engine, for comparison only and never rules:\n- " + scala + " · cited 3 times\n"
                + propose
                + "The last check used these as specifications: " + SPEC + "\n"
                + "Directory: plugin/trino-delta-lake/\n");
        ContextSpecCheck unlinked = new ContextSpecCheck("check-2", "plugin/trino-iceberg/", SHA, "key", ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ContextSpecCheck.State.CHECKING, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), 0, 8, Optional.empty(), NOW, Optional.empty());
        assertThat(ContextSpecCheckService.planningBrief(unlinked, Optional.empty(), ImmutableList.of()))
                .startsWith("Pages the user kept from the links in the directory's code, most cited first. Decide which are specifications "
                    + "the code implements; open a page only when its address and citations do not settle that, and read the most cited specification first:\n(none)\n" + propose);
    }

    private Optional<Duplicate> searched(int number)
    {
        return switch (number) {
            case 100 -> Optional.of(new Duplicate(100, "Old stats report", "closed", "https://github.com/owner/repository/issues/100"));
            case 31252 -> Optional.of(new Duplicate(31252, "Support catalog-managed tables", "open", "https://github.com/owner/repository/issues/31252"));
            default -> Optional.empty();
        };
    }

    private void reproducing()
    {
        store.save(WORKSPACE, Optional.empty(), check(ContextSpecCheck.State.REPRODUCING, ImmutableList.of(candidate("c1", 3, Status.REPRODUCING))));
    }

    private void ran(TurnId turn, String command, int exitCode, String output)
    {
        events.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), events.size() + 1, TASK, Optional.of(turn),
                AgentEventSource.root(), NOW, new AgentEventPayload.CommandActivity(exitCode == 0 ? ProviderAgentEvent.ActivityState.COMPLETED
                : ProviderAgentEvent.ActivityState.FAILED, ImmutableList.of(command), Optional.of(output), Optional.of(exitCode), false, Optional.empty(),
                Optional.empty(), ImmutableList.of(), false, Optional.empty())));
    }

    private void turnEnded(ContextBuild build, ContextBuildService.Outcome outcome)
    {
        when(buildStore.find(WORKSPACE)).thenReturn(Optional.of(build));
        when(builds.get(WORKSPACE)).thenReturn(new ContextBuildService.View(build.sequence(), false, Optional.of(new ContextBuildService.BuildView("CHECK", build.requestKey(),
                TASK.toString(), "check-1", Optional.of(new ContextBuildService.TaskView(TASK.toString(), WORKSPACE, "OPEN", Optional.empty(), Optional.empty())),
                Optional.empty(), 0, outcome, Optional.empty(), ImmutableList.of()))));
    }

    private static ContextSpecCheckService.Result confirmed(String command, List<String> labels)
    {
        return new ContextSpecCheckService.Result(Status.CONFIRMED, "The checkpoint dropped the second remove", Optional.of(TEST), Optional.of(command),
                Optional.empty(), Optional.of("A checkpoint keeps one of two removes."), Optional.of("Readers see a deleted row again."),
                Optional.of("Key removes by path and deletion vector."), labels);
    }

    private static ContextSpecCheckService.Result refuted()
    {
        return new ContextSpecCheckService.Result(Status.REFUTED, "The writer keys both", Optional.of(TEST), Optional.of(COMMAND), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of());
    }

    private ContextSpecCheck current()
    {
        return store.find(WORKSPACE, "check-1").orElseThrow();
    }

    private static ContextSpecCheckService.Found found(Predicted predicted, String title, int severity, Optional<Integer> duplicate)
    {
        return new ContextSpecCheckService.Found(predicted, title, "The checkpoint MUST contain the remove actions keyed by path and deletion vector.",
                SPEC + "#checkpoints", ImmutableList.of("plugin/trino-delta-lake/src/Writer.java:10-20"), "Log replay keys by both; the writer by path.",
                severity, duplicate);
    }

    private static Candidate candidate(String id, int severity, Status status)
    {
        Predicted predicted = Predicted.DIVERGES;
        return new Candidate(id, predicted.type(), predicted, id.equals("c1") ? "Checkpoints key removes by path only" : "Gap " + id,
                "The checkpoint MUST contain the remove actions keyed by path and deletion vector.", SPEC + "#checkpoints",
                ImmutableList.of("plugin/trino-delta-lake/src/Writer.java:10-20"), "Log replay keys by both; the writer by path.", severity, Optional.empty(), status,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ContextSpecCheck check(ContextSpecCheck.State state, List<Candidate> candidates)
    {
        return new ContextSpecCheck("check-1", "plugin/trino-delta-lake/", SHA, "check-request", ImmutableList.of(SPEC, GUIDE), ImmutableList.of(),
                ImmutableList.of("delta-lake", "correctness"), state, candidates, ImmutableList.of(),
                ImmutableList.of(), 0, 8, Optional.empty(), NOW, state.active() ? Optional.empty() : Optional.of(NOW));
    }

    private static ContextBuild build(Optional<ContextBuild.Reproduction> reproduction)
    {
        return build(reproduction, ContextBuild.Capture.empty());
    }

    private static ContextBuild build(Optional<ContextBuild.Reproduction> reproduction, ContextBuild.Capture capture)
    {
        return new ContextBuild(reproduction.isPresent() ? 2 : 1, "check-request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(),
                new RepositoryIdentity("owner", "repository"), 1, REPO, SHA, "bytequay/check", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW, capture, Optional.of(new ContextBuild.Check("check-1", "plugin/trino-delta-lake/", reproduction, "brief")), Optional.empty(), Optional.empty());
    }
}
