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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ReviewTurnInputResolver.Reference;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TestRepositoryGuidelineReader
{
    private final TaskId taskId = TaskId.random();
    private final GitCommandRunner git = new GitCommandRunner();
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final RepositoryGuidelineReader reader = new RepositoryGuidelineReader(git, reviews, turns);

    @TempDir
    private Path root;
    private Path repository;

    @BeforeEach
    void repository()
            throws IOException
    {
        // A task's worktree directory is named after the task.
        repository = Files.createDirectories(root.resolve(taskId.toString()));
        command("init", "-q");
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.empty());
    }

    @Test
    void readsRootAndGithubGuidelinesAtTheTaskStartCommitNotItsWorkingTree()
            throws IOException
    {
        write(".github/DEVELOPMENT.md", "Run one module with -pl.\n");
        write("CONTRIBUTING.md", "Keep commits small.\n");
        write("README.md", "Not a guideline.\n");
        Files.createSymbolicLink(repository.resolve("AGENTS.md"), Path.of("CONTRIBUTING.md"));
        String start = commit();
        write("CONTRIBUTING.md", "Rewritten after the task started.\n");
        commit();
        write("CONTRIBUTING.md", "Uncommitted edit.\n");

        Reference guidelines = reader.read(task(start), turn("user"));

        // Root files come before .github, and the symlinked AGENTS.md is left out rather than read twice.
        assertThat(guidelines.guidelines()).containsExactly("CONTRIBUTING.md", ".github/DEVELOPMENT.md");
        assertThat(guidelines.text())
                .contains("at commit " + start)
                .contains("----- BEGIN REPOSITORY GUIDELINES -----\n### CONTRIBUTING.md\n\nKeep commits small.")
                .contains("### .github/DEVELOPMENT.md\n\nRun one module with -pl.")
                .endsWith("----- END REPOSITORY GUIDELINES -----")
                .doesNotContain("Rewritten", "Uncommitted", "Not a guideline");
        assertThat(guidelines.append("Fix the flaky test")).startsWith("Fix the flaky test\n\nRepository guidelines:");
    }

    @Test
    void cutsALongFileAndStopsAtTheTotalBudget()
            throws IOException
    {
        write("AGENTS.md", "a".repeat(20 * 1024));
        write("CONTRIBUTING.md", "c".repeat(15 * 1024));
        write("DEVELOPMENT.md", "d".repeat(10 * 1024));
        write(".github/CONTRIBUTING.md", "Never reached.\n");

        Reference guidelines = reader.read(task(commit()), turn("user"));

        assertThat(guidelines.guidelines()).containsExactly("AGENTS.md", "CONTRIBUTING.md", "DEVELOPMENT.md");
        // 16 KiB of the first file, all 15 KiB of the second, and the last 1 KiB of the 32 KiB budget for the third.
        assertThat(guidelines.text()).contains("### AGENTS.md (first 16 KiB of 20 KiB)").contains("### CONTRIBUTING.md\n")
                .contains("### DEVELOPMENT.md (first 1 KiB of 10 KiB)").doesNotContain("Never reached")
                .contains("a".repeat(16 * 1024 - 3) + "…").doesNotContain("a".repeat(16 * 1024 - 2))
                .contains("c".repeat(15 * 1024))
                .contains("d".repeat(1024 - 3) + "…").doesNotContain("d".repeat(1024 - 2));
    }

    @Test
    void aReviewReadsItsBaseBranchTipAndTheGuidelinesBesideItsChangedPaths()
            throws IOException
    {
        write("CONTRIBUTING.md", "Base rules.\n");
        write("plugin/delta/AGENTS.md", "Delta rules.\n");
        write("plugin/iceberg/AGENTS.md", "Iceberg rules, unchanged by this PR.\n");
        String base = commit();
        write("CONTRIBUTING.md", "The pull request rewrote the rules.\n");
        write("plugin/delta/src/Writer.java", "class Writer {}\n");
        String head = commit();
        AgentReview review = pullRequestReview("review", "workspace", "example/project", 7, taskId.toString(), Instant.EPOCH)
                .withPullRequest(7, OptionalLong.of(1), Instant.EPOCH);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review));
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run(1, base, head, "CONTRIBUTING.md"), run(2, base, head, "plugin/delta/src/Writer.java")));

        Reference guidelines = reader.read(task(head), turn("user"));

        assertThat(guidelines.guidelines()).containsExactly("CONTRIBUTING.md", "plugin/delta/AGENTS.md");
        assertThat(guidelines.text()).contains("at commit " + base).contains("Base rules.").contains("Delta rules.")
                .doesNotContain("rewrote", "Iceberg rules");
    }

    @Test
    void guidelinesThatCannotBeReadLeaveTheTurnInputUnchanged()
    {
        Reference guidelines = reader.read(task("f".repeat(40)), turn("user"));

        assertThat(guidelines).isEqualTo(Reference.NONE);
        assertThat(guidelines.append("Fix the flaky test")).isEqualTo("Fix the flaky test");
    }

    @Test
    void nestedGuidelinesComeFromEveryDirectoryAboveAChangedPathOnce()
    {
        assertThat(RepositoryGuidelineReader.directories(ImmutableList.of(diff("a/b/c.java"), diff("a/d.java"), diff("e.java"), diff(".github/workflows/ci.yml"))))
                .containsExactly(".github", ".github/workflows", "a", "a/b");
        assertThat(RepositoryGuidelineReader.paths(ImmutableList.of(".github", "a")))
                .containsExactly("CLAUDE.md", "AGENTS.md", "CONTRIBUTING.md", "DEVELOPMENT.md",
                        ".github/CLAUDE.md", ".github/AGENTS.md", ".github/CONTRIBUTING.md", ".github/DEVELOPMENT.md", "a/CLAUDE.md", "a/AGENTS.md");
    }

    @Test
    void automaticReviewStepsReuseAcknowledgedGuidelinesOnlyWithinTheirFrozenRun()
            throws IOException
    {
        write("AGENTS.md", "Root rules.\n");
        write("plugin/delta/AGENTS.md", "Delta rules.\n");
        String base = commit();
        AgentTask fresh = task(base);
        AgentTask resumed = fresh.withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "conversation"), Instant.EPOCH);
        AgentTurn first = turn("review-step:first");
        AgentTurn next = turn("review-step:next");
        AgentReview review = pullRequestReview("review", "workspace", "example/project", 7, taskId.toString(), Instant.EPOCH)
                .withPullRequest(7, OptionalLong.of(1), Instant.EPOCH);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(review));
        ReviewRun firstRun = run(1, base, base, "README.md").withTurn(first.id().toString(), Instant.EPOCH);
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(firstRun));

        assertThat(reader.read(fresh, first).guidelines()).containsExactly("AGENTS.md");
        AgentTurn sent = first.withReference(ImmutableList.of("AGENTS.md"), ImmutableList.of()).markDeliveryStarted(Instant.EPOCH);
        when(turns.list(taskId)).thenReturn(ImmutableList.of(sent));
        // Recording a send is not an acknowledgement; delivery may have failed or be uncertain.
        assertThat(reader.read(resumed, next).guidelines()).containsExactly("AGENTS.md");
        when(turns.list(taskId)).thenReturn(ImmutableList.of(sent.running(new ProviderTurnRef("first"), Optional.empty())));
        assertThat(reader.read(resumed, next)).isEqualTo(Reference.NONE);
        assertThat(reader.read(fresh, next).guidelines()).containsExactly("AGENTS.md");
        assertThat(reader.read(resumed, turn("user-followup"))).isEqualTo(Reference.NONE);

        ReviewRun changedScope = run(2, base, base, "plugin/delta/Writer.java");
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(firstRun, changedScope));
        assertThat(reader.read(resumed, next).guidelines()).containsExactly("AGENTS.md", "plugin/delta/AGENTS.md");
        write("AGENTS.md", "Updated base rules.\n");
        String updatedBase = commit();
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(firstRun, run(2, updatedBase, updatedBase, "README.md")));
        assertThat(reader.read(resumed, next).text()).contains("Updated base rules.");
    }

    @Test
    void developmentFollowupsUseTheSameReceiptRuleAndRetryMissingGuidelines()
            throws IOException
    {
        write("AGENTS.md", "Root rules.\n");
        AgentTask fresh = task(commit());
        AgentTask resumed = fresh.withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "conversation"), Instant.EPOCH);
        AgentTurn source = turn("user");
        AgentTurn generated = turn("development:commit").withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()));
        when(turns.list(taskId)).thenReturn(ImmutableList.of(source.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("source"), Optional.empty())));
        assertThat(reader.read(resumed, generated).guidelines()).containsExactly("AGENTS.md");

        AgentTurn delivered = source.withReference(ImmutableList.of("AGENTS.md"), ImmutableList.of()).markDeliveryStarted(Instant.EPOCH)
                .running(new ProviderTurnRef("source"), Optional.empty());
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered));
        assertThat(reader.read(resumed, generated)).isEqualTo(Reference.NONE);
        assertThat(reader.read(fresh, generated).guidelines()).containsExactly("AGENTS.md");
    }

    @Test
    void userTurnsReuseAcknowledgedGuidelinesUntilTheConversationIsCompacted()
            throws IOException
    {
        write("AGENTS.md", "Root rules.\n");
        AgentTask resumed = task(commit()).withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "conversation"), Instant.EPOCH);
        AgentTurn delivered = turn("user").withReference(ImmutableList.of("AGENTS.md"), ImmutableList.of()).markDeliveryStarted(Instant.EPOCH)
                .running(new ProviderTurnRef("first"), Optional.empty());
        AgentTurn later = turn("later").markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("later"), Optional.empty());
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered, later));
        assertThat(reader.read(resumed, turn("next"))).isEqualTo(Reference.NONE);

        // A compaction may have summarized the guidelines away, whether in the turn that carried them or after it.
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered, later.withMetrics(compacted())));
        assertThat(reader.read(resumed, turn("next")).guidelines()).containsExactly("AGENTS.md");
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered.withMetrics(compacted()), later));
        assertThat(reader.read(resumed, turn("next")).guidelines()).containsExactly("AGENTS.md");
    }

    private static AgentMetrics compacted()
    {
        return new AgentMetrics(0, 0, 0, Optional.empty(), Duration.ZERO, new ToolCounters(1, 0, 1));
    }

    private AgentTurn turn(String key)
    {
        return AgentTurn.accepted(TurnId.random(), taskId, key,
                new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10),
                        ImmutableList.of(), Optional.empty(), Optional.empty()), Instant.EPOCH);
    }

    private AgentTask task(String startCommit)
    {
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("example", "project"), repository, "bytequay/task/" + taskId, startCommit);
        return AgentTask.created(taskId, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    }

    private static ReviewRun run(int ordinal, String base, String head, String file)
    {
        return new ReviewRun("run-" + ordinal, "review", ordinal, ReviewRun.Scope.FULL, "main", base, base, head, Optional.empty(), Optional.empty(), ImmutableList.of(),
                ImmutableList.of(diff(file)), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }

    private static DiffFile diff(String path)
    {
        return new DiffFile(path, "modified", 1, 0, "patch");
    }

    private void write(String path, String content)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String commit()
    {
        command("add", "-A");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        return command("rev-parse", "HEAD").strip();
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
