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
package com.bytequay.app.service.goal;

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
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestGoalResearchTools
{
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private static final String HEAD = "a".repeat(40);
    private static final String ICEBERG = "c".repeat(40);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ContextBuildStore store = new ContextBuildStore(new InMemoryAppSettingsStore(), tasks, mapper);
    private final ContextBuildService service = mock(ContextBuildService.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final List<GoalKnowledgeNote> saved = new ArrayList<>();
    private final GoalKnowledgeNoteStore notes = new GoalKnowledgeNoteStore()
    {
        @Override
        public List<GoalKnowledgeNote> list(String goalId)
        {
            return saved.stream().filter(note -> note.goalId().equals(goalId)).collect(toImmutableList());
        }

        @Override
        public void save(GoalKnowledgeNote note)
        {
            assertThat(inTransaction).as("a note and its capture are saved in one transaction").isTrue();
            saved.add(note);
        }
    };
    private final GoalResearchTools tools = new GoalResearchTools(store, service, notes, tasks, unitOfWork, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        AgentTask task = AgentTask.created(TASK, Optional.of(WORKSPACE), false, "Research", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/research"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH)
                .claimWorktreeMutation(WorktreeMutationClaim.turn(TURN, Instant.EPOCH), Instant.EPOCH);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenAnswer(call -> store.find(WORKSPACE).orElseThrow());
        when(unitOfWork.execute(any())).thenAnswer(call -> {
            inTransaction.set(true);
            try {
                return ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run();
            }
            finally {
                inTransaction.set(false);
            }
        });
    }

    @Test
    void savesNotesCitingPagesAndFilesAtTheCommitsTheRunReads()
            throws Exception
    {
        store.save(Optional.empty(), build(Optional.of(research())));
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        assertThat(bridge.tools()).extracting(ReviewTool::name).containsExactly("goal_knowledge_save");
        assertThat(bridge.instruction()).contains("must not commit");

        JsonNode note = call(bridge, """
                {"topic": "Manifest lists", "body": "A snapshot lists its manifests in one manifest list.",
                 "sources": ["https://iceberg.apache.org/spec/#manifest-lists", "apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java@%s",
                 "trinodb/trino:plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSplitSource.java@%s"]}""".formatted(ICEBERG, HEAD));
        assertThat(note.path("activityLine").asText()).isEqualTo("Saved note · Manifest lists");
        assertThat(saved).singleElement().satisfies(stored -> {
            assertThat(stored.goalId()).isEqualTo("goal-1");
            assertThat(stored.taskId()).isEqualTo(TASK.toString());
            assertThat(stored.sources()).extracting(GoalKnowledgeNote.Source::commit).containsExactly(Optional.empty(), Optional.of(ICEBERG), Optional.of(HEAD));
            assertThat(stored.sources().get(1).reference()).isEqualTo("apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java");
        });
        assertThat(store.find(WORKSPACE).orElseThrow().capture().attempts()).containsKey(GoalResearchTools.SAVED + saved.getFirst().id());

        assertThat(error(bridge, """
                {"topic": "Snapshots", "body": "b", "sources": ["trinodb/trino:core/Snapshot.java@%s"]}""".formatted("d".repeat(40))))
                .isEqualTo("sources: trinodb/trino:core/Snapshot.java@%s is not at a commit this run reads".formatted("d".repeat(40)));
        assertThat(error(bridge, """
                {"topic": "Snapshots", "body": "b", "sources": ["http://example.com/spec"]}"""))
                .contains("neither an https page nor owner/repo:path@commit");
        assertThat(error(bridge, """
                {"topic": "Snapshots", "body": "b", "sources": []}""")).contains("sources: cite at least one page or file");
        assertThat(error(bridge, """
                {"topic": "Snapshots", "body": "b", "sources": ["https://iceberg.apache.org/spec/"], "level": "STRONG"}""")).contains("Unrecognized field \"level\"");
        assertThat(saved).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{}",
            "{\"topic\":123,\"body\":\"b\",\"sources\":[\"https://example.com\"]}",
            "{\"topic\":\"t\",\"body\":true,\"sources\":[\"https://example.com\"]}",
            "{\"topic\":\"t\",\"body\":\"b\",\"sources\":[123]}",
            "{\"topic\":\"t\",\"body\":\"b\",\"sources\":[null]}",
            "{\"topic\":\"t\",\"body\":\"b\",\"sources\":null}",
            "{\"topic\":\"one\",\"topic\":\"two\",\"body\":\"b\",\"sources\":[\"https://example.com\"]}",
            "{\"topic\":\"t\",\"body\":\"b\",\"sources\":[\"https://example.com\"]} {}"})
    void invalidRequestsCannotReachAuthorizationOrStorage(String json)
            throws Exception
    {
        store.save(Optional.empty(), build(Optional.of(research())));
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        assertThat(error(bridge, json)).isNotBlank();
        assertThat(saved).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void rechecksAuthorizationBeforeSavingAnything()
            throws Exception
    {
        ContextBuild build = build(Optional.of(research()));
        store.save(Optional.empty(), build);
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenReturn(build).thenThrow(new IllegalArgumentException("The turn was stopped"));
        assertThat(error(bridge, """
                {"topic":"t","body":"b","sources":["https://example.com"]}""")).isEqualTo("The turn was stopped");
        assertThat(saved).isEmpty();
    }

    @Test
    void checksTheCurrentQuotaInsideTheTransaction()
            throws Exception
    {
        ContextBuild build = build(Optional.of(research()));
        ContextBuild full = build.withCapture(build.capture().withAttempts(IntStream.range(0, GoalResearchTools.MAX_NOTES).boxed()
                .collect(toImmutableMap(index -> GoalResearchTools.SAVED + index, ignored -> "1"))));
        store.save(Optional.empty(), build);
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenReturn(build, full);
        assertThat(error(bridge, """
                {"topic":"t","body":"b","sources":["https://example.com"]}""")).contains("already saved 40 notes");
        assertThat(saved).isEmpty();
    }

    @Test
    void unexpectedStorageFailureIsNotReportedAsInvalidInput()
    {
        store.save(Optional.empty(), build(Optional.of(research())));
        ReviewBridge bridge = tools.bridge(TASK.toString()).orElseThrow();
        doThrow(new IllegalStateException("Storage unavailable")).when(unitOfWork).execute(any());
        assertThatThrownBy(() -> bridge.tool("goal_knowledge_save").orElseThrow().call().apply("""
                {"topic":"t","body":"b","sources":["https://example.com"]}"""))
                .isInstanceOf(IllegalStateException.class).hasMessage("Storage unavailable");
        assertThat(saved).isEmpty();
    }

    @Test
    void onlyAResearchRunGetsTheTool()
    {
        store.save(Optional.empty(), build(Optional.empty()));
        assertThat(tools.bridge(TASK.toString())).isEmpty();
    }

    private String error(ReviewBridge bridge, String arguments)
            throws Exception
    {
        return call(bridge, arguments).path("error").path("message").asText();
    }

    private JsonNode call(ReviewBridge bridge, String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.tool("goal_knowledge_save").orElseThrow().call().apply(arguments));
    }

    private static ContextBuild.Research research()
    {
        return new ContextBuild.Research("card-1", "goal-1", "Iceberg connector expert", "Explain and change the connector", "Snapshots, manifests and scan planning",
                ImmutableList.of("https://iceberg.apache.org/spec/"), ImmutableList.of(new ContextBuild.Related("apache/iceberg", "/tmp/iceberg", ICEBERG)));
    }

    private static ContextBuild build(Optional<ContextBuild.Research> research)
    {
        Optional<ContextBuild.Check> check = research.isPresent() ? Optional.empty() : Optional.of(new ContextBuild.Check("check-1", "plugin/x/", Optional.empty(), "brief"));
        return new ContextBuild(1, "research-request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(), new RepositoryIdentity("trinodb", "trino"), 1,
                RepoRef.of("trinodb", "trino"), HEAD, "bytequay/research", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH,
                ContextBuild.Capture.empty(), check, Optional.empty(), research);
    }
}
