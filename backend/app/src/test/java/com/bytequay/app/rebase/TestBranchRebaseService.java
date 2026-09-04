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
package com.bytequay.app.rebase;

import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.RebaseSequencerDriver;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebase.FinishReason;
import com.bytequay.core.rebase.BranchRebase.PauseReason;
import com.bytequay.core.rebase.BranchRebase.Status;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
class TestBranchRebaseService
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final GitCommandRunner GIT = new GitCommandRunner();
    private static final RepositoryIdentity REPOSITORY_ID = RepositoryIdentity.valueOf("trinodb/trino");

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private TaskId taskId;
    private TaskWorktree taskWorktree;
    private InMemoryStore store;
    private FakeTurns turns;
    private RebaseSequencerDriver driver;
    private BranchRebaseService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        taskId = TaskId.random();
        Path created = Files.createDirectories(temporaryDirectory.resolve(taskId.toString()));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        git(created, "config", "user.name", "ByteQuay Test");
        git(created, "config", "user.email", "bytequay@example.com");
        git(created, "remote", "add", "origin", "https://github.com/trinodb/trino.git");
        repository = created.toRealPath();
        taskWorktree = new TaskWorktree(
                taskId,
                "workspace-1",
                REPOSITORY_ID,
                repository,
                workBranch(),
                "a".repeat(40));
        store = new InMemoryStore();
        turns = new FakeTurns();
        driver = new RebaseSequencerDriver(GIT);

        AgentTaskService tasks = mock(AgentTaskService.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        AgentTask task = AgentTask.created(
                taskId,
                false,
                "Rebase feature",
                TitleSource.USER,
                AgentLaneId.CLAUDE_CLI,
                taskWorktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.CI_AUTOFIX),
                NOW);
        when(tasks.get(taskId)).thenReturn(task);
        when(worktrees.inspect(taskWorktree)).thenAnswer(ignored -> worktreeSnapshot());
        service = new BranchRebaseService(
                store,
                tasks,
                worktrees,
                turns,
                driver,
                GIT,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void finishesWithoutRewritingWhenWorkAlreadyContainsTheBase()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", workBranch());
        String original = change("feature.txt", "feature\n", "Feature");

        BranchRebase result = service.start(command("fast"));

        assertThat(result.status()).isEqualTo(Status.FINISHED);
        assertThat(result.finishReason()).contains(FinishReason.COMPLETED);
        assertThat(result.resultWorkTip()).contains(original);
        assertThat(head()).isEqualTo(original);
    }

    @Test
    void cleanlyRebasesAndRecoversACommandCompletedBeforeItsResultSave()
    {
        Tips tips = cleanDivergence();
        BranchRebase started = service.start(command("recover-clean"));

        assertThat(driver.begin(repository, tips.original(), tips.base()).kind())
                .isEqualTo(com.bytequay.app.git.SequenceState.Kind.COMPLETED);
        String rebased = head();
        BranchRebase recovered = service.advance(started.id());

        assertThat(recovered.status()).isEqualTo(Status.FINISHED);
        assertThat(recovered.resultWorkTip()).contains(rebased);
        assertThat(git(repository, "merge-base", "--is-ancestor", tips.base(), rebased)).isEmpty();
    }

    @Test
    void resolvesAConflictThenLetsOnlyTheProgramContinue()
            throws IOException
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("conflict"));
        rebase = service.advance(rebase.id());
        assertThat(rebase.conflictCommitSha()).contains(conflict.source());
        assertThat(rebase.currentTurnId()).isEmpty();

        rebase = service.advance(rebase.id());
        assertThat(rebase.currentTurnId()).isPresent();
        write("file.txt", "resolved\n");
        git(repository, "add", "file.txt");
        turns.completeCurrent();

        rebase = service.advance(rebase.id());
        assertThat(rebase.journal().orElseThrow().type())
                .isEqualTo(BranchRebase.ActionType.CONTINUE);
        rebase = service.advance(rebase.id());

        assertThat(rebase.status()).isEqualTo(Status.FINISHED);
        assertThat(git(repository, "merge-base", "--is-ancestor", conflict.base(), head())).isEmpty();
        assertThat(Files.readString(repository.resolve("file.txt"))).isEqualTo("resolved\n");
    }

    @Test
    void skipsAnAgentResolutionThatIsProvenEmpty()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("empty"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());
        write("file.txt", "main\n");
        git(repository, "add", "file.txt");
        turns.completeCurrent();

        rebase = service.advance(rebase.id());
        assertThat(rebase.journal().orElseThrow().type()).isEqualTo(BranchRebase.ActionType.SKIP);
        rebase = service.advance(rebase.id());

        assertThat(rebase.status()).isEqualTo(Status.FINISHED);
        assertThat(rebase.resultWorkTip()).contains(conflict.base());
    }

    @Test
    void adoptsAContinueCompletedBeforeItsResultWasSaved()
            throws IOException
    {
        conflict();
        BranchRebase rebase = service.start(command("recover-continue"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());
        write("file.txt", "resolved\n");
        git(repository, "add", "file.txt");
        turns.completeCurrent();
        rebase = service.advance(rebase.id());
        BranchRebase.ActionJournal action = rebase.journal().orElseThrow();

        assertThat(driver.continueRebase(
                repository,
                action.expectedHead(),
                action.stoppedCommitSha().orElseThrow()).kind())
                .isEqualTo(com.bytequay.app.git.SequenceState.Kind.COMPLETED);
        BranchRebase recovered = service.advance(rebase.id());

        assertThat(recovered.status()).isEqualTo(Status.FINISHED);
        assertThat(Files.readString(repository.resolve("file.txt"))).isEqualTo("resolved\n");
    }

    @Test
    void adoptsAnEmptySkipCompletedBeforeItsResultWasSaved()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("recover-skip"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());
        write("file.txt", "main\n");
        git(repository, "add", "file.txt");
        turns.completeCurrent();
        rebase = service.advance(rebase.id());
        BranchRebase.ActionJournal action = rebase.journal().orElseThrow();

        assertThat(driver.skipEmpty(
                repository,
                action.expectedHead(),
                action.stoppedCommitSha().orElseThrow()).kind())
                .isEqualTo(com.bytequay.app.git.SequenceState.Kind.COMPLETED);
        BranchRebase recovered = service.advance(rebase.id());

        assertThat(recovered.status()).isEqualTo(Status.FINISHED);
        assertThat(recovered.resultWorkTip()).contains(conflict.base());
    }

    @Test
    void pausesWhenTheAgentRunsContinueItself()
    {
        conflict();
        BranchRebase rebase = service.start(command("agent-continued"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());
        write("file.txt", "resolved\n");
        git(repository, "add", "file.txt");
        git(repository, "-c", "core.editor=true", "rebase", "--continue");
        turns.completeCurrent();

        BranchRebase paused = service.advance(rebase.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.RECONCILIATION_MISMATCH);
        assertThat(paused.summary().orElseThrow()).contains("advanced");
    }

    @Test
    void pausesAfterThreeUnresolvedAgentTurns()
    {
        conflict();
        BranchRebase rebase = service.start(command("retry-limit"));
        rebase = service.advance(rebase.id());
        for (int attempt = 1; attempt <= BranchRebase.MAX_AGENT_TURNS; attempt++) {
            rebase = service.advance(rebase.id());
            turns.completeCurrent();
            rebase = service.advance(rebase.id());
        }

        assertThat(rebase.status()).isEqualTo(Status.PAUSED);
        assertThat(rebase.pauseReason()).contains(PauseReason.RETRY_LIMIT_REACHED);
        assertThat(rebase.attemptCount()).isEqualTo(BranchRebase.MAX_AGENT_TURNS);
    }

    @Test
    void cancellationAbortsAndRestoresTheOriginalTip()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("cancel"));
        rebase = service.advance(rebase.id());

        BranchRebase cancelled = service.cancel(rebase.id());

        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(head()).isEqualTo(conflict.original());
    }

    @Test
    void adoptsAnAbortCompletedBeforeItsResultWasSaved()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("recover-abort"));
        rebase = service.advance(rebase.id());
        BranchRebase.ActionJournal abort = new BranchRebase.ActionJournal(
                BranchRebase.ActionType.ABORT,
                rebase.conflictCommitSha(),
                head(),
                rebase.attemptCount(),
                rebase.id() + ":abort",
                NOW);
        rebase = store.save(rebase.requestAbort(abort, NOW));
        driver.abort(repository, conflict.original());

        BranchRebase recovered = service.advance(rebase.id());

        assertThat(recovered.status()).isEqualTo(Status.FINISHED);
        assertThat(recovered.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(head()).isEqualTo(conflict.original());
    }

    @Test
    void pausesWhenAbortCannotRestoreTheOriginalTip()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("abort-mismatch"));
        rebase = service.advance(rebase.id());
        driver.abort(repository, conflict.original());
        change("external.txt", "external\n", "External history change");

        BranchRebase paused = service.cancel(rebase.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.UNSAFE_GIT_STATE);
        assertThat(head()).isNotEqualTo(conflict.original());
    }

    @Test
    void cancellationWaitsForAnActiveTurnThenAborts()
    {
        Conflict conflict = conflict();
        BranchRebase rebase = service.start(command("cancel-turn"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());

        BranchRebase requested = service.cancel(rebase.id());
        assertThat(requested.status()).isEqualTo(Status.RUNNING);
        assertThat(requested.currentTurnId()).isPresent();

        BranchRebase cancelled = service.advance(rebase.id());
        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(head()).isEqualTo(conflict.original());
    }

    @Test
    void userNoteStartsANewTurnAfterATurnFailure()
    {
        conflict();
        BranchRebase rebase = service.start(command("retry-note"));
        rebase = service.advance(rebase.id());
        rebase = service.advance(rebase.id());
        turns.failCurrent();
        rebase = service.advance(rebase.id());
        assertThat(rebase.status()).isEqualTo(Status.PAUSED);
        assertThat(rebase.pauseReason()).contains(PauseReason.AGENT_TURN_FAILED);

        BranchRebase retried = service.sendMessage(rebase.id(), "Prefer the target API shape");

        assertThat(retried.status()).isEqualTo(Status.RUNNING);
        assertThat(retried.currentTurnId()).isPresent();
        assertThat(turns.lastPrompt()).contains("Prefer the target API shape");
        assertThat(retried.attemptCount()).isEqualTo(2);
    }

    @Test
    void retriesAnAmbiguousTurnStartWithTheSameAttemptAndKey()
    {
        conflict();
        BranchRebase rebase = service.start(command("ambiguous-turn-start"));
        rebase = service.advance(rebase.id());
        turns.failAfterStartingOnce();

        BranchRebase paused = service.advance(rebase.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.journal().orElseThrow().type()).isEqualTo(BranchRebase.ActionType.AGENT_TURN);
        String turnKey = paused.journal().orElseThrow().idempotencyKey();
        BranchRebase resumed = service.sendMessage(paused.id(), "Try the same conflict again");
        assertThat(resumed.currentTurnId()).isPresent();
        assertThat(resumed.attemptCount()).isEqualTo(1);
        assertThat(resumed.journal().orElseThrow().idempotencyKey()).isEqualTo(turnKey);
        assertThat(turns.values()).hasSize(1);
    }

    @Test
    void movesAWorkBranchWhoseCommitsAreAlreadyInTheBase()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", workBranch());
        String original = change("feature.txt", "feature\n", "Feature");
        git(repository, "checkout", "--quiet", "main");
        git(repository, "merge", "--quiet", "--ff-only", workBranch());
        String base = change("main.txt", "main\n", "Main after feature");
        git(repository, "checkout", "--quiet", workBranch());

        BranchRebase rebase = service.start(command("already-upstream"));
        rebase = service.advance(rebase.id());

        assertThat(rebase.status()).isEqualTo(Status.FINISHED);
        assertThat(rebase.resultWorkTip()).contains(base);
        assertThat(head()).isEqualTo(base).isNotEqualTo(original);
    }

    @Test
    void rejectsMergeCommitsAndDetachedOrWrongTaskScope()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", workBranch());
        change("feature.txt", "feature\n", "Feature");
        git(repository, "checkout", "--quiet", "-b", "side", "main");
        change("side.txt", "side\n", "Side");
        git(repository, "checkout", "--quiet", workBranch());
        git(repository, "merge", "--quiet", "--no-ff", "-m", "Merge side", "side");

        assertThatThrownBy(() -> service.start(command("merge")))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("merge commits");
    }

    @Test
    void rejectsDirtyDetachedSameAndRemoteTrackingInputs()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", workBranch());
        write("dirty.txt", "not committed\n");
        assertThatThrownBy(() -> service.start(command("dirty")))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("dirty");

        git(repository, "clean", "-fd");
        git(repository, "checkout", "--quiet", "--detach");
        assertThatThrownBy(() -> service.start(command("detached")))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("generated task branch");

        git(repository, "checkout", "--quiet", workBranch());
        StartBranchRebaseCommand same = new StartBranchRebaseCommand(
                taskWorktree, workBranch(), "same-branch");
        assertThatThrownBy(() -> service.start(same))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("same");

        StartBranchRebaseCommand remote = new StartBranchRebaseCommand(
                taskWorktree, "refs/remotes/origin/main", "remote-ref");
        assertThatThrownBy(() -> service.start(remote))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("valid local branch");
    }

    @Test
    void repeatedStartReturnsTheSameDurableRebase()
    {
        cleanDivergence();
        BranchRebase first = service.start(command("same"));
        BranchRebase second = service.start(command("same"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.values()).hasSize(1);
    }

    @Test
    void rejectsAnIdempotencyKeyReusedForAnotherRequest()
    {
        cleanDivergence();
        service.start(command("same-request-key"));
        StartBranchRebaseCommand changed = new StartBranchRebaseCommand(
                taskWorktree, workBranch(), "same-request-key");

        assertThatThrownBy(() -> service.start(changed))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("another branch rebase");
    }

    private Tips cleanDivergence()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", workBranch());
        String original = change("feature.txt", "feature\n", "Feature");
        git(repository, "checkout", "--quiet", "main");
        String base = change("main.txt", "main\n", "Main");
        git(repository, "checkout", "--quiet", workBranch());
        return new Tips(original, base);
    }

    private Conflict conflict()
    {
        write("file.txt", "seed\n");
        commit("Base");
        git(repository, "checkout", "--quiet", "-b", workBranch());
        write("file.txt", "feature\n");
        commit("Feature conflict");
        String source = head();
        String original = source;
        git(repository, "checkout", "--quiet", "main");
        write("file.txt", "main\n");
        commit("Main conflict");
        String base = head();
        git(repository, "checkout", "--quiet", workBranch());
        return new Conflict(original, base, source);
    }

    private StartBranchRebaseCommand command(String key)
    {
        return new StartBranchRebaseCommand(
                taskWorktree,
                "main",
                key);
    }

    private void seed()
    {
        write("seed.txt", "seed\n");
        commit("Base");
    }

    private String change(String path, String content, String message)
    {
        write(path, content);
        commit(message);
        return head();
    }

    private void write(String path, String content)
    {
        try {
            Path file = repository.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void commit(String message)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", message);
    }

    private String head()
    {
        return git(repository, "rev-parse", "HEAD");
    }

    private WorktreeSnapshot worktreeSnapshot()
    {
        GitCommandRunner.Result branch = GIT.run(repository, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (!branch.successful() || !branch.output().strip().equals(workBranch())) {
            throw new BranchRebaseException("Worktree is not on the generated task branch");
        }
        String status = git(repository, "status", "--porcelain=v1", "--untracked-files=all");
        return new WorktreeSnapshot(
                taskWorktree,
                head(),
                !status.isBlank(),
                false);
    }

    private String workBranch()
    {
        return "bytequay/task/" + taskId;
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output().strip();
    }

    private record Tips(String original, String base) {}

    private record Conflict(String original, String base, String source) {}

    private static final class InMemoryStore
            implements BranchRebaseStore
    {
        private final Map<BranchRebaseId, BranchRebase> rebases = new LinkedHashMap<>();

        @Override
        public synchronized BranchRebase createOrGet(BranchRebase rebase)
        {
            Optional<BranchRebase> existing = findByIdempotencyKey(rebase.startIdempotencyKey());
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            rebases.put(rebase.id(), rebase);
            return rebase;
        }

        @Override
        public synchronized Optional<BranchRebase> findByIdempotencyKey(String idempotencyKey)
        {
            return rebases.values().stream()
                    .filter(rebase -> rebase.startIdempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public synchronized Optional<BranchRebase> find(BranchRebaseId rebaseId)
        {
            return Optional.ofNullable(rebases.get(rebaseId));
        }

        @Override
        public synchronized List<BranchRebase> listRunning(int limit)
        {
            return rebases.values().stream()
                    .filter(rebase -> rebase.status() == Status.RUNNING)
                    .sorted(Comparator.comparing(BranchRebase::updatedAt))
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public synchronized BranchRebase save(BranchRebase rebase)
        {
            BranchRebase current = rebases.get(rebase.id());
            if (current == null || current.version() != rebase.version()) {
                throw new BranchRebaseException("version changed");
            }
            BranchRebase saved = rebase.withVersion(rebase.version() + 1);
            rebases.put(saved.id(), saved);
            return saved;
        }

        public List<BranchRebase> values()
        {
            return new ArrayList<>(rebases.values());
        }
    }

    private static final class FakeTurns
            implements AgentTurnService
    {
        private final Map<TurnId, AgentTurn> turns = new LinkedHashMap<>();
        private TurnId current;
        private String lastPrompt;
        private boolean failAfterStarting;

        @Override
        public AgentTurnSettings effectiveSettings(TaskId taskId)
        {
            throw new UnsupportedOperationException("this test never describes a turn before starting it");
        }

        @Override
        public AgentRuntimeStopImpact stopImpact(TaskId taskId)
        {
            throw new UnsupportedOperationException("this test never stops a task runtime");
        }

        @Override
        public AgentTask stopRuntime(TaskId taskId, String generation)
        {
            throw new UnsupportedOperationException("this test never stops a task runtime");
        }

        @Override
        public AgentInputDelivery steer(SteerAgentTurnCommand command)
        {
            throw new UnsupportedOperationException("this test never steers or queues a turn");
        }

        @Override
        public Optional<AgentInputDelivery> findSteerByIdempotencyKey(TurnId turnId, String idempotencyKey)
        {
            return Optional.empty();
        }

        @Override
        public QueuedAgentInput enqueue(QueueAgentInputCommand command)
        {
            throw new UnsupportedOperationException("this test never steers or queues a turn");
        }

        @Override
        public List<QueuedAgentInput> queued(TaskId taskId)
        {
            throw new UnsupportedOperationException("this test never steers or queues a turn");
        }

        @Override
        public Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey)
        {
            return Optional.empty();
        }

        @Override
        public QueuedAgentInput removeQueued(TaskId taskId, QueuedInputId queuedInputId)
        {
            throw new UnsupportedOperationException("this test never steers or queues a turn");
        }

        @Override
        public AgentTurn resumeQueued(TaskId taskId)
        {
            throw new UnsupportedOperationException("this test never steers or queues a turn");
        }

        @Override
        public AgentTurn start(StartAgentTurnCommand command)
        {
            lastPrompt = command.message();
            Optional<AgentTurn> existing = turns.values().stream()
                    .filter(turn -> turn.taskId().equals(command.taskId())
                            && turn.idempotencyKey().equals(command.idempotencyKey()))
                    .findFirst();
            if (existing.isPresent()) {
                current = existing.orElseThrow().id();
                return existing.orElseThrow();
            }
            current = TurnId.random();
            AgentTurn turn = AgentTurn.accepted(current, command.taskId(), command.idempotencyKey(), turnSettings(), NOW)
                    .markDeliveryStarted(NOW)
                    .running(new ProviderTurnRef("provider-" + current), Optional.empty());
            turns.put(current, turn);
            if (failAfterStarting) {
                failAfterStarting = false;
                throw new BranchRebaseException("turn result was lost");
            }
            return turn;
        }

        @Override
        public Optional<AgentTurn> findTurnByIdempotencyKey(TaskId taskId, String idempotencyKey)
        {
            return Optional.empty();
        }

        private static AgentTurnSettings turnSettings()
        {
            return new AgentTurnSettings(
                    Optional.empty(),
                    Optional.empty(),
                    AgentPermissionPolicy.AUTO,
                    AgentSandboxPolicy.WORKSPACE_WRITE,
                    Duration.ofMinutes(5),
                    ImmutableList.of());
        }

        @Override
        public AgentTurn cancel(TurnId turnId)
        {
            AgentTurn turn = turns.get(turnId);
            if (turn.state().active()) {
                turn = turn.finish(
                        TurnState.CANCELLED,
                        TurnMetrics.empty(),
                        Optional.of("cancelled"),
                        NOW);
                turns.put(turnId, turn);
            }
            return turn;
        }

        @Override
        public AgentTurn get(TurnId turnId)
        {
            return turns.get(turnId);
        }

        public void completeCurrent()
        {
            AgentTurn turn = turns.get(current);
            turns.put(current, turn.finish(
                    TurnState.COMPLETED,
                    TurnMetrics.empty(),
                    Optional.empty(),
                    NOW));
        }

        public void failCurrent()
        {
            AgentTurn turn = turns.get(current);
            turns.put(current, turn.finish(
                    TurnState.FAILED,
                    TurnMetrics.empty(),
                    Optional.of("failed"),
                    NOW));
        }

        public String lastPrompt()
        {
            return lastPrompt;
        }

        public List<AgentTurn> values()
        {
            return new ArrayList<>(turns.values());
        }

        public void failAfterStartingOnce()
        {
            failAfterStarting = true;
        }
    }
}
