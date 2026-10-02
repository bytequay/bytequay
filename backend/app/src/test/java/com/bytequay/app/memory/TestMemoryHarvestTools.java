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
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.context.ContextBuild;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.ContextBuildStore;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemorySource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.memory.MemoryFixtures.rule;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestMemoryHarvestTools
{
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ContextBuildStore store = new ContextBuildStore(new InMemoryAppSettingsStore(), tasks, mapper);
    private final ContextBuildService service = mock(ContextBuildService.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MemoryService memories = new MemoryService(memoryStore, new InMemoryAppSettingsStore(), mapper, clock);
    private final MemoryHarvestTools tools = new MemoryHarvestTools(store, service, memories, tasks, unitOfWork, mapper, clock);

    @BeforeEach
    void setUp()
    {
        AgentTask task = AgentTask.created(TASK, Optional.of(WORKSPACE), false, "Learn", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/learn"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH)
                .claimWorktreeMutation(WorktreeMutationClaim.turn(TURN, Instant.EPOCH), Instant.EPOCH);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenAnswer(call -> current());
        when(unitOfWork.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
        memoryStore.save(rule("m11", WORKSPACE, "Document every new table property.", ImmutableList.of("plugin/")));
        memoryStore.save(new Memory("m12", WORKSPACE, Memory.Kind.RULE, "Keep refactors in their own commits.", ImmutableList.of(),
                ImmutableList.of(MemoryFixtures.pullRequestSource(30000, "bo")), Memory.Origin.USER_EDIT, 2, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW));
    }

    @Test
    void readsTheThreadsByPageWithTheSavedRulesFirst()
            throws Exception
    {
        store.save(Optional.empty(), build(30));
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        assertThat(bridge.tools()).extracting(ReviewTool::name).containsExactly("repository_context_harvest_threads", "repository_context_harvest_save");

        JsonNode first = call(bridge, "repository_context_harvest_threads", "{}");
        assertThat(first.path("pages").intValue()).isEqualTo(2);
        assertThat(first.path("memories").findValuesAsText("id")).containsExactlyInAnyOrder("m11", "m12");
        assertThat(first.path("memories").findParents("id").stream().filter(memory -> memory.path("yours").booleanValue()).map(memory -> memory.path("id").asText()))
                .containsExactly("m12");
        assertThat(first.path("threads")).hasSize(MemoryHarvestTools.PAGE_SIZE);
        assertThat(first.path("threads").get(0).path("applied").booleanValue()).isTrue();
        assertThat(first.path("threads").get(0).path("notes").get(0).path("key").asText()).isEqualTo("c1");

        JsonNode second = call(bridge, "repository_context_harvest_threads", "{\"page\":2}");
        assertThat(second.path("threads")).hasSize(5);
        assertThat(second.has("memories")).isFalse();
        assertThat(call(bridge, "repository_context_harvest_threads", "{\"page\":3}").path("error").path("message").asText()).isEqualTo("page: there are 2 pages");
        assertThat(current().capture().activity()).extracting(ContextBuild.Activity::text).containsExactly("Read threads, page 1 of 2", "Read threads, page 2 of 2");
    }

    @Test
    void savesAddsSourcesAndReplacesCitingOnlyThisRunsNotes()
            throws Exception
    {
        store.save(Optional.empty(), build(3));
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();

        JsonNode saved = call(bridge, "repository_context_harvest_save",
                "{\"action\":\"SAVE\",\"text\":\"When a connector adds a session property,\\ngive it a catalog property too.\",\"scope\":[\"plugin/\"],\"sources\":[\"c1\"]}");
        String id = saved.path("id").asText();
        Memory memory = memories.get(WORKSPACE, id);
        assertThat(memory.text()).isEqualTo("When a connector adds a session property, give it a catalog property too.");
        assertThat(memory.origin()).isEqualTo(new Memory.Origin(false, "Learn from my PRs", Optional.of(TASK.toString())));
        MemorySource source = memory.sources().getFirst();
        assertThat(source.url()).contains("https://github.com/trinodb/trino/pull/31201#discussion_r1");
        assertThat(source.quote()).isEqualTo("Please document property 1.");
        assertThat(source.pullRequest()).contains(new MemorySource.PullRequestPart(31201, MemorySource.Part.REVIEW_COMMENT, "node-1", "alice", true, true, true, false));

        call(bridge, "repository_context_harvest_save", "{\"action\":\"ADD_SOURCE\",\"id\":\"m11\",\"sources\":[\"c2\"]}");
        assertThat(memories.get(WORKSPACE, "m11").sources()).hasSize(2);
        call(bridge, "repository_context_harvest_save", "{\"action\":\"REPLACE\",\"id\":\"m11\",\"text\":\"Document table properties on the connector page.\","
                + "\"scope\":[\"plugin/trino-delta-lake/\"],\"sources\":[\"c3\"]}");
        Memory replaced = memories.get(WORKSPACE, "m11");
        assertThat(replaced.text()).isEqualTo("Document table properties on the connector page.");
        assertThat(replaced.previous().orElseThrow().text()).isEqualTo("Document every new table property.");
        assertThat(replaced.sources()).hasSize(3);

        assertThat(call(bridge, "repository_context_harvest_save", "{\"action\":\"ADD_SOURCE\",\"id\":\"m12\",\"sources\":[\"c1\"]}").path("error").path("message").asText())
                .contains("Memory m12 is the user's; agents never change it");
        assertThat(call(bridge, "repository_context_harvest_save", "{\"action\":\"SAVE\",\"text\":\"x\",\"sources\":[\"c99\"]}").path("error").path("message").asText())
                .isEqualTo("sources: c99 is not a note of this harvest");
        assertThat(current().capture().attempts().keySet()).anyMatch(key -> key.equals(MemoryHarvestTools.SAVED + id))
                .anyMatch(key -> key.startsWith(MemoryHarvestTools.SEEN_AGAIN + "m11:")).anyMatch(key -> key.equals(MemoryHarvestTools.REPLACED + "m11"));
    }

    @Test
    void onlyAHarvestRunGetsTheTools()
    {
        ContextBuild harvest = build(1);
        store.save(Optional.empty(), new ContextBuild(harvest.sequence(), harvest.requestKey(), harvest.requestDigest(), WORKSPACE, TASK.toString(),
                harvest.physicalRepository(), 1, harvest.repository(), harvest.baselineSha(), harvest.branch(), harvest.laneId(), harvest.defaults(), Instant.EPOCH,
                ContextBuild.Capture.empty(), Optional.of(new ContextBuild.Check("check-1", "plugin/x/", Optional.empty(), "brief")), Optional.empty()));
        assertThat(tools.bridge(TASK.toString())).isEmpty();
    }

    private JsonNode call(ReviewBridge bridge, String name, String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.tool(name).orElseThrow().call().apply(arguments));
    }

    private ContextBuild current()
    {
        return store.find(WORKSPACE).orElseThrow();
    }

    private static ContextBuild build(int threads)
    {
        ContextBuild.Harvest harvest = new ContextBuild.Harvest("harvest-1", "Learn from my PRs", true,
                ImmutableList.of(new ContextBuild.HarvestPull(31201, "Add a property", "https://github.com/trinodb/trino/pull/31201", true, Instant.EPOCH)),
                IntStream.rangeClosed(1, threads).mapToObj(number -> new ContextBuild.HarvestThread("thread-" + number, 31201, MemorySource.Part.REVIEW_COMMENT,
                        Optional.of("plugin/x/A.java"), number == 1, false, ImmutableList.of(new ContextBuild.HarvestNote("c" + number, "node-" + number, "alice", true,
                        "Please document property " + number + ".", "https://github.com/trinodb/trino/pull/31201#discussion_r" + number, Instant.EPOCH))))
                        .collect(toImmutableList()));
        return new ContextBuild(1, "harvest-request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(), new RepositoryIdentity("trinodb", "trino"), 1,
                RepoRef.of("trinodb", "trino"), "a".repeat(40), "bytequay/learn", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH,
                ContextBuild.Capture.empty(), Optional.empty(), Optional.of(harvest));
    }
}
