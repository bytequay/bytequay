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
import com.bytequay.app.context.ContextSpecCheck.Candidate;
import com.bytequay.app.context.ContextSpecCheck.Predicted;
import com.bytequay.app.context.ContextSpecCheck.Status;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
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
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestContextSpecCheckTools
{
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private static final String SHA = "a".repeat(40);
    private static final String SPEC = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ContextBuildStore store = new ContextBuildStore(new InMemoryAppSettingsStore(), tasks, mapper);
    private final ContextBuildService service = mock(ContextBuildService.class);
    private final ContextSpecCheckService checks = mock(ContextSpecCheckService.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);

    @BeforeEach
    void setUp()
    {
        AgentTask task = AgentTask.created(TASK, Optional.of(WORKSPACE), false, "Check", new AgentLaneId("codex"), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/check"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH)
                .claimWorktreeMutation(WorktreeMutationClaim.turn(TURN, Instant.EPOCH), Instant.EPOCH);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenAnswer(call -> current());
        when(unitOfWork.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
    }

    @Test
    void aPlanningTurnSearchesReportsAndSavesCandidatesThatCiteThem()
            throws Exception
    {
        store.save(Optional.empty(), build(Optional.empty()));
        ReviewBridge bridge = bridge();
        assertThat(bridge.tools()).extracting(ReviewTool::name)
                .containsExactly("repository_context_read", "repository_context_spec_search", "repository_context_spec_candidates");
        when(checks.searchReports(any(), eq("deletion vector"))).thenReturn(ImmutableList.of(new ContextSpecCheckService.Report(31252,
                "Checkpoint loses a remove", "open", "issue", "https://github.com/owner/repository/issues/31252", ImmutableList.of(), "excerpt")));

        JsonNode found = call(bridge, "repository_context_spec_search", "{\"words\":\"deletion vector\"}");

        assertThat(found.path("reports").get(0).path("number").intValue()).isEqualTo(31252);
        assertThat(current().capture().attempts()).containsKey("spec-report:31252");
        when(checks.saveCandidates(any(), anyList(), anyList(), anyList(), anyList(), any())).thenAnswer(call -> {
            ContextSpecCheckService.Found candidate = call.<List<ContextSpecCheckService.Found>>getArgument(1).getFirst();
            assertThat(candidate.predicted()).isEqualTo(Predicted.DIVERGES);
            assertThat(candidate.duplicate()).contains(31252);
            IntFunction<Optional<ContextSpecCheck.Duplicate>> searched = call.getArgument(5);
            assertThat(searched.apply(31252)).map(ContextSpecCheck.Duplicate::open).contains(true);
            assertThat(searched.apply(7)).isEmpty();
            return new ContextSpecCheckService.Saved(ImmutableList.of("c1"), ImmutableList.of(), 0, 1, ImmutableList.of());
        });

        JsonNode saved = call(bridge, "repository_context_spec_candidates", "{\"candidates\":[{\"predicted\":\"DIVERGES\",\"title\":\"Removes keyed by path\","
                + "\"rule\":\"MUST key by path and deletion vector\",\"specUrl\":\"" + SPEC + "#checkpoints\",\"code\":[\"plugin/x/Writer.java:1-2\"],"
                + "\"reasoning\":\"why\",\"severity\":3,\"duplicate\":31252}],\"covered\":[\"Checkpoints\"]}");

        assertThat(saved.path("saved").path("saved").get(0).textValue()).isEqualTo("c1");
        assertThat(current().capture().attempts()).containsKey(ContextRun.CHECKED);
        assertThat(current().capture().activity()).extracting(ContextBuild.Activity::text)
                .containsExactly("Searched reports for “deletion vector” · 1 report", "Saved 1 candidate · 0 to reproduce");
        assertThat(call(bridge, "repository_context_spec_candidates", "{\"candidates\":[{\"predicted\":\"MAYBE\",\"title\":\"t\",\"rule\":\"r\","
                + "\"specUrl\":\"" + SPEC + "\",\"code\":[\"a:1\"],\"reasoning\":\"r\",\"severity\":1}]}").path("error").path("message").textValue())
                .startsWith("predicted: use DIVERGES");
    }

    @Test
    void aReproductionTurnOnlyRecordsItsResult()
            throws Exception
    {
        store.save(Optional.empty(), build(Optional.of(new ContextBuild.Reproduction("c1", "Removes keyed by path"))));
        ReviewBridge bridge = bridge();
        assertThat(bridge.tools()).extracting(ReviewTool::name).containsExactly("repository_context_read", "repository_context_spec_result");
        when(checks.saveResult(any(), eq(TURN), any())).thenAnswer(call -> {
            ContextSpecCheckService.Result result = call.getArgument(2);
            assertThat(result.outcome()).isEqualTo(Status.CONFIRMED);
            assertThat(result.command()).contains("./mvnw -pl plugin/x test -Dtest=TestKeys");
            return new Candidate("c1", Predicted.DIVERGES.type(), Predicted.DIVERGES, "Removes keyed by path", "MUST key by path and deletion vector",
                    SPEC + "#checkpoints", ImmutableList.of("plugin/x/Writer.java:1-2"), "why", 3, Optional.empty(), Status.CONFIRMED,
                    Optional.of(new ContextSpecCheck.Test("plugin/x/TestKeys.java", "./mvnw -pl plugin/x test -Dtest=TestKeys", 1, "expected 2", "class TestKeys {}")),
                    Optional.of("fails"), Optional.of(new ContextSpecCheck.Draft("Removes keyed by path", "body", ImmutableList.of())), Optional.empty());
        });

        JsonNode recorded = call(bridge, "repository_context_spec_result", "{\"outcome\":\"CONFIRMED\",\"reason\":\"fails\",\"testPath\":\"plugin/x/TestKeys.java\","
                + "\"command\":\"./mvnw -pl plugin/x test -Dtest=TestKeys\",\"summary\":\"s\",\"impact\":\"i\",\"proposal\":\"p\"}");

        assertThat(recorded.path("status").textValue()).isEqualTo("CONFIRMED");
        assertThat(recorded.path("exitCode").intValue()).isEqualTo(1);
        assertThat(current().capture().attempts()).containsEntry(ContextRun.CHECKED, "CONFIRMED");
        assertThat(current().capture().activity()).extracting(ContextBuild.Activity::text).containsExactly("Confirmed · Removes keyed by path · exit 1");
        assertThat(call(bridge, "repository_context_spec_result", "{\"outcome\":\"MAYBE\",\"reason\":\"r\"}").path("error").path("message").textValue())
                .startsWith("outcome: use CONFIRMED");
    }

    private ReviewBridge bridge()
    {
        ContextBuildTools learning = new ContextBuildTools(store, service, tasks, mock(WorkspaceRepositoryService.class),
                mock(RepositoryContextService.class), mock(ContextBaselineReader.class), mock(SelectedDocumentationReader.class), mock(ContextPageCaptures.class),
                mock(ContextHistoryReader.class), mock(RepositoryContextPreparation.class), unitOfWork, mapper, mock(ContextLearningService.class),
                mock(ContextNotebookLookup.class), mock(ContextMergedCandidateService.class), CLOCK);
        return new ContextSpecCheckTools(store, service, learning, checks, tasks, unitOfWork, mapper, CLOCK).bridge(TASK.toString()).orElseThrow();
    }

    private JsonNode call(ReviewBridge bridge, String tool, String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.tool(tool).orElseThrow().call().apply(arguments));
    }

    private ContextBuild current()
    {
        return store.find(WORKSPACE).orElseThrow();
    }

    private static ContextBuild build(Optional<ContextBuild.Reproduction> reproduction)
    {
        return new ContextBuild(1, "check-request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(), new RepositoryIdentity("owner", "repository"), 1,
                RepoRef.of("owner", "repository"), SHA, "bytequay/check", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of("project"), ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, ContextBuild.Capture.empty(), ContextBuild.Mode.CHECK,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new ContextBuild.Check("check-1", "Delta Lake connector", reproduction, "brief")));
    }
}
