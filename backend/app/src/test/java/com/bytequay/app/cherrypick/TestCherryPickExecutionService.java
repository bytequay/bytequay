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
import com.bytequay.app.service.github.BoundedProcessRunner;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCherryPickExecutionService
{
    private static final GitCommandRunner GIT = new GitCommandRunner();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);

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
        // A checked-in wrapper so validation runs for real. It fails when a
        // resolution leaves the marker behind, which is how the repair tests
        // make a build go red.
        writeExecutable(upstream, "mvnw", "#!/bin/sh\nif grep -q BROKEN seed.txt; then\n"
                + "  echo 'compilation failure: seed.txt is BROKEN'\n  exit 1\nfi\nexit 0\n");
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
                new CherryPickValidation(new BoundedProcessRunner()),
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
    void recordsAWholeBatchInOneAdvance()
    {
        String base = head(upstream);
        for (int index = 1; index <= 3; index++) {
            write(upstream, "file" + index + ".txt", "content " + index + "\n");
            commit(upstream, "Upstream change " + index);
        }

        CherryPickExecution started = start(base, ImmutableList.of());
        CherryPickExecution afterOne = service.advance(started.id());

        assertThat(afterOne.status()).as("one advance covers the whole clean range").isEqualTo(Status.FINISHED);
        assertThat(afterOne.items()).allMatch(item -> item.result() == Result.APPLIED);
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
        CherryPickExecution first = service.start(command, clone, worktreeParent);
        CherryPickExecution second = service.start(command, clone, worktreeParent);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.listRecent(10)).hasSize(1);
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
                        "stale-key"),
                clone,
                worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("stale");

        assertThatThrownBy(() -> service.start(
                command(preview, ImmutableList.of("0".repeat(40)), "ineligible-key"), clone, worktreeParent))
                .isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining("not an eligible commit");
    }

    @Test
    void rejectsASecondExecutionForTheSameTargetBranch()
    {
        String base = head(upstream);
        write(upstream, "file.txt", "content\n");
        commit(upstream, "Upstream change");

        service.start(command(preview(base), ImmutableList.of(), "first-key"), clone, worktreeParent);

        assertThatThrownBy(() -> service.start(
                command(preview(base), ImmutableList.of(), "second-key"), clone, worktreeParent))
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

        CherryPickExecution cancelled = service.cancel(started.id(), clone);

        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(Files.exists(started.workspace().worktree())).isFalse();
        assertThat(git(clone, "branch", "--list", started.workspace().workBranch())).isEmpty();
        assertThat(git(clone, "rev-parse", started.workspace().localTargetBranch()))
                .as("the local target branch is untouched")
                .isEqualTo(localTarget);
        assertThat(service.cancel(started.id(), clone).status()).isEqualTo(Status.FINISHED);
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
        agent.then(() -> resolve(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, conflicting).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, conflicting).agentTurnCount()).isEqualTo(1);
        assertThat(agent.turnsStarted()).isEqualTo(1);

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
        agent.then(() -> resolve(worktree, "seed.txt", "fork edit\n"), TurnState.COMPLETED);

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
        agent.then(() -> resolve(worktree, "seed.txt", "resolved\n"), TurnState.COMPLETED);
        runToCompletion(started);

        assertThat(agent.prompts()).hasSize(1);
        assertThat(agent.prompts().getFirst())
                .contains("stage the result. Then stop")
                .contains("ByteQuay runs git cherry-pick --continue itself")
                .contains("Do not run git cherry-pick --continue, --skip, or --abort")
                .contains("push, use gh, or")
                .contains("Your final message is for the user")
                .contains("Conflict files:")
                .contains("seed.txt");
    }

    @Test
    void repairsAFailedValidationByAmendingTheSameCommit()
    {
        String base = head(upstream);
        write(upstream, "seed.txt", "upstream edit\n");
        commit(upstream, "Conflicting change");
        String conflicting = head(upstream);
        write(fork, "seed.txt", "fork edit\n");
        commit(fork, "Fork edit");

        CherryPickExecution started = start(base, ImmutableList.of());
        Path worktree = started.workspace().worktree();
        // The first resolution compiles red; the repair amends the same commit.
        agent.then(() -> resolve(worktree, "seed.txt", "BROKEN resolution\n"), TurnState.COMPLETED)
                .then(() -> {
                    write(worktree, "seed.txt", "good resolution\n");
                    git(worktree, "add", "seed.txt");
                    git(worktree, "commit", "--quiet", "--amend", "--no-edit");
                }, TurnState.COMPLETED);

        CherryPickExecution finished = runToCompletion(started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(item(finished, conflicting).result()).isEqualTo(Result.APPLIED);
        assertThat(item(finished, conflicting).validation().orElseThrow().passed()).isTrue();
        assertThat(agent.turnsStarted()).isEqualTo(2);
        assertThat(git(worktree, "rev-list", "--count", finished.range().initialTargetSha() + "..HEAD"))
                .as("a repair amends rather than adding a commit")
                .isEqualTo("1");
        assertThat(git(worktree, "log", "-1", "--format=%B"))
                .as("amending preserves the -x line")
                .contains("(cherry picked from commit " + conflicting + ")");
        assertThat(agent.prompts().getLast())
                .contains("amend the same commit")
                .contains("git commit --amend --no-edit")
                .contains("compilation failure")
                .contains("saying it passes does not make it pass");
    }

    private static void resolve(Path worktree, String name, String content)
    {
        write(worktree, name, content);
        git(worktree, "add", name);
    }

    private CherryPickExecution runToCompletion(CherryPickExecution execution)
    {
        CherryPickExecution current = execution;
        for (int step = 0; step < 30 && current.status() == Status.RUNNING; step++) {
            current = service.advance(current.id());
        }
        return current;
    }

    private CherryPickExecution start(String baseRef, List<String> skipped)
    {
        return service.start(command(preview(baseRef), skipped, "key-" + skipped.size() + "-" + baseRef),
                clone, worktreeParent);
    }

    private CherryPickPreview preview(String baseRef)
    {
        return previews.preview(new PreviewCherryPickRangeCommand(
                clone, "origin", baseRef, "origin/main", "cork", "main"));
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
                key);
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

    private static void writeExecutable(Path repository, String name, String content)
    {
        write(repository, name, content);
        assertThat(repository.resolve(name).toFile().setExecutable(true)).isTrue();
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
