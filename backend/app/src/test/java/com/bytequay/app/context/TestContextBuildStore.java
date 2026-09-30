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
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.context.RepositoryContextPreparation.CapturedSource;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestContextBuildStore
{
    private static final String WORKSPACE = "workspace";
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final ContextBuildStore store = new ContextBuildStore(settings, tasks, mapper);

    @Test
    void reloadsTypedEvidenceAndExactOperationReceiptsFromExistingSettings()
    {
        RegisteredContextSource registration = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs/page", ContextEntryOrigin.USER, Optional.empty());
        var file = new ContextRepositoryRead("context-read:file", new ContextReference.RepositoryFile(1, REPOSITORY, "README.md", SHA), SHA,
                Instant.EPOCH, 1, 1, 1, 6, false, Optional.of("file"));
        var document = new ContextDocumentRead("context-read:doc", new ContextReference.Document("docs", registration.url()), registration, 0,
                UUID.randomUUID().toString(), "b".repeat(64), Instant.EPOCH, 1, 1, 6, false, false, false, Optional.of("doc"));
        var history = new ContextHistoryRead("context-read:pr", new ContextReference.PullRequest(1, REPOSITORY, 123, Optional.empty()),
                ContextHistoryRead.State.MERGED, Optional.of(Instant.EPOCH), Optional.of(SHA), Hashing.sha256().hashString("source", UTF_8).toString(),
                Instant.EPOCH, 6, false, false, ImmutableList.of("Applicability unverified"), Optional.of("pr"));
        CapturedSource evidence = new CapturedSource("Original source", "source");
        ContextBuild build = build().withCapture(new ContextBuild.Capture(ImmutableList.of(file, document, history),
                ImmutableMap.of("file", evidence, "doc", evidence, "pr", evidence), ImmutableMap.of("tree:", "Read root"),
                ImmutableMap.of("begin", new ContextBuild.Operation("sha256:" + "b".repeat(64), "{\"status\":\"OWNED\"}")),
                Optional.empty(), ImmutableList.of("One bounded source page was selected"), ImmutableList.of())
                .logged(Instant.EPOCH, "Read README.md", ContextBuild.Activity.Tone.DONE));
        store.save(Optional.empty(), build);

        ContextBuildStore recreated = new ContextBuildStore(settings, tasks, mapper);
        assertThat(recreated.find(WORKSPACE)).contains(build);
        assertThat(recreated.find("different-workspace")).isEmpty();
        assertThatThrownBy(() -> recreated.save(Optional.empty(), build)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        assertThat(recreated.find(WORKSPACE)).contains(build);
    }

    @Test
    void buildsAndRunsSavedBeforeInvestigationsStillLoad()
            throws Exception
    {
        store.save(Optional.empty(), build());
        store.recordRun(WORKSPACE, run(1));
        ObjectNode saved = (ObjectNode) mapper.readTree(settings.get(ContextBuildStore.PREFIX + WORKSPACE).orElseThrow());
        saved.remove("investigation");
        settings.put(ContextBuildStore.PREFIX + WORKSPACE, mapper.writeValueAsString(saved));
        ArrayNode runs = (ArrayNode) mapper.readTree(settings.get(ContextBuildStore.RUNS_PREFIX + WORKSPACE).orElseThrow());
        runs.forEach(run -> ((ObjectNode) run).remove("investigation"));
        settings.put(ContextBuildStore.RUNS_PREFIX + WORKSPACE, mapper.writeValueAsString(runs));

        ContextBuildStore reopened = new ContextBuildStore(settings, tasks, mapper);
        assertThat(reopened.find(WORKSPACE)).contains(build());
        assertThat(reopened.runs(WORKSPACE)).containsExactly(run(1));
    }

    @Test
    void keepsTheNewestRunSummariesOnce()
    {
        for (int sequence = 1; sequence <= ContextBuildStore.MAX_RUNS + 2; sequence++) {
            store.recordRun(WORKSPACE, run(sequence));
        }
        store.recordRun(WORKSPACE, run(ContextBuildStore.MAX_RUNS + 2));

        List<ContextRun> runs = new ContextBuildStore(settings, tasks, mapper).runs(WORKSPACE);
        assertThat(runs).hasSize(ContextBuildStore.MAX_RUNS);
        assertThat(runs.getFirst().sequence()).isEqualTo(3);
        assertThat(runs.getLast().sequence()).isEqualTo(ContextBuildStore.MAX_RUNS + 2);
        assertThat(store.runs("different-workspace")).isEmpty();
    }

    @Test
    void retainsEvidenceAndActivityForLargerRuns()
    {
        String excerpt = "x".repeat(4 * 1024);
        ImmutableList.Builder<ContextSourceRead> reads = ImmutableList.builder();
        ImmutableMap.Builder<String, CapturedSource> evidence = ImmutableMap.builder();
        ImmutableMap.Builder<String, String> attempts = ImmutableMap.builder();
        for (int index = 0; index < 256; index++) {
            String id = "context-read:" + index;
            String path = "src/File" + index + ".java";
            reads.add(new ContextRepositoryRead(id, new ContextReference.RepositoryFile(1, REPOSITORY, path, SHA), SHA,
                    Instant.EPOCH, 1, 1, 1, excerpt.length(), false, Optional.of(id)));
            evidence.put(id, new CapturedSource("Captured source", excerpt));
            attempts.put("read-operation:" + index, "Reserved").put("file:" + path, id);
        }
        List<ContextBuild.Activity> activity = IntStream.range(0, ContextBuild.Capture.MAX_ACTIVITY)
                .mapToObj(index -> new ContextBuild.Activity(Instant.EPOCH, "x".repeat(400), ContextBuild.Activity.Tone.DONE)).toList();
        ContextBuild build = build().withCapture(new ContextBuild.Capture(reads.build(), evidence.buildOrThrow(), attempts.buildOrThrow(),
                ImmutableMap.of(), Optional.empty(), ImmutableList.of(), activity));
        store.save(Optional.empty(), build);
        assertThat(settings.get(ContextBuildStore.PREFIX + WORKSPACE).orElseThrow().length()).isGreaterThan(1024 * 1024);
        assertThat(new ContextBuildStore(settings, tasks, mapper).find(WORKSPACE)).contains(build);

        for (int sequence = 1; sequence <= ContextBuildStore.MAX_RUNS; sequence++) {
            store.recordRun(WORKSPACE, new ContextRun(sequence, "task:" + sequence, ContextBuild.Mode.LEARN, Instant.EPOCH, Optional.of(Instant.EPOCH),
                    Optional.empty(), Optional.empty(), ContextRun.Outcome.UNFINISHED, 256, 0,
                    ImmutableList.of(), activity, Optional.empty(), Optional.empty()));
        }
        assertThat(settings.get(ContextBuildStore.RUNS_PREFIX + WORKSPACE).orElseThrow().length()).isGreaterThan(1024 * 1024);
        assertThat(new ContextBuildStore(settings, tasks, mapper).runs(WORKSPACE)).hasSize(ContextBuildStore.MAX_RUNS);
    }

    private static ContextRun run(long sequence)
    {
        return new ContextRun(sequence, "task-" + sequence, ContextBuild.Mode.LEARN, Instant.EPOCH, Optional.of(Instant.EPOCH),
                Optional.of(new ContextRun.Question("plan", "topic:1", "Iceberg connector", 0, "How are snapshots committed?")), Optional.empty(),
                ContextRun.Outcome.ANSWERED, 12, 2, ImmutableList.of("knowledge:project:commit"),
                ImmutableList.of(new ContextBuild.Activity(Instant.EPOCH, "Question answered", ContextBuild.Activity.Tone.DONE)), Optional.empty(), Optional.empty());
    }

    @Test
    void resolvesPurposeOnlyForTheCurrentTaskInItsStoredWorkspace()
    {
        ContextBuild build = store.save(Optional.empty(), build());
        AgentTask task = task(WORKSPACE);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        assertThat(store.findByTask(TASK.toString())).contains(build);
        TaskId other = TaskId.valueOf("22222222-2222-4222-8222-222222222222");
        when(tasks.find(other)).thenReturn(Optional.of(task));
        assertThat(store.findByTask(other.toString())).isEmpty();
        when(tasks.find(TASK)).thenReturn(Optional.of(task("other")));
        assertThat(store.findByTask(TASK.toString())).isEmpty();
    }

    private static AgentTask task(String workspaceId)
    {
        return AgentTask.created(TASK, Optional.of(workspaceId), false, "Learn repository", new AgentLaneId("codex"), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/context-learn-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    }

    private static ContextBuild build()
    {
        return new ContextBuild(1, "request", "sha256:" + "b".repeat(64), WORKSPACE, TASK.toString(), new RepositoryIdentity("owner", "repository"),
                1, REPOSITORY, SHA, "bytequay/task", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of("project"), ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, ContextBuild.Capture.empty(), ContextBuild.Mode.LEARN,
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
