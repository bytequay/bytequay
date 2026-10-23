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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.MemoryBridge;
import com.bytequay.app.review.ReviewToolAssignment;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.memory.MemoryFixtures.rule;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestMemoryBridgeResolverImpl
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final MemoryService memories = new MemoryService(store, new InMemoryAppSettingsStore(), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    private final WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
    private final MemorySources sources = mock(MemorySources.class);
    private final ReviewToolAssignment assignments = mock(ReviewToolAssignment.class);
    private final MemoryBridgeResolverImpl resolver = new MemoryBridgeResolverImpl(tasks, reviews, memories, sources, mapper, relationships, assignments);

    @Test
    void offersTheToolToDevelopmentTasksAndReviewsWithMemoryOnEvenBeforeAnythingIsRemembered()
    {
        AgentTask development = task(true, "workspace");
        assertThat(resolver.resolve(development.id().toString())).isPresent();

        store.save(rule("m1", "workspace", "Keep refactors and behavior changes in separate commits.", ImmutableList.of()));
        assertThat(resolver.resolve(development.id().toString())).isPresent();

        AgentTask specCheck = task(false, "workspace");
        assertThat(resolver.resolve(specCheck.id().toString())).isEmpty();

        AgentTask review = task(false, "workspace");
        when(reviews.findReviewByTask(review.id().toString()))
                .thenReturn(Optional.of(pullRequestReview("review", "workspace", "owner/repository", 1, review.id().toString(), NOW)));
        assertThat(resolver.resolve(review.id().toString())).isPresent();

        AgentTask elsewhere = task(true, "other");
        memories.saveSwitches("other", new MemorySwitches(true, false));
        assertThat(resolver.resolve(elsewhere.id().toString())).isEmpty();

        when(tasks.find(development.id())).thenReturn(Optional.of(development.fail("Failed", NOW, NOW.plus(Duration.ofDays(7)))));
        assertThat(resolver.resolve(development.id().toString())).isEmpty();
        assertThat(resolver.resolve(TaskId.random().toString())).isEmpty();
    }

    @Test
    void theWorkspaceSwitchesWithdrawTheTool()
    {
        store.save(rule("m1", "workspace", "Keep refactors and behavior changes in separate commits.", ImmutableList.of()));
        AgentTask development = task(true, "workspace");
        AgentTask review = task(false, "workspace");
        when(reviews.findReviewByTask(review.id().toString()))
                .thenReturn(Optional.of(pullRequestReview("review", "workspace", "owner/repository", 1, review.id().toString(), NOW)));

        memories.saveSwitches("workspace", new MemorySwitches(true, false));
        assertThat(resolver.resolve(development.id().toString())).isEmpty();
        assertThat(resolver.resolve(review.id().toString())).isPresent();

        memories.saveSwitches("workspace", new MemorySwitches(false, true));
        assertThat(resolver.resolve(development.id().toString())).isPresent();
        assertThat(resolver.resolve(review.id().toString())).isEmpty();
    }

    @Test
    void searchesByPathAndTextReadsSourcesAndReportsWithoutEditing()
            throws Exception
    {
        store.save(rule("m1", "workspace", "Keep refactors and behavior changes in separate commits.", ImmutableList.of()));
        store.save(rule("m2", "workspace", "Adding connector table properties", "Document every new table property on the connector's page.",
                ImmutableList.of("plugin/"), 31201, 30877));
        store.save(rule("m3", "workspace", "Leave cleanup of unfinished output to abort().", ImmutableList.of("plugin/storage/")));
        store.save(rule("m4", "workspace", "Iceberg rule", ImmutableList.of("plugin/catalog/")));
        store.save(rule("m5", "other", "Another workspace", ImmutableList.of()));
        AgentTask task = task(true, "workspace");
        MemoryBridge bridge = resolver.resolve(task.id().toString()).orElseThrow();

        JsonNode byPath = call(bridge, "{\"action\":\"SEARCH\",\"path\":\"plugin/storage/src/main/java/DeltaLakeWriter.java\"}");
        assertThat(byPath.path("memories").findValuesAsText("id")).containsExactly("m3", "m2", "m1");
        assertThat(byPath.path("memories").get(1).path("source").asText()).isEqualTo("PR #31201 @alice · 2 PRs");
        assertThat(byPath.path("memories").get(1).path("title").asText()).isEqualTo("Adding connector table properties");
        assertThat(byPath.path("memories").findValues("text")).isEmpty();
        assertThat(byPath.path("memories").findValues("sources")).isEmpty();
        assertThat(byPath.path("memories").get(2).path("scope").asText()).isEqualTo("repository");
        assertThat(byPath.path("activityLine").asText()).isEqualTo("Searched memory in plugin/storage/src/main/java/DeltaLakeWriter.java · 3 memories");

        JsonNode byDirectory = call(bridge, "{\"action\":\"SEARCH\",\"path\":\"plugin\",\"query\":\"RULE\"}");
        assertThat(byDirectory.path("memories").findValuesAsText("id")).containsExactly("m4");

        JsonNode read = call(bridge, "{\"action\":\"READ\",\"id\":\"m2\"}");
        assertThat(read.path("sources").findValuesAsText("quote")).containsExactly("Please document the new property.", "Please document the new property.");
        assertThat(read.path("sources").get(1).path("pullRequest").asInt()).isEqualTo(30877);
        assertThat(read.path("title").asText()).isEqualTo("Adding connector table properties");
        assertThat(read.path("text").asText()).isEqualTo("Document every new table property on the connector's page.");
        assertThat(read.path("activityLine").asText()).isEqualTo("Read memory m2");
        assertThat(call(bridge, "{\"action\":\"SEARCH\",\"query\":\"document\"}").path("memories").findValuesAsText("id")).containsExactly("m2");

        JsonNode reported = call(bridge, "{\"action\":\"REPORT\",\"id\":\"m3\",\"reason\":\"The writer now encodes partition values\",\"quote\":\"path(partition)\"}");
        assertThat(reported.path("activityLine").asText()).isEqualTo("Reported memory m3 as wrong");
        assertThat(store.find("m3").orElseThrow().reports()).singleElement()
                .satisfies(report -> assertThat(report.taskId()).isEqualTo(task.id().toString()));
        assertThat(store.find("m3").orElseThrow().text()).isEqualTo("Leave cleanup of unfinished output to abort().");
        assertThat(call(bridge, "{\"action\":\"SEARCH\",\"query\":\"abort\"}").path("memories").get(0).path("flag").asText())
                .isEqualTo("reported wrong: The writer now encodes partition values");

        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"READ\",\"id\":\"m5\"}")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Memory m5 is not in this workspace");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"EDIT\",\"id\":\"m1\"}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("action must be SEARCH, READ, REPORT or REMEMBER");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REPORT\",\"id\":\"m1\"}")).hasMessage("REPORT needs a reason");
    }

    @Test
    void aReplaySearchesAndReadsOnlyWhatItCouldHaveKnownAndReportsNothing()
            throws Exception
    {
        store.save(rule("m1", "workspace", "Rule from an earlier pull request.", ImmutableList.of(), 30000));
        store.save(rule("m2", "workspace", "Rule from the replayed pull request.", ImmutableList.of(), 31188));
        AgentTask replay = task(false, "workspace");
        when(reviews.findReviewByTask(replay.id().toString())).thenReturn(Optional.of(replay(replay, false)));
        assertThat(resolver.resolve(replay.id().toString())).isEmpty();

        when(reviews.findReviewByTask(replay.id().toString())).thenReturn(Optional.of(replay(replay, true)));
        memories.saveSwitches("workspace", new MemorySwitches(false, false));
        MemoryBridge bridge = resolver.resolve(replay.id().toString()).orElseThrow();
        assertThat(call(bridge, "{\"action\":\"SEARCH\",\"query\":\"rule\"}").path("memories").findValuesAsText("id")).containsExactly("m1");
        assertThat(call(bridge, "{\"action\":\"READ\",\"id\":\"m1\"}").path("id").asText()).isEqualTo("m1");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"READ\",\"id\":\"m2\"}")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Memory m2 is not in this workspace");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REPORT\",\"id\":\"m1\",\"reason\":\"Old\"}"))
                .hasMessage("A replay reviews past code and cannot report memories");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"kind\":\"RULE\",\"text\":\"Rule\"}"))
                .hasMessage("A replay reviews past code and cannot remember anything");
        assertThat(store.find("m1").orElseThrow().reports()).isEmpty();
    }

    @Test
    void remembersRulesOnTheUsersWordsAndRecipesOnPassingCommandsAndUndoesThem()
            throws Exception
    {
        AgentTask task = task(true, "workspace");
        TurnId turn = TurnId.random();
        when(assignments.capture(task.id().toString())).thenReturn(Optional.of(turn));
        MemorySource words = MemorySource.user("Keep refactors in their own commits.", NOW,
                new MemorySource.UserPart(MemorySource.Where.TASK_MESSAGE, task.id().toString(), "event-1"));
        when(sources.userWords(task, turn, Optional.of("Keep refactors in their own commits."))).thenReturn(words);
        MemorySource command = MemorySource.command("./mvnw -pl core test", NOW,
                new MemorySource.CommandPart(task.id().toString(), turn.toString(), "./mvnw -pl core test", ".", "a".repeat(40)));
        when(sources.passingCommand(task, turn, "./mvnw -pl core test")).thenReturn(command);
        store.save(new Memory("m9", "workspace", Memory.Kind.RULE, "Own rule", "The user's own rule.", ImmutableList.of(), ImmutableList.of(words), Memory.Origin.USER_EDIT,
                1, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW));
        MemoryBridge bridge = resolver.resolve(task.id().toString()).orElseThrow();

        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"kind\":\"RULE\",\"text\":\"Keep refactors in their own commits.\","
                + "\"quote\":\"Keep refactors in their own commits.\"}")).hasMessage("REMEMBER needs a title: the situation it covers, in at most 80 characters");
        JsonNode rule = call(bridge, "{\"action\":\"REMEMBER\",\"kind\":\"RULE\",\"title\":\"Refactor commits\",\"text\":\"Keep refactors\\nin their own commits.\","
                + "\"quote\":\"Keep refactors in their own commits.\"}");
        String ruleId = rule.path("id").asText();
        assertThat(rule.path("activityLine").asText()).isEqualTo("Remembered " + ruleId + " · Refactor commits");
        Memory saved = store.find(ruleId).orElseThrow();
        assertThat(saved.sources()).containsExactly(words);
        assertThat(saved.origin()).isEqualTo(new Memory.Origin(false, "Remembered in a task", Optional.of(task.id().toString())));

        JsonNode recipe = call(bridge, "{\"action\":\"REMEMBER\",\"kind\":\"RECIPE\",\"title\":\"Core tests\",\"text\":\"Run the core tests with `./mvnw -pl core test`.\","
                + "\"command\":\"./mvnw -pl core test\",\"scope\":[\"core/\"]}");
        Memory remembered = store.find(recipe.path("id").asText()).orElseThrow();
        assertThat(remembered.kind()).isEqualTo(Memory.Kind.RECIPE);
        assertThat(remembered.scope()).containsExactly("core/");
        assertThat(remembered.sources()).containsExactly(command);

        JsonNode updated = call(bridge, "{\"action\":\"REMEMBER\",\"id\":\"" + ruleId + "\",\"kind\":\"RULE\",\"title\":\"Refactors and behavior changes\","
                + "\"text\":\"Keep refactors and behavior changes in separate commits.\",\"quote\":\"Keep refactors in their own commits.\"}");
        assertThat(updated.path("activityLine").asText()).isEqualTo("Updated " + ruleId + " · Refactors and behavior changes");
        assertThat(store.find(ruleId).orElseThrow().previous()).map(Memory.Previous::title).contains("Refactor commits");
        assertThat(store.find(ruleId).orElseThrow().previous()).map(Memory.Previous::text).contains("Keep refactors in their own commits.");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"id\":\"" + ruleId + "\",\"kind\":\"RECIPE\",\"title\":\"x\",\"text\":\"x\","
                + "\"command\":\"./mvnw -pl core test\"}")).hasMessage("Memory " + ruleId + " is a RULE; remember a RECIPE as a new memory");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"id\":\"m9\",\"kind\":\"RULE\",\"title\":\"x\",\"text\":\"x\","
                + "\"quote\":\"Keep refactors in their own commits.\"}"))
                .hasMessageContaining("Memory m9 is the user's; agents never change it");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"kind\":\"RECIPE\",\"title\":\"x\",\"text\":\"x\"}"))
                .hasMessage("A RECIPE needs the command that passed");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REMEMBER\",\"kind\":\"SPEC\",\"text\":\"x\"}")).hasMessage("kind must be RULE or RECIPE");

        memories.undoRemember("workspace", ruleId);
        assertThat(store.find(ruleId).orElseThrow().text()).isEqualTo("Keep refactors in their own commits.");
        assertThat(store.find(ruleId).orElseThrow().origin().user()).isTrue();
        assertThatThrownBy(() -> memories.undoRemember("workspace", ruleId)).hasMessageContaining("is yours now");
        memories.undoRemember("workspace", remembered.id());
        assertThat(store.find(remembered.id())).isEmpty();
        assertThat(memories.undoDelete("workspace", remembered.id()).id()).isEqualTo(remembered.id());
    }

    @Test
    void pagesSearchResults()
            throws Exception
    {
        for (int number = 1; number <= 25; number++) {
            store.save(rule("m" + number, "workspace", "Situation " + number, "Full rule " + number, ImmutableList.of("plugin/")));
        }
        MemoryBridge bridge = resolver.resolve(task(true, "workspace").id().toString()).orElseThrow();
        JsonNode second = call(bridge, "{\"action\":\"SEARCH\",\"path\":\"plugin/\",\"page\":2}");
        assertThat(second.path("memories")).hasSize(5);
        assertThat(second.path("memories").findValuesAsText("title")).allMatch(title -> title.startsWith("Situation "));
        assertThat(second.path("memories").findValues("text")).isEmpty();
        assertThat(second.path("pages").asInt()).isEqualTo(2);
        assertThat(second.path("total").asInt()).isEqualTo(25);
    }

    @Test
    void readsAllRelatedMemoryWithoutOwnMemoriesAndRechecksEachCall()
            throws Exception
    {
        Workspace destination = new Workspace("iceberg", "Iceberg", 2, RepoRef.of("apache", "iceberg"), Optional.empty(), Optional.empty(),
                2, RepoRef.of("apache", "iceberg"), Workspace.Topology.DIRECT, "main", "https://github.com/apache/iceberg.git",
                Optional.empty(), Optional.empty(), "/managed/iceberg", Optional.empty(), Optional.empty(), 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(relationships.requireRelated("workspace", "iceberg")).thenReturn(destination);
        for (int number = 1; number <= 25; number++) {
            store.save(rule("m" + number, "iceberg", "Rule " + number, ImmutableList.of()));
        }
        AgentTask task = task(true, "workspace");
        MemoryBridge bridge = resolver.resolve(task.id().toString()).orElseThrow();
        JsonNode page = call(bridge, "{\"action\":\"SEARCH\",\"workspaceId\":\"iceberg\",\"page\":2}");
        assertThat(page.path("memories")).hasSize(5);
        assertThat(page.path("workspaceId").asText()).isEqualTo("iceberg");
        assertThat(page.path("repository").asText()).isEqualTo("apache/iceberg");
        assertThat(page.path("memories").get(0).path("version").asInt()).isEqualTo(1);
        JsonNode memory = call(bridge, "{\"action\":\"READ\",\"workspaceId\":\"iceberg\",\"id\":\"m1\"}");
        assertThat(memory.path("sources").isArray()).isTrue();
        assertThat(memory.path("version").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> call(bridge, "{\"action\":\"REPORT\",\"workspaceId\":\"iceberg\",\"id\":\"m1\",\"reason\":\"bad\"}"))
                .hasMessageContaining("Only SEARCH and READ");
        assertThatThrownBy(() -> call(bridge, "{\"action\":\"REMEMBER\",\"workspaceId\":\"iceberg\",\"kind\":\"RULE\",\"text\":\"Keep private\"}"))
                .hasMessageContaining("Only SEARCH and READ");
        assertThat(memories.list("workspace")).isEmpty();
        memories.saveSwitches("iceberg", new MemorySwitches(true, false));
        assertThatThrownBy(() -> call(bridge, "{\"action\":\"SEARCH\",\"workspaceId\":\"iceberg\"}"))
                .hasMessageContaining("Related workspace memory is disabled");
        assertThat(resolver.resolve(task.id().toString())).isPresent(); // Local REMEMBER remains available.
        memories.saveSwitches("iceberg", new MemorySwitches(true, true));
        when(relationships.requireRelated("workspace", "iceberg")).thenThrow(new IllegalArgumentException("Workspace is not related"));
        assertThatThrownBy(() -> call(bridge, "{\"action\":\"SEARCH\",\"workspaceId\":\"iceberg\"}"))
                .hasMessageContaining("not related");
        when(tasks.find(task.id())).thenReturn(Optional.of(task.fail("Failed", NOW, NOW.plus(Duration.ofDays(7)))));
        assertThatThrownBy(() -> call(bridge, "{\"action\":\"SEARCH\"}")).hasMessageContaining("Task is unavailable");
    }

    private static AgentReview replay(AgentTask task, boolean memory)
    {
        return new AgentReview("replay-review", "workspace", "owner/repository", OptionalInt.empty(), OptionalLong.empty(), task.id().toString(), Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty(),
                Optional.of(new AgentReview.Replay("replay", 31188, memory, NOW.plusSeconds(3600))));
    }

    private JsonNode call(MemoryBridge bridge, String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.call().apply(arguments));
    }

    private AgentTask task(boolean interactive, String workspaceId)
    {
        AgentTask task = AgentTask.created(TaskId.random(), Optional.of(workspaceId), interactive, "Task", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repository"), Path.of("/tmp/memory-worktree"),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty(), Optional.empty()), NOW);
        when(tasks.find(task.id())).thenReturn(Optional.of(task));
        return task;
    }
}
