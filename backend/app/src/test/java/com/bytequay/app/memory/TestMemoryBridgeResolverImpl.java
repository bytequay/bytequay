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
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.MemoryBridge;
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
    private final MemoryService memories = new MemoryService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MemoryBridgeResolverImpl resolver = new MemoryBridgeResolverImpl(tasks, reviews, memories, mapper);

    @Test
    void offersTheToolToDevelopmentTasksAndReviewsWhoseWorkspaceHasMemories()
    {
        AgentTask development = task(true, "workspace");
        assertThat(resolver.resolve(development.id().toString())).isEmpty();

        store.save(rule("m1", "workspace", "Keep refactors and behavior changes in separate commits.", ImmutableList.of()));
        assertThat(resolver.resolve(development.id().toString())).isPresent();

        AgentTask specCheck = task(false, "workspace");
        assertThat(resolver.resolve(specCheck.id().toString())).isEmpty();

        AgentTask review = task(false, "workspace");
        when(reviews.findReviewByTask(review.id().toString())).thenReturn(Optional.of(new AgentReview("review", "workspace", "trinodb/trino", OptionalInt.of(1),
                OptionalLong.empty(), review.id().toString(), Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0,
                NOW, NOW, Optional.empty())));
        assertThat(resolver.resolve(review.id().toString())).isPresent();

        AgentTask elsewhere = task(true, "other");
        assertThat(resolver.resolve(elsewhere.id().toString())).isEmpty();

        when(tasks.find(development.id())).thenReturn(Optional.of(development.fail("Failed", NOW, NOW.plus(Duration.ofDays(7)))));
        assertThat(resolver.resolve(development.id().toString())).isEmpty();
        assertThat(resolver.resolve(TaskId.random().toString())).isEmpty();
    }

    @Test
    void searchesByPathAndTextReadsSourcesAndReportsWithoutEditing()
            throws Exception
    {
        store.save(rule("m1", "workspace", "Keep refactors and behavior changes in separate commits.", ImmutableList.of()));
        store.save(rule("m2", "workspace", "Document every new table property on the connector's page.", ImmutableList.of("plugin/"), 31201, 30877));
        store.save(rule("m3", "workspace", "Leave cleanup of unfinished output to abort().", ImmutableList.of("plugin/trino-delta-lake/")));
        store.save(rule("m4", "workspace", "Iceberg rule", ImmutableList.of("plugin/trino-iceberg/")));
        store.save(rule("m5", "other", "Another workspace", ImmutableList.of()));
        AgentTask task = task(true, "workspace");
        MemoryBridge bridge = resolver.resolve(task.id().toString()).orElseThrow();

        JsonNode byPath = call(bridge, "{\"action\":\"SEARCH\",\"path\":\"plugin/trino-delta-lake/src/main/java/DeltaLakeWriter.java\"}");
        assertThat(byPath.path("memories").findValuesAsText("id")).containsExactly("m3", "m2", "m1");
        assertThat(byPath.path("memories").get(1).path("source").asText()).isEqualTo("PR #31201 @alice · 2 PRs");
        assertThat(byPath.path("memories").get(2).path("scope").asText()).isEqualTo("repository");
        assertThat(byPath.path("activityLine").asText()).isEqualTo("Searched memory in plugin/trino-delta-lake/src/main/java/DeltaLakeWriter.java · 3 memories");

        JsonNode byDirectory = call(bridge, "{\"action\":\"SEARCH\",\"path\":\"plugin\",\"query\":\"RULE\"}");
        assertThat(byDirectory.path("memories").findValuesAsText("id")).containsExactly("m4");

        JsonNode read = call(bridge, "{\"action\":\"READ\",\"id\":\"m2\"}");
        assertThat(read.path("sources").findValuesAsText("quote")).containsExactly("Please document the new property.", "Please document the new property.");
        assertThat(read.path("sources").get(1).path("pullRequest").asInt()).isEqualTo(30877);
        assertThat(read.path("activityLine").asText()).isEqualTo("Read memory m2");

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
                .hasMessage("action must be SEARCH, READ or REPORT");
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"REPORT\",\"id\":\"m1\"}")).hasMessage("REPORT needs a reason");
    }

    @Test
    void pagesSearchResults()
            throws Exception
    {
        for (int number = 1; number <= 25; number++) {
            store.save(rule("m" + number, "workspace", "Rule " + number, ImmutableList.of()));
        }
        MemoryBridge bridge = resolver.resolve(task(true, "workspace").id().toString()).orElseThrow();
        JsonNode second = call(bridge, "{\"action\":\"SEARCH\",\"page\":2}");
        assertThat(second.path("memories")).hasSize(5);
        assertThat(second.path("pages").asInt()).isEqualTo(2);
        assertThat(second.path("total").asInt()).isEqualTo(25);
    }

    private JsonNode call(MemoryBridge bridge, String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.call().apply(arguments));
    }

    private AgentTask task(boolean interactive, String workspaceId)
    {
        AgentTask task = AgentTask.created(TaskId.random(), Optional.of(workspaceId), interactive, "Task", new AgentLaneId("codex"),
                new RepositoryIdentity("trinodb", "trino"), Path.of("/tmp/memory-worktree"),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty(), Optional.empty()), NOW);
        when(tasks.find(task.id())).thenReturn(Optional.of(task));
        return task;
    }
}
