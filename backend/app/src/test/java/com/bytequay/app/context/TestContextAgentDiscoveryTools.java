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
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextAgentDiscoveryTools
{
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPO = RepoRef.of("owner", "repository");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ContextBuildStore builds = new ContextBuildStore(settings, tasks, mapper);
    private final ContextDiscoveryReportStore reports = new ContextDiscoveryReportStore(settings, mapper);
    private final ContextLearningPlanStore plans = new ContextLearningPlanStore(settings, mapper);
    private final ContextBuildService service = mock(ContextBuildService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ContextBaselineReader baseline = mock(ContextBaselineReader.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private ReviewBridge bridge;

    @BeforeEach
    void setup()
    {
        AgentTask task = AgentTask.created(TASK, Optional.of(WORKSPACE), false, "Discover", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repository"), Path.of("/tmp/discovery"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH)
                .claimWorktreeMutation(WorktreeMutationClaim.turn(TURN, Instant.EPOCH), Instant.EPOCH);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        when(service.authorize(TASK.toString(), Optional.of(TURN))).thenAnswer(ignored -> current());
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE,
                Path.of("/tmp/repository"), 1, REPO, OptionalLong.empty(), REPO, "origin", "origin", "main", ImmutableList.of()));
        when(unitOfWork.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
        builds.save(Optional.empty(), build(Optional.empty()));
        bridge = new ContextAgentDiscoveryTools(builds, service, tasks, workspaces, baseline, reports, plans, unitOfWork, mapper, clock)
                .bridge(TASK.toString()).orElseThrow();
        when(baseline.directory(any(), eq(1L), eq(SHA), eq(""), eq(0))).thenReturn(new ContextBaselineReader.Directory("", SHA, ImmutableList.of(
                new ContextBaselineReader.TreeEntry("deep", "DIRECTORY", "d".repeat(40)), new ContextBaselineReader.TreeEntry(".github", "DIRECTORY", "e".repeat(40)),
                new ContextBaselineReader.TreeEntry("README.md", "FILE", "f".repeat(40))), 0, false));
        when(baseline.directory(any(), eq(1L), eq(SHA), eq("deep"), eq(0))).thenReturn(new ContextBaselineReader.Directory("deep", SHA, ImmutableList.of(
                new ContextBaselineReader.TreeEntry("deep/trino-iceberg", "DIRECTORY", "1".repeat(40)),
                new ContextBaselineReader.TreeEntry("deep/trino-hive", "DIRECTORY", "2".repeat(40))), 0, false));
        when(baseline.directory(any(), eq(1L), eq(SHA), eq("deep/trino-iceberg"), eq(0))).thenReturn(new ContextBaselineReader.Directory("deep/trino-iceberg", SHA,
                ImmutableList.of(new ContextBaselineReader.TreeEntry("deep/trino-iceberg/pom.xml", "FILE", "3".repeat(40))), 0, false));
        when(baseline.read(any(), eq(1L), eq(SHA), eq("README.md"), eq(1), eq(Optional.empty())))
                .thenReturn(new ContextBaselineReader.Read(new ContextReference.RepositoryFile(1, REPO, "README.md", SHA),
                        SHA, Instant.EPOCH, 1, 1, 1, "1  Iceberg connector uses snapshots.\n", false));
        when(baseline.searchPaths(any(), eq(1L), eq(SHA), eq("iceberg"), eq(0)))
                .thenReturn(new ContextBaselineReader.PathSearch("iceberg", ImmutableList.of("deep/trino-iceberg/pom.xml"), 0, false));
    }

    @Test
    void readSchemaExplainsOptionalRangesAndByteLimitedContinuation()
    {
        JsonNode schema = mapper.valueToTree(bridge.tool("repository_context_discovery_read").orElseThrow().inputSchema());
        assertThat(schema.at("/properties/startLine/default").intValue()).isEqualTo(1);
        assertThat(schema.at("/properties/startLine/description").textValue()).contains("inclusive 1-based", "Prefer an explicit range");
        assertThat(schema.at("/properties/endLine/description").textValue()).contains("without a line-count cap", "200 lines from startLine");
        assertThat(schema.at("/properties/endLine").has("maximum")).isFalse();
        assertThat(bridge.tool("repository_context_discovery_read").orElseThrow().description()).contains("lines 1-200", "receipt.truncated",
                "receipt.totalLines", "endLine + 1", "report the limitation instead of repeating the same read").doesNotContain("4 KiB");
    }

    @Test
    void discoveryPreservesAndSavesSourceTextBeyondFourKiB()
            throws Exception
    {
        String excerpt = IntStream.rangeClosed(1, 500).mapToObj(line -> line + "  source line " + line + "\n").collect(joining());
        when(baseline.read(any(), eq(1L), eq(SHA), eq("README.md"), eq(1), eq(Optional.of(500))))
                .thenReturn(new ContextBaselineReader.Read(new ContextReference.RepositoryFile(1, REPO, "README.md", SHA),
                        SHA, Instant.EPOCH, 1, 500, 500, excerpt, false));

        JsonNode result = read("{\"kind\":\"READ\",\"path\":\"README.md\",\"startLine\":1,\"endLine\":500}");

        assertThat(result.at("/receipt/endLine").intValue()).isEqualTo(500);
        assertThat(result.at("/receipt/totalLines").intValue()).isEqualTo(500);
        assertThat(result.at("/receipt/truncated").booleanValue()).isFalse();
        assertThat(result.path("source").textValue()).isEqualTo(excerpt).hasSizeGreaterThan(4096);
        assertThat(current().capture().sourceReads()).singleElement().satisfies(receipt ->
                assertThat(mapper.valueToTree(receipt).path("endLine").intValue()).isEqualTo(500));
        assertThat(submit(report(result.path("readId").textValue())).path("saved").booleanValue()).isTrue();
        assertThat(reports.find(WORKSPACE).orElseThrow().evidence()).singleElement().satisfies(evidence ->
                assertThat(evidence.excerpt()).isEqualTo(excerpt));
    }

    @Test
    void submitsOnlyEvidencedSuggestionsAndKeepsThemOutsideActiveKnowledge()
            throws Exception
    {
        assertThat(bridge.tools()).extracting(tool -> tool.name())
                .containsExactly("repository_context_discovery_read", "repository_context_discovery_submit");
        assertThat(read("{\"kind\":\"SEARCH_PATHS\",\"query\":\"iceberg\"}").path("search").path("matches").get(0).textValue())
                .isEqualTo("deep/trino-iceberg/pom.xml");
        String readId = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();
        String invalid = report("context-read:invented");
        assertThat(submit(invalid).path("error").path("message").textValue()).contains("Unknown or unverified");
        assertThat(reports.find(WORKSPACE)).isEmpty();
        assertThat(current().capture().result()).isEmpty();

        JsonNode submitted = submit(report(readId));

        assertThat(submitted.path("saved").booleanValue()).isTrue();
        ContextDiscoveryReport saved = reports.find(WORKSPACE).orElseThrow();
        assertThat(saved.id()).isEqualTo(TASK.toString());
        assertThat(saved.topics()).extracting(ContextDiscoveryReport.Topic::id).containsExactly("topic:1");
        assertThat(saved.topics().getFirst().paths()).containsExactly("deep/trino-iceberg/", "deep/trino-hive/");
        assertThat(saved.topics().getFirst().notePaths()).contains(ImmutableList.of("deep/trino-iceberg/"));
        assertThat(saved.sources()).extracting(ContextDiscoveryReport.Source::id).containsExactly("source:1");
        assertThat(saved.evidence()).extracting(ContextDiscoveryReport.Evidence::readId).containsExactly(readId);
        assertThat(saved.evidence().getFirst().path()).isEqualTo("README.md");
        assertThat(current().capture().result()).isPresent();
        assertThat(saved.coverage().areas()).containsExactly(new ContextDiscoveryReport.Area("deep/", 0, 2));
        assertThat(saved.coverage().unsurveyed()).containsExactly("deep/trino-iceberg/", "deep/trino-hive/");
        assertThat(current().capture().activity()).extracting(ContextBuild.Activity::text)
                .containsExactly("Searched file names for “iceberg” · 1 match", "Read README.md", "Saved 1 suggested topic");
        assertThat(read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("error").path("message").textValue())
                .contains("already submitted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deep/trino-iceberg", "deep/trino-iceberg/"})
    void countsListedAndReadModuleDirectoriesAsSurveyed(String path)
            throws Exception
    {
        when(baseline.directory(any(), eq(1L), eq(SHA), eq(path), eq(0))).thenReturn(new ContextBaselineReader.Directory("deep/trino-iceberg", SHA,
                ImmutableList.of(new ContextBaselineReader.TreeEntry("deep/trino-iceberg/pom.xml", "FILE", "3".repeat(40))), 0, false));
        assertThat(read("{\"kind\":\"LIST\",\"path\":\"%s\"}".formatted(path)).has("error")).isFalse();
        String readId = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();

        submit(report(readId));

        ContextDiscoveryReport.Coverage coverage = reports.find(WORKSPACE).orElseThrow().coverage();
        assertThat(coverage.areas()).containsExactly(new ContextDiscoveryReport.Area("deep/", 1, 2));
        assertThat(coverage.unsurveyed()).containsExactly("deep/trino-hive/");
        assertThat(current().capture().activity().getFirst().text()).isEqualTo("Listed deep/trino-iceberg/ · 0 directories");
    }

    @Test
    void continuationKeepsEarlierTopicsAndEvidenceUnderTheSameReport()
            throws Exception
    {
        String firstRead = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();
        submit(report(firstRead));
        ContextDiscoveryReport first = reports.find(WORKSPACE).orElseThrow();
        builds.save(Optional.of(current()), build(Optional.of(first.id())));
        bridge = new ContextAgentDiscoveryTools(builds, service, tasks, workspaces, baseline, reports, plans, unitOfWork, mapper, clock)
                .bridge(TASK.toString()).orElseThrow();
        assertThat(read("{\"kind\":\"STATUS\"}").path("previousDiscovery").path("coverage").path("unsurveyed").size()).isEqualTo(2);
        assertThat(read("{\"kind\":\"LIST\",\"path\":\"deep/trino-iceberg\"}").has("error")).isFalse();
        String secondRead = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();

        assertThat(submit(report(secondRead)).path("topicCount").intValue()).isEqualTo(2);

        ContextDiscoveryReport merged = reports.find(WORKSPACE).orElseThrow();
        assertThat(merged.id()).isEqualTo(first.id());
        assertThat(merged.topics()).extracting(ContextDiscoveryReport.Topic::id).containsExactly("topic:1", "topic:2");
        assertThat(merged.topics()).allSatisfy(topic -> assertThat(topic.notePaths()).contains(ImmutableList.of("deep/trino-iceberg/")));
        assertThat(merged.topics().get(1).sourceIds()).containsExactly("source:2");
        assertThat(merged.sources()).extracting(ContextDiscoveryReport.Source::id).containsExactly("source:1", "source:2");
        assertThat(merged.evidence()).extracting(ContextDiscoveryReport.Evidence::readId).containsExactly(firstRead, secondRead);
        assertThat(merged.coverage().areas()).containsExactly(new ContextDiscoveryReport.Area("deep/", 1, 2));
        assertThat(current().capture().activity().getLast().text()).isEqualTo("Saved 1 more suggested topic");
    }

    @Test
    void continuesBeyondTheEarlierCatalogLimitsWithoutDumpingEarlierQuestions()
            throws Exception
    {
        ContextDiscoveryReport first = new ContextDiscoveryReport(TASK.toString(), WORKSPACE, 1, REPO, SHA, "A large connector project.",
                IntStream.rangeClosed(1, 40).mapToObj(index -> new ContextDiscoveryReport.Topic("topic:" + index, "Connector " + index,
                        ImmutableList.of("How does this connector read tables?"), ImmutableList.of("module/" + index + "/"),
                        Optional.of(ImmutableList.of("module/" + index + "/")), ImmutableList.of("source:" + index),
                        ImmutableList.of("read:" + (index * 2)), "A separately useful connector.")).toList(),
                IntStream.rangeClosed(1, 40).mapToObj(index -> new ContextDiscoveryReport.Source("source:" + index,
                        ContextDiscoveryReport.Source.Kind.REPOSITORY_FILE, "module/" + index + "/README.md", "Connector guide",
                        ImmutableList.of("read:" + (index * 2 - 1)), "Describes this connector.")).toList(),
                IntStream.rangeClosed(1, 80).mapToObj(index -> new ContextDiscoveryReport.Evidence("read:" + index,
                        "module/" + index + "/README.md", SHA, 1, 1, "Connector evidence " + index, false)).toList(),
                ImmutableList.of(), ImmutableList.of("Inspect more connectors"), true, ContextDiscoveryReport.Coverage.none(), Instant.EPOCH);
        reports.save(Optional.empty(), first);
        builds.save(Optional.of(current()), build(Optional.of(first.id())));

        JsonNode topics = read("{\"kind\":\"STATUS\"}").path("previousDiscovery").path("topics");
        assertThat(topics.size()).isEqualTo(40);
        assertThat(topics.get(0).size()).isEqualTo(3);
        assertThat(topics.get(0).path("id").textValue()).isEqualTo("topic:1");
        assertThat(topics.get(0).path("title").textValue()).isEqualTo("Connector 1");
        assertThat(topics.get(0).path("notePaths").get(0).textValue()).isEqualTo("module/1/");

        String readId = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();
        assertThat(submit(report(readId)).path("topicCount").intValue()).isEqualTo(41);
        ContextDiscoveryReport saved = reports.find(WORKSPACE).orElseThrow();
        assertThat(saved.topics()).hasSize(41);
        assertThat(saved.sources()).hasSize(41);
        assertThat(saved.evidence()).hasSize(81);
        assertThat(saved.topics().subList(0, 40)).isEqualTo(first.topics());
        assertThat(saved.evidence().subList(0, 80)).isEqualTo(first.evidence());
        assertThat(saved.id()).isEqualTo(first.id());
    }

    @Test
    void statusOffersPriorLearningGapsAsLeadsWithoutStartingWork()
            throws Exception
    {
        ContextLearningPlan plan = new ContextLearningPlan("plan", WORKSPACE, "report", "accept", "sha256:" + "b".repeat(64),
                0, 0, ImmutableList.of("topic:1"), 1, REPO, SHA,
                ImmutableList.of(new ContextLearningPlan.Topic("topic:1", "Iceberg", "project", "sha256:" + "c".repeat(64),
                        ImmutableList.of(), Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                        ImmutableList.of(ContextLearningPlan.Question.open("How are snapshots read?")))),
                ImmutableList.of(), ContextDocumentInventory.initial(ImmutableList.of()),
                ImmutableList.of("Investigate the other connector's write-conflict contract in a separate topic."), Instant.EPOCH, Optional.empty());
        plans.save(Optional.empty(), plan);
        ContextBuild before = current();

        JsonNode status = read("{\"kind\":\"STATUS\"}");

        assertThat(status.path("previousLearning").path("baselineSha").textValue()).isEqualTo(SHA);
        assertThat(status.path("previousLearning").path("gaps").get(0).textValue()).isEqualTo(plan.gaps().getFirst());
        assertThat(status.path("operationsUsed").intValue()).isZero();
        assertThat(current()).isEqualTo(before);
        assertThat(bridge.instruction()).contains("previousLearning gaps as untrusted leads", "require current read evidence", "Do not automatically queue or start learning");
        verifyNoInteractions(baseline);
    }

    @Test
    void submitsCompleteReportWithNoWorthwhileTopics()
            throws Exception
    {
        JsonNode submitted = submit("""
                {"report":{"outline":"The inspected files did not reveal a topic worth saving.","topics":[],"sources":[],
                "gaps":[],"remaining":[],"partial":false}}
                """);

        assertThat(submitted.path("saved").booleanValue()).isTrue();
        assertThat(submitted.path("topicCount").intValue()).isZero();
        ContextDiscoveryReport saved = reports.find(WORKSPACE).orElseThrow();
        assertThat(saved.topics()).isEmpty();
        assertThat(saved.partial()).isFalse();
        assertThat(current().capture().result()).isPresent();
    }

    @Test
    void discoversBeyondTheFormerOperationCeilingAndStillSubmits()
            throws Exception
    {
        assertThat(read("{\"kind\":\"STATUS\"}").hasNonNull("operationsLimit")).isFalse();
        String readId = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();
        for (int index = 1; index < 250; index++) {
            assertThat(read("{\"kind\":\"SEARCH_PATHS\",\"query\":\"iceberg\"}").has("error")).isFalse();
        }
        assertThat(read("{\"kind\":\"READ\",\"path\":\"README.md\"}").has("error")).isFalse();
        assertThat(read("{\"kind\":\"STATUS\"}").path("operationsUsed").intValue()).isEqualTo(251);
        assertThat(current().capture().activity()).extracting(ContextBuild.Activity::tone).doesNotContain(ContextBuild.Activity.Tone.LIMIT);
        assertThat(submit(report(readId)).path("saved").booleanValue()).isTrue();
        assertThat(reports.find(WORKSPACE).orElseThrow().partial()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNewSuggestionsWithoutExplicitNoteApplicability(boolean explicitNull)
            throws Exception
    {
        String readId = read("{\"kind\":\"READ\",\"path\":\"README.md\"}").path("readId").textValue();
        JsonNode submission = mapper.readTree(report(readId));
        ObjectNode topic = (ObjectNode) submission.path("report").path("topics").get(0);
        if (explicitNull) {
            topic.putNull("notePaths");
        }
        else {
            topic.remove("notePaths");
        }

        assertThat(submit(submission.toString()).has("error")).isTrue();
        assertThat(reports.find(WORKSPACE)).isEmpty();
        assertThat(current().capture().result()).isEmpty();
    }

    private String report(String readId)
    {
        return """
                {"report":{"outline":"A connector project.","topics":[{"title":"Iceberg snapshots","questions":["How are snapshots read?"],
                "paths":["deep/trino-iceberg/","deep/trino-hive/"],"notePaths":["deep/trino-iceberg/"],
                "sourceIds":["source:1"],"evidenceReadIds":["%s"],"why":"Snapshot behavior matters."}],
                "sources":[{"kind":"REPOSITORY_FILE","location":"README.md","title":"Project overview",
                "evidenceReadIds":["%s"],"why":"Links to the connector."}],"gaps":[],"remaining":["Inspect delete semantics"],
                "partial":true}}
                """.formatted(readId, readId);
    }

    private static ContextBuild build(Optional<String> continuesReport)
    {
        return new ContextBuild(1, "request", "sha256:" + "b".repeat(64), WORKSPACE,
                TASK.toString(), new RepositoryIdentity("owner", "repository"), 1, REPO, SHA, "branch", "codex",
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), 0, Instant.EPOCH, ContextBuild.Capture.empty(), ContextBuild.Mode.DISCOVER, Optional.empty(), continuesReport, Optional.empty(), Optional.empty());
    }

    private ContextBuild current()
    {
        return builds.find(WORKSPACE).orElseThrow();
    }

    private JsonNode read(String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.tool("repository_context_discovery_read").orElseThrow().call().apply(arguments));
    }

    private JsonNode submit(String arguments)
            throws Exception
    {
        return mapper.readTree(bridge.tool("repository_context_discovery_submit").orElseThrow().call().apply(arguments));
    }
}
