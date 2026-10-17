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
package com.bytequay.app.memory;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ReviewTurnInputResolver.Reference;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMemory;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.memory.MemoryFixtures.rule;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestMemoryDelivery
{
    private final TaskId taskId = TaskId.random();
    private final GitCommandRunner git = new GitCommandRunner();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final MemoryService memories = new MemoryService(store, new InMemoryAppSettingsStore(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final MemoryDelivery delivery = new MemoryDelivery(memories, store, reviews, git, turns);

    @TempDir
    private Path root;
    private Path repository;

    @BeforeEach
    void repository()
            throws IOException
    {
        // A task's worktree directory is named after the task.
        repository = Files.createDirectories(root.resolve(taskId.toString()));
    }

    @Test
    void aDevelopmentTaskGetsRepositoryWideMemoryFirstAndScopedMemoryOnceItChangesFiles()
            throws IOException
    {
        store.save(rule("m1", "workspace", "Keep refactors in their own commits.", ImmutableList.of()));
        store.save(rule("m2", "workspace", "Recurring rule seen in three PRs.", ImmutableList.of(), 1, 2, 3));
        store.save(rule("m3", "workspace", "Delta writes leave cleanup to abort().", ImmutableList.of("plugin/trino-delta-lake/")));
        store.save(rule("m4", "workspace", "Connector docs list every property.", ImmutableList.of("plugin/")));
        store.save(rule("m5", "workspace", "Iceberg rule", ImmutableList.of("plugin/trino-iceberg/")));
        String start = initialCommit();
        AgentTask task = task(start);

        Reference first = delivery.reference(task, turn(true));
        assertThat(first.memories()).extracting(TurnMemory::id).containsExactly("m2", "m1");
        assertThat(first.text()).contains("----- BEGIN REPOSITORY MEMORY -----\n[m2] Recurring rule seen in three PRs. (PR #1 @alice · 3 PRs)\n[m1] ")
                .contains("READ that memory with repository_memory").contains("cite its id, such as [m2]").endsWith("----- END REPOSITORY MEMORY -----");
        assertThat(first.guidelines()).isEmpty();
        assertThat(first.text()).doesNotContain("Showing ");

        write("plugin/trino-delta-lake/src/DeltaLakeWriter.java", "class DeltaLakeWriter { void finish() {} }\n");
        Reference later = delivery.reference(task, turn(true));
        assertThat(later.memories()).extracting(TurnMemory::id).containsExactly("m3", "m4", "m2", "m1");

        assertThat(delivery.reference(task, turn(false))).isEqualTo(Reference.NONE);
        memories.saveSwitches("workspace", new MemorySwitches(true, false));
        assertThat(delivery.reference(task, turn(true))).isEqualTo(Reference.NONE);
    }

    @Test
    void aReviewGetsTheMemoryScopedToItsChangedPathsAndFlaggedMemoryWithItsFlag()
    {
        store.save(rule("m1", "workspace", "Repository rule.", ImmutableList.of()));
        store.save(rule("m3", "workspace", "Delta Lake writer cleanup", "Delta writes leave cleanup to abort().", ImmutableList.of("plugin/trino-delta-lake/"))
                .reported(new MemoryReport("The writer cleans up in finish() now", "", "task", NOW), NOW));
        store.save(rule("m5", "workspace", "Iceberg rule", ImmutableList.of("plugin/trino-iceberg/")));
        AgentTask review = AgentTask.created(taskId, Optional.of("workspace"), false, "Review", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                repository, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(pullRequestReview("review", "workspace", "trinodb/trino", 1, taskId.toString(), NOW)));
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run("plugin/trino-delta-lake/src/DeltaLakeWriter.java")));

        Reference reference = delivery.reference(review, turn(true));
        assertThat(reference.memories()).extracting(TurnMemory::id).containsExactly("m3", "m1");
        assertThat(reference.memories().getFirst().line())
                .isEqualTo("Delta Lake writer cleanup (PR #31201 @alice) Flagged: reported wrong: The writer cleans up in finish() now.");
        assertThat(reference.text()).contains("[m3] Delta Lake writer cleanup (PR #31201 @alice)").doesNotContain("leave cleanup to abort()");

        memories.saveSwitches("workspace", new MemorySwitches(false, true));
        assertThat(delivery.reference(review, turn(true))).isEqualTo(Reference.NONE);
    }

    @Test
    void aReplayGetsMemoryOnlyOnItsSideAndOnlyWhatItCouldHaveKnown()
    {
        store.save(rule("m1", "workspace", "Rule from an earlier pull request.", ImmutableList.of(), 30000));
        store.save(rule("m2", "workspace", "Rule from the replayed pull request.", ImmutableList.of(), 31188));
        AgentTask review = AgentTask.created(taskId, Optional.of("workspace"), false, "Replay", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                repository, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run("core/A.java")));

        replay(true, NOW.plusSeconds(3600));
        Reference reference = delivery.reference(review, turn(true));
        assertThat(reference.memories()).extracting(TurnMemory::id).containsExactly("m1");
        delivery.delivered(turn(true).markDeliveryStarted(NOW).withReference(ImmutableList.of(), reference.memories()));
        assertThat(store.find("m1").orElseThrow().delivered()).isZero();

        // The fixtures' sources are written at NOW, so a first review before then could have known neither rule.
        replay(true, NOW.minusSeconds(3600));
        assertThat(delivery.reference(review, turn(true))).isEqualTo(Reference.NONE);
        replay(false, NOW.plusSeconds(3600));
        assertThat(delivery.reference(review, turn(true))).isEqualTo(Reference.NONE);
        memories.saveSwitches("workspace", new MemorySwitches(false, false));
        replay(true, NOW.plusSeconds(3600));
        assertThat(delivery.reference(review, turn(true)).memories()).extracting(TurnMemory::id).containsExactly("m1");
    }

    @Test
    void leavesOutABlockTheConversationStillHoldsAsItsLatest()
            throws IOException
    {
        store.save(rule("m1", "workspace", "Keep refactors in their own commits.", ImmutableList.of()));
        AgentTask task = task(initialCommit()).withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "conversation"), NOW);
        Reference block = delivery.reference(task, turn(true));
        AgentTurn delivered = turn(true).withReference(ImmutableList.of(), block.memories()).markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("first"), Optional.empty());
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered));
        assertThat(delivery.reference(task, turn(true))).isEqualTo(Reference.NONE);

        // The block is sent again once it changes or a compaction may have summarized it away.
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered.withMetrics(new AgentMetrics(0, 0, 0, Optional.empty(), Duration.ZERO, new ToolCounters(0, 0, 1)))));
        assertThat(delivery.reference(task, turn(true))).isEqualTo(block);
        when(turns.list(taskId)).thenReturn(ImmutableList.of(delivered));
        store.save(rule("m2", "workspace", "Run the module's tests before committing.", ImmutableList.of()));
        assertThat(delivery.reference(task, turn(true)).memories()).extracting(TurnMemory::id).contains("m1", "m2");
    }

    @Test
    void deliversAtMostFortyLinesAndCountsWhatWasDelivered()
    {
        for (int number = 1; number <= 45; number++) {
            store.save(rule("m" + number, "workspace", "Rule " + number, ImmutableList.of()));
        }
        Reference reference = MemoryDelivery.reference(MemoryDelivery.select(memories.list("workspace"), ImmutableList.of()));
        assertThat(reference.memories()).hasSize(MemoryDelivery.MAX_LINES);
        assertThat(reference.text()).contains("Showing 40 of 45 matching memories", "SEARCH repository_memory", "page through the matching titles");

        // Titles are short, so only long flags fill the bytes before the lines run out.
        Reference large = MemoryDelivery.reference(memories.list("workspace").stream()
                .map(memory -> rule(memory.id(), "workspace", "x".repeat(590), ImmutableList.of()).reported(new MemoryReport("y".repeat(480), "", "task", NOW), NOW))
                .collect(toImmutableList()));
        assertThat(large.memories().size()).isLessThan(MemoryDelivery.MAX_LINES);
        assertThat(large.text()).contains("Showing " + large.memories().size() + " of 45 matching memories");
        assertThat(large.text().getBytes(UTF_8).length).isLessThan(MemoryDelivery.MAX_BYTES + 1024);

        AgentTurn turn = turn(true).markDeliveryStarted(NOW).withReference(ImmutableList.of(), reference.memories().subList(0, 2));
        delivery.delivered(turn);
        delivery.delivered(turn);
        assertThat(store.find(reference.memories().get(0).id()).orElseThrow().delivered()).isEqualTo(2);
        assertThat(store.find(reference.memories().get(2).id()).orElseThrow().delivered()).isZero();
    }

    @Test
    void aSecondReaderGetsTheTextOfMatchingRulesButNoRecipes()
    {
        store.save(rule("m1", "workspace", "Keep refactors in their own commits.", ImmutableList.of()));
        store.save(rule("m2", "workspace", "Iceberg rule", ImmutableList.of("plugin/trino-iceberg/")));
        store.save(rule("m3", "workspace", "Delta rule", ImmutableList.of("plugin/trino-delta-lake/")));
        Memory recipe = rule("m4", "workspace", "Run the module's tests with ./mvnw test -pl plugin/trino-iceberg.", ImmutableList.of("plugin/trino-iceberg/"));
        store.save(new Memory(recipe.id(), recipe.workspaceId(), Memory.Kind.RECIPE, recipe.title(), recipe.text(), recipe.scope(), recipe.sources(), recipe.origin(),
                recipe.version(), recipe.previous(), recipe.delivered(), recipe.cited(), recipe.reports(), recipe.createdAt(), recipe.updatedAt()));
        AgentReview review = pullRequestReview("review", "workspace", "trinodb/trino", 31244, taskId.toString(), NOW);

        List<Memory> rules = delivery.rules(review, run("plugin/trino-iceberg/src/main/java/IcebergMetadata.java"));

        assertThat(rules).extracting(Memory::id).containsExactly("m2", "m1");
        assertThat(rules.getFirst().text()).isEqualTo("Iceberg rule");
        assertThat(store.find("m2").orElseThrow().delivered()).isZero();

        memories.saveSwitches("workspace", new MemorySwitches(false, true));
        assertThat(delivery.rules(review, run("plugin/trino-iceberg/src/main/java/IcebergMetadata.java"))).isEmpty();
    }

    private void replay(boolean memory, Instant cutoff)
    {
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.of(new AgentReview("review", "workspace", "trinodb/trino", OptionalInt.empty(),
                OptionalLong.empty(), taskId.toString(), Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0,
                NOW, NOW, Optional.empty(), Optional.of(new AgentReview.Replay("replay", 31188, memory, cutoff)))));
    }

    private AgentTask task(String startCommit)
    {
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("trinodb", "trino"), repository, "bytequay/task/" + taskId, startCommit);
        return AgentTask.created(taskId, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private AgentTurn turn(boolean memoryOn)
    {
        return AgentTurn.accepted(TurnId.random(), taskId, "turn-" + memoryOn, new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofHours(1), ImmutableList.of()).withRepositoryContext(memoryOn), NOW);
    }

    private static ReviewRun run(String file)
    {
        return new ReviewRun("run-1", "review", 1, ReviewRun.Scope.FULL, "main", "a".repeat(40), "a".repeat(40), "b".repeat(40), Optional.empty(),
                Optional.empty(), ImmutableList.of(),
                ImmutableList.of(new DiffFile(file, "modified", 1, 0, "patch")), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(),
                Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private String initialCommit()
            throws IOException
    {
        git.run(repository, "init", "-q");
        write("README.md", "Project\n");
        git.run(repository, "add", "-A");
        assertThat(git.run(repository, ImmutableList.of("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Start")).successful()).isTrue();
        return git.run(repository, "rev-parse", "HEAD").output().strip();
    }

    private void write(String path, String content)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
