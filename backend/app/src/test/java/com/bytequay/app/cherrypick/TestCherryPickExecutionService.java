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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.TurnState;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitSequencerDriver;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionType;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Result;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCherryPickExecutionService
{
    private static final GitCommandRunner GIT = new GitCommandRunner();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String WORKSPACE_ID = "workspace-id";

    @TempDir
    private Path temporaryDirectory;

    private Path upstream;
    private Path fork;
    private Path clone;
    private Path worktreeParent;
    private InMemoryCherryPickExecutionStore store;
    private CherryPickPreviewService previews;
    private CherryPickExecutionService service;
    private ScriptedCherryPickAgent agent;

    @BeforeEach
    void setUp()
            throws IOException
    {
        upstream = init("upstream");
        fork = init("fork");
        write(upstream, "seed.txt", "seed\n");
        write(upstream, "pom.xml", "<project/>\n");
        commit(upstream, "Seed");
        git(fork, "remote", "add", "upstream", upstream.toString());
        git(fork, "fetch", "--quiet", "upstream");
        git(fork, "checkout", "--quiet", "-B", "main", "upstream/main");

        clone = init("clone");
        // GitHub-shaped remote URLs mapped to the local fixtures, so repository
        // identity parses the way it will in production.
        git(clone, "remote", "add", "origin", "https://github.com/trinodb/trino.git");
        git(clone, "remote", "add", "cork", "https://github.com/cork/trino.git");
        git(clone, "config", "url." + upstream.toUri() + ".insteadOf", "https://github.com/trinodb/trino.git");
        git(clone, "config", "url." + fork.toUri() + ".insteadOf", "https://github.com/cork/trino.git");
        worktreeParent = Files.createDirectories(temporaryDirectory.resolve("worktrees"));

        store = new InMemoryCherryPickExecutionStore();
        previews = new CherryPickPreviewService(
                GIT,
                new CherryPickConflictScout(GIT),
                new CherryPickPullRequestLookup(
                        TestCherryPickPullRequestLookup.offlineClient(),
                        () -> {
                            throw new IllegalStateException("execution tests use no GitHub credential");
                        }),
                CLOCK);
        agent = new ScriptedCherryPickAgent(temporaryDirectory);
        service = new CherryPickExecutionService(
                previews,
                store,
                new CherryPickWorkspace(GIT),
                new GitSequencerDriver(GIT),
                GIT,
                agent,
                agent,
                agent,
                CLOCK);
    }

    @Test
    void appliesACleanRangeInOrderAndFinishes()
    {
        String base = head(upstream);
        for (int index = 1; index <= 4; index++) {
            write(upstream, "file" + index + ".txt", "content " + index + "\n");
            commit(upstream, "Upstream change " + index);
        }

        CherryPickExecution finished = runToCompletion(start(base, ImmutableList.of()));

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(finished.finishReason()).contains(FinishReason.COMPLETED);
        assertThat(finished.items()).allMatch(item -> item.result() == Result.APPLIED);

        Path worktree = finished.workspace().worktree();
        assertThat(git(worktree, "rev-list", "--count", finished.range().initialTargetSha() + "..HEAD"))
                .isEqualTo("4");
        assertThat(git(worktree, "symbolic-ref", "--short", "HEAD"))
                .isEqualTo(finished.workspace().workBranch());

        // Every recorded target SHA is the commit whose -x line names its source.
        for (CherryPickItem item : finished.items()) {
            String message = git(worktree, "log", "-1", "--format=%B", item.targetSha().orElseThrow());
            assertThat(message).contains("(cherry picked from commit " + item.sourceSha() + ")");
        }
        assertThat(finished.items().stream().map(CherryPickItem::position))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void appliesExactlyOneCommitPerAdvance()
    {
        String base = head(upstream);
        for (int index = 1; index <= 3; index++) {
            write(upstream, "file" + index + ".txt", "content " + index + "\n");
            commit(upstream, "Upstream change " + index);
        }

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();

        CherryPickExecution afterOne = service.advance(started.id());
        assertThat(afterOne.status()).isEqualTo(Status.RUNNING);
        assertThat(afterOne.items().stream().filter(item -> item.result() == Result.APPLIED))
                .as("one Git operation carries one source commit")
                .hasSize(1);
        assertThat(git(worktree, "rev-list", "--count", started.range().initialTargetSha() + "..HEAD"))
                .isEqualTo("1");
        assertThat(afterOne.cursor().orElseThrow().position()).isEqualTo(2);

        CherryPickExecution afterTwo = service.advance(afterOne.id());
        assertThat(afterTwo.items().stream().filter(item -> item.result() == Result.APPLIED)).hasSize(2);
        assertThat(afterTwo.cursor().orElseThrow().position()).isEqualTo(3);

        assertThat(runToCompletion(afterTwo).status()).isEqualTo(Status.FINISHED);
    }

    @Test
    void honoursConfirmedSkipsWithoutRunningGitForThem()
    {
        String base = head(upstream);
        write(upstream, "kept.txt", "kept\n");
        commit(upstream, "Kept change");
        write(upstream, "skipped.txt", "skipped\n");
        commit(upstream, "Skipped change");
        String skippedSha = head(upstream);

        CherryPickExecution finished = runToCompletion(start(base, ImmutableList.of(skippedSha)));

        CherryPickItem skipped = item(finished, skippedSha);
        assertThat(skipped.result()).isEqualTo(Result.USER_SKIPPED);
        assertThat(skipped.targetSha()).isEmpty();
        assertThat(git(finished.workspace().worktree(), "rev-list", "--count",
                finished.range().initialTargetSha() + "..HEAD"))
                .as("only the kept commit lands")
                .isEqualTo("1");
        assertThat(Files.exists(finished.workspace().worktree().resolve("skipped.txt"))).isFalse();
    }

    @Test
    void skipsBothKindsOfEmptyCommitWithoutPausing()
    {
        String base = head(upstream);
        git(upstream, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String emptyUpstream = head(upstream);
        write(upstream, "shared.txt", "shared\n");
        commit(upstream, "Redundant change");
        String redundant = head(upstream);
        write(upstream, "unique.txt", "unique\n");
        commit(upstream, "Unique change");

        // The fork already contains the redundant change under its own commit.
        write(fork, "shared.txt", "shared\n");
        commit(fork, "Fork already has it");

        CherryPickExecution finished = runToCompletion(start(base, ImmutableList.of()));

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, emptyUpstream).result()).isEqualTo(Result.EMPTY_SKIPPED);
        assertThat(item(finished, emptyUpstream).targetSha()).isEmpty();
        assertThat(item(finished, redundant).result()).isEqualTo(Result.EMPTY_SKIPPED);
        assertThat(finished.items().stream().filter(item -> item.result() == Result.APPLIED))
                .hasSize(1);
    }

    @Test
    void returnsTheSameExecutionForARepeatedIdempotencyKey()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        CherryPickPreview preview = preview(base);
        StartCherryPickExecutionCommand command = command(preview, ImmutableList.of(), "same-key");
        CherryPickExecution first = service.start(command, WORKSPACE_ID, clone, worktreeParent);
        CherryPickExecution second = service.start(command, WORKSPACE_ID, clone, worktreeParent);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.listRecent(10)).hasSize(1);
    }

    @Test
    void rejectsPreviewExecutionAndCancelFromAnotherWorkspace()
            throws IOException
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");
        CherryPickPreview preview = preview(base);

        assertThatThrownBy(() -> service.start(
                command(preview, ImmutableList.of(), "wrong-workspace"),
                "another-workspace",
                clone,
                worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("does not belong");
        assertThat(Files.list(worktreeParent)).isEmpty();

        CherryPickExecution started = service.start(
                command(preview, ImmutableList.of(), "right-workspace"),
                WORKSPACE_ID,
                clone,
                worktreeParent);
        assertThatThrownBy(() -> service.cancel(started.id(), "another-workspace", clone))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("does not belong");
        assertThat(Files.exists(started.workspace().worktree())).isTrue();
    }

    @Test
    void rejectsAStalePreviewAndAnIneligibleSkip()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");
        CherryPickPreview preview = preview(base);

        assertThatThrownBy(() -> service.start(
                new StartCherryPickExecutionCommand(
                        preview.previewId(),
                        preview.sourceBaseSha(),
                        "0".repeat(40),
                        preview.targetSha(),
                        ImmutableList.of(),
                        "claude",
                        "stale-key",
                        new PullRequestDraft("Cherry-pick changes", "")),
                WORKSPACE_ID,
                clone,
                worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("stale");

        assertThatThrownBy(() -> service.start(
                command(preview, ImmutableList.of("0".repeat(40)), "ineligible-key"), WORKSPACE_ID, clone, worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("not an eligible commit");
    }

    @Test
    void rejectsWhenTheSynchronizedTargetMovedAfterPreview()
            throws IOException
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");
        CherryPickPreview preview = preview(base);

        write(fork, "target-moved.txt", "new target\n");
        commit(fork, "Target moved");

        assertThatThrownBy(() -> service.start(
                command(preview, ImmutableList.of(), "moved-target"), WORKSPACE_ID, clone, worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("stale");
        assertThat(store.listRecent(10)).isEmpty();
        assertThat(Files.list(worktreeParent)).isEmpty();
    }

    @Test
    void rejectsASecondExecutionForTheSameTargetBranch()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        service.start(command(preview(base), ImmutableList.of(), "first-key"), WORKSPACE_ID, clone, worktreeParent);

        assertThatThrownBy(() -> service.start(
                command(preview(base), ImmutableList.of(), "second-key"), WORKSPACE_ID, clone, worktreeParent))
                .isInstanceOf(CherryPickConflictException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void cancellationRemovesOnlyWhatByteQuayCreated()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        CherryPickExecution started = start(base, ImmutableList.of());
        String localTarget = git(clone, "rev-parse", started.workspace().localTargetBranch());

        CherryPickExecution cancelled = service.cancel(started.id(), WORKSPACE_ID, clone);

        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(Files.exists(started.workspace().worktree())).isFalse();
        assertThat(git(clone, "branch", "--list", started.workspace().workBranch())).isEmpty();
        assertThat(git(clone, "rev-parse", started.workspace().localTargetBranch()))
                .as("the local target branch is untouched")
                .isEqualTo(localTarget);
        assertThat(service.cancel(started.id(), WORKSPACE_ID, clone).status()).isEqualTo(Status.FINISHED);
    }

    @Test
    void handsAConflictToTheAgentAndAcceptsOnlyWhatGitShows()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String conflicting = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, conflicting).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, conflicting).agentTurnCount()).isEqualTo(1);
        assertThat(agent.turnsStarted()).isEqualTo(1);
        assertThat(item(finished, conflicting).conflictPaths().getFirst().stages())
                .extracting(CherryPickItem.IndexStage::stage)
                .containsExactly(1, 2, 3);

        String message = git(worktree, "log", "-1", "--format=%B",
                item(finished, conflicting).targetSha().orElseThrow());
        assertThat(message)
                .as("the -x line survives the agent's resolution")
                .contains("(cherry picked from commit " + conflicting + ")");
        assertThat(git(worktree, "rev-list", "--count", finished.range().initialTargetSha() + "..HEAD"))
                .as("one source commit creates at most one target commit")
                .isEqualTo("1");
    }

    @Test
    void retriesTheSameConflictAndStopsAfterThreeAttempts()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String conflicting = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        // The agent claims success every time but never stages anything.
        agent.then(() -> {}, TurnState.COMPLETED)
                .then(() -> {}, TurnState.COMPLETED)
                .then(() -> {}, TurnState.COMPLETED);

        CherryPickExecution paused = runToCompletion(started);

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.RETRY_LIMIT_REACHED);
        assertThat(item(paused, conflicting).result()).isEqualTo(Result.PENDING);
        assertThat(item(paused, conflicting).agentTurnCount()).isEqualTo(3);
        assertThat(agent.turnsStarted()).as("three attempts, then stop").isEqualTo(3);
    }

    @Test
    void pausesWhenTheTurnItselfFails()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        agent.then(() -> {}, TurnState.FAILED);

        CherryPickExecution paused = runToCompletion(started);

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.AGENT_TURN_FAILED);
        assertThat(agent.turnsStarted()).isEqualTo(1);
    }

    @Test
    void skipsAResolutionThatBecomesEmptyWithoutAnotherTurn()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "shared result\n");
        commit(upstream, "Conflicting change");
        String conflicting = head(upstream);
        write(upstream, "later.txt", "later\n");
        commit(upstream, "Later change");
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        // Resolving back to what the target already has leaves nothing to commit.
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "fork edit\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, conflicting).result()).isEqualTo(Result.EMPTY_SKIPPED);
        assertThat(item(finished, conflicting).targetSha()).isEmpty();
        assertThat(agent.turnsStarted()).as("an empty resolution needs no second turn").isEqualTo(1);
    }

    @Test
    void promptTellsTheAgentToStageAndStopAndNeverReadsItsReply()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);
        runToCompletion(started);

        assertThat(agent.prompts()).hasSize(1);
        assertThat(agent.prompts().getFirst())
                .contains("ByteQuay has already committed")
                .contains("git commit --fixup=")
                .contains("Do not run git cherry-pick --continue, --skip, or --abort")
                .contains("push, use gh, or")
                .contains("Your final message is for the user")
                .contains("Conflict files:")
                .contains("seed.txt")
                .contains("Build or type-check the modules you touched");
    }

    @Test
    void acceptsAResolutionWithoutBuildingIt()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String conflicting = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "BROKEN resolution\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        // ByteQuay cannot tell whether a resolution compiles, so it does not try.
        // The agent owns that judgement, and one turn is all this conflict costs.
        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, conflicting).result()).isEqualTo(Result.APPLIED);
        assertThat(agent.turnsStarted()).isEqualTo(1);
        assertThat(git(worktree, "rev-list", "--count", finished.range().initialTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    @Test
    void asksTheAgentToAmendTheSameFixupWhenMarkersRemain()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(
                        worktree,
                        "seed.txt",
                        "<<<<<<< target\nfork edit\n=======\nupstream edit\n>>>>>>> source\n"),
                TurnState.COMPLETED);
        agent.then(() -> amendFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, source).agentTurnCount()).isEqualTo(2);
        assertThat(agent.prompts().getLast())
                .contains("Fixup commit to repair")
                .contains("git commit --amend --no-edit");
        assertThat(git(worktree, "rev-list", "--count", started.currentTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    private static void resolve(Path worktree, String name, String content)
    {
        write(worktree, name, content);
        git(worktree, "add", name);
    }

    private static void resolveAndFixup(Path worktree, String name, String content)
    {
        String conflictCommit = head(worktree);
        resolve(worktree, name, content);
        git(worktree, "commit", "--quiet", "--fixup=" + conflictCommit);
    }

    private static void amendFixup(Path worktree, String name, String content)
    {
        resolve(worktree, name, content);
        git(worktree, "commit", "--quiet", "--amend", "--no-edit");
    }

    @Test
    void survivesARestartAtEveryPointWithoutRepeatingAMutation()
    {
        String base = head(upstream);
        for (int index = 1; index <= 3; index++) {
            write(upstream, "file" + index + ".txt", "content " + index + "\n");
            commit(upstream, "Upstream change " + index);
        }

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();

        // Every advance observes Git first, so calling it again after a "crash"
        // reconciles instead of re-running the operation.
        CherryPickExecution finished = runToCompletion(started);
        String tip = git(worktree, "rev-parse", "HEAD");
        assertThat(finished.status()).isEqualTo(Status.FINISHED);

        assertThat(service.advance(started.id()).status()).isEqualTo(Status.FINISHED);
        assertThat(git(worktree, "rev-parse", "HEAD"))
                .as("a repeated advance creates nothing")
                .isEqualTo(tip);
        assertThat(git(worktree, "rev-list", "--count", started.range().initialTargetSha() + "..HEAD"))
                .isEqualTo("3");
    }

    @Test
    void reconcilesAnAppliedCommitCreatedBeforeACrash()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");
        String source = head(upstream);

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();

        // Git did the work; the crash happened before the result was recorded.
        git(worktree, "cherry-pick", "-x", source);
        assertThat(started.items().getFirst().result()).isEqualTo(Result.PENDING);

        CherryPickExecution recovered = runToCompletion(started);

        assertThat(recovered.status()).isEqualTo(Status.FINISHED);
        assertThat(item(recovered, source).result()).isEqualTo(Result.APPLIED);
        assertThat(git(worktree, "rev-list", "--count", started.range().initialTargetSha() + "..HEAD"))
                .as("the pre-crash commit is adopted, not duplicated")
                .isEqualTo("1");
    }

    @Test
    void adoptsAnEmptySkipCompletedBeforeItsResultWasSaved()
    {
        String base = head(upstream);
        git(upstream, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String source = head(upstream);

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        GitSequencerDriver driver = new GitSequencerDriver(GIT);
        com.bytequay.app.git.SequenceState empty = driver.beginCherryPick(
                worktree, started.currentTargetSha(), source);
        assertThat(empty.kind()).isEqualTo(com.bytequay.app.git.SequenceState.Kind.STOPPED_EMPTY);
        CherryPickExecution journalled = store.save(started
                .withStep(Step.APPLYING, CLOCK.instant())
                .withJournal(new ActionJournal(
                        ActionType.SKIP_EMPTY,
                        Optional.of(source),
                        started.currentTargetSha(),
                        1,
                        "skip",
                        CLOCK.instant()), CLOCK.instant()));

        assertThat(driver.skipEmpty(worktree, started.currentTargetSha(), source).kind())
                .isEqualTo(com.bytequay.app.git.SequenceState.Kind.COMPLETED);
        CherryPickExecution finished = runToCompletion(journalled);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.EMPTY_SKIPPED);
        assertThat(item(finished, source).targetSha()).isEmpty();
        assertThat(finished.journal()).isEmpty();
        assertThat(head(worktree)).isEqualTo(started.currentTargetSha());
    }

    @Test
    void adoptsAConflictSnapshotCreatedBeforeItsResultWasSaved()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        GitSequencerDriver driver = new GitSequencerDriver(GIT);
        com.bytequay.app.git.SequenceState conflict = driver.beginCherryPick(
                worktree, started.currentTargetSha(), source);
        CherryPickExecution journalled = store.save(started
                .recordConflict(source, conflictPaths(conflict), CLOCK.instant())
                .withStep(Step.RESOLVING, CLOCK.instant())
                .withJournal(new ActionJournal(
                        ActionType.COMMIT_CONFLICT_SNAPSHOT,
                        Optional.of(source),
                        started.currentTargetSha(),
                        1,
                        "snapshot",
                        CLOCK.instant()), CLOCK.instant()));

        String conflictCommit = driver.commitConflictSnapshot(
                worktree, started.currentTargetSha(), source, conflict.conflicts()).head();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(journalled);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, source).targetSha().orElseThrow()).isNotEqualTo(conflictCommit);
        assertThat(git(worktree, "rev-list", "--count", started.currentTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    @Test
    void adoptsAnAgentFixupCreatedBeforeATurnWasSaved()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        CherryPickExecution waitingForFixup = service.advance(started.id());
        waitingForFixup = service.advance(waitingForFixup.id());
        Path worktree = waitingForFixup.workspace().worktree();
        assertThat(waitingForFixup.journal().map(ActionJournal::type)).contains(ActionType.AGENT_TURN);

        resolveAndFixup(worktree, "seed.txt", "resolved after restart\n");
        CherryPickExecution finished = runToCompletion(waitingForFixup);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.APPLIED);
        assertThat(agent.turnsStarted()).isZero();
        assertThat(git(worktree, "rev-list", "--count", started.currentTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    @Test
    void finishesNormalizationAfterACrashFollowingTheSoftReset()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);

        CherryPickExecution current = service.advance(started.id());
        current = service.advance(current.id());
        current = service.advance(current.id());
        current = service.advance(current.id());
        assertThat(current.journal().map(ActionJournal::type)).contains(ActionType.NORMALIZE_CONFLICT_PAIR);
        String fixup = current.journal().orElseThrow().expectedHead();
        String conflictCommit = git(worktree, "rev-parse", fixup + "^");
        git(worktree, "reset", "--soft", started.currentTargetSha());

        CherryPickExecution finished = runToCompletion(current);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, source).targetSha().orElseThrow()).isNotEqualTo(conflictCommit);
        assertThat(git(worktree, "rev-list", "--count", started.currentTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    @Test
    void adoptsNormalizationCompletedBeforeItsResultWasSaved()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);
        CherryPickExecution normalizing = reachNormalization(started);
        String fixup = normalizing.journal().orElseThrow().expectedHead();
        String conflictCommit = git(worktree, "rev-parse", fixup + "^");
        GitSequencerDriver.NormalizationResult result = new GitSequencerDriver(GIT).normalizeConflictPair(
                worktree, started.currentTargetSha(), conflictCommit, fixup);
        assertThat(result.kind()).isEqualTo(GitSequencerDriver.NormalizationResult.Kind.APPLIED);

        CherryPickExecution finished = runToCompletion(normalizing);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).targetSha()).contains(result.head());
        assertThat(git(worktree, "rev-list", "--count", started.currentTargetSha() + "..HEAD"))
                .isEqualTo("1");
    }

    @Test
    void adoptsEmptyPairRemovalCompletedBeforeItsResultWasSaved()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String source = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "fork edit\n"), TurnState.COMPLETED);
        CherryPickExecution normalizing = reachNormalization(started);
        String fixup = normalizing.journal().orElseThrow().expectedHead();
        String conflictCommit = git(worktree, "rev-parse", fixup + "^");
        GitSequencerDriver.NormalizationResult result = new GitSequencerDriver(GIT).normalizeConflictPair(
                worktree, started.currentTargetSha(), conflictCommit, fixup);
        assertThat(result.kind()).isEqualTo(GitSequencerDriver.NormalizationResult.Kind.EMPTY);

        CherryPickExecution finished = runToCompletion(normalizing);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, source).result()).isEqualTo(Result.EMPTY_SKIPPED);
        assertThat(item(finished, source).targetSha()).isEmpty();
        assertThat(head(worktree)).isEqualTo(started.currentTargetSha());
    }

    @Test
    void storesARangeReviewAndClosesTheAgentTask()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        CherryPickExecution finished = runToCompletion(start(base, ImmutableList.of()));

        assertThat(finished.rangeReview()).isPresent();
        assertThat(finished.rangeReview().orElseThrow().truncated()).isFalse();
        assertThat(finished.rangeReview().orElseThrow().output()).contains("Upstream change");
        assertThat(finished.summary().orElseThrow()).contains("1 applied");
        assertThat(agent.taskCompleted()).isTrue();
    }

    @Test
    void refusesToAdoptACommitThePlanNeverAuthorised()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        // Something outside ByteQuay commits into the work branch.
        write(worktree, "stray.txt", "stray\n");
        git(worktree, "add", "stray.txt");
        git(worktree, "commit", "--quiet", "-m", "Stray commit");

        CherryPickExecution paused = service.advance(started.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.RECONCILIATION_MISMATCH);
        assertThat(paused.summary().orElseThrow()).contains("no cherry-pick origin line");
    }

    @Test
    void acceptsARecoveryMessageOnlyForAPausedRange()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        assertThatThrownBy(() -> service.sendMessage(started.id(), "try harder"))
                .as("a running range takes no instructions")
                .isInstanceOf(CherryPickConflictException.class);

        agent.then(() -> {}, TurnState.FAILED);
        CherryPickExecution paused = runToCompletion(started);
        assertThat(paused.status()).isEqualTo(Status.PAUSED);

        assertThatThrownBy(() -> service.sendMessage(paused.id(), "   "))
                .isInstanceOf(CherryPickInvalidRequestException.class);

        Path worktree = paused.workspace().worktree();
        agent.then(() -> resolveAndFixup(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);
        CherryPickExecution resumed = service.sendMessage(paused.id(), "prefer the upstream side");

        assertThat(resumed.status()).isEqualTo(Status.RUNNING);
        assertThat(agent.prompts().getLast())
                .contains("Note from the user")
                .contains("guidance only")
                .contains("prefer the upstream side");
        assertThat(runToCompletion(resumed).status()).isEqualTo(Status.FINISHED);
    }

    @Test
    void cancellationRefusesAWorktreeItCannotRecognise()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        git(worktree, "checkout", "--quiet", "--detach");

        CherryPickExecution refused = service.cancel(started.id(), WORKSPACE_ID, clone);
        assertThat(refused.status())
                .as("a worktree that is no longer on our branch is left alone")
                .isEqualTo(Status.PAUSED);
        assertThat(Files.exists(worktree)).isTrue();

        git(worktree, "checkout", "--quiet", started.workspace().workBranch());
        assertThat(service.cancel(started.id(), WORKSPACE_ID, clone).status()).isEqualTo(Status.FINISHED);
        assertThat(Files.exists(worktree)).isFalse();
    }

    private CherryPickExecution runToCompletion(CherryPickExecution execution)
    {
        CherryPickExecution current = execution;
        for (int step = 0; step < 30 && current.status() == Status.RUNNING; step++) {
            current = service.advance(current.id());
        }
        return current;
    }

    private CherryPickExecution reachNormalization(CherryPickExecution execution)
    {
        CherryPickExecution current = execution;
        for (int step = 0; step < 6; step++) {
            current = service.advance(current.id());
            if (current.journal().map(ActionJournal::type)
                    .filter(ActionType.NORMALIZE_CONFLICT_PAIR::equals)
                    .isPresent()) {
                return current;
            }
        }
        throw new AssertionError("execution did not reach normalization");
    }

    private static List<CherryPickItem.ConflictPath> conflictPaths(
            com.bytequay.app.git.SequenceState state)
    {
        return state.conflicts().stream()
                .map(conflict -> new CherryPickItem.ConflictPath(
                        conflict.path(),
                        switch (conflict.type()) {
                            case CONTENT -> CherryPickItem.ConflictType.CONTENT;
                            case ADD_ADD -> CherryPickItem.ConflictType.ADD_ADD;
                            case MODIFY_DELETE -> CherryPickItem.ConflictType.MODIFY_DELETE;
                            case SUBMODULE -> CherryPickItem.ConflictType.SUBMODULE;
                        },
                        Optional.empty(),
                        conflict.stages().stream()
                                .map(stage -> new CherryPickItem.IndexStage(
                                        stage.stage(), stage.mode(), stage.blobSha()))
                                .collect(toImmutableList())))
                .collect(toImmutableList());
    }

    private CherryPickExecution start(String baseRef, List<String> skipped)
    {
        return service.start(command(preview(baseRef), skipped, "key-" + skipped.size() + "-" + baseRef),
                WORKSPACE_ID, clone, worktreeParent);
    }

    private CherryPickPreview preview(String baseRef)
    {
        return previews.preview(new PreviewCherryPickRangeCommand(
                clone, Optional.of(WORKSPACE_ID), "origin", baseRef, "origin/main", "cork", "main"));
    }

    private static StartCherryPickExecutionCommand command(
            CherryPickPreview preview,
            List<String> skipped,
            String key)
    {
        return new StartCherryPickExecutionCommand(
                preview.previewId(),
                preview.sourceBaseSha(),
                preview.sourceTipSha(),
                preview.targetSha(),
                skipped,
                "claude",
                key,
                new PullRequestDraft("Cherry-pick changes", "Generated by test"));
    }

    private static CherryPickItem item(CherryPickExecution execution, String sourceSha)
    {
        return execution.items().stream()
                .filter(value -> value.sourceSha().equals(sourceSha))
                .findFirst()
                .orElseThrow(() -> new AssertionError("commit not in plan: " + sourceSha));
    }

    private Path init(String name)
            throws IOException
    {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", root.toString());
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "bytequay@example.com");
        return root.toRealPath();
    }

    private static void write(Path repository, String name, String content)
    {
        try {
            Files.writeString(repository.resolve(name), content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void commit(Path repository, String subject)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", subject);
    }

    private static String head(Path repository)
    {
        return git(repository, "rev-parse", "HEAD");
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
        return result.output().strip();
    }
}
