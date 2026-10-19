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
package com.bytequay.app.artifact;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactNext;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestArtifactService
{
    private static final AgentLaneId LANE = new AgentLaneId("claude");
    private static final String WORKSPACE = "ws1";
    private static final String PAGE = "<!doctype html><link rel=\"stylesheet\" href=\"theme.css\"><h1>Delete files</h1>";

    @TempDir
    private Path temporaryDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final MemoryArtifactStore store = new MemoryArtifactStore();
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final GitCommandRunner git = new GitCommandRunner();
    private final List<AgentSessionContext> opened = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<RunningTurn> turns = new CopyOnWriteArrayList<>();
    // What the agent does in each turn, in order; past the queue it saves PAGE and finishes.
    private final Queue<Function<AgentSessionContext, CompletableFuture<AgentTurnResult>>> agent = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean conversationLost = new AtomicBoolean();
    private Path runtime;
    private String commit;
    private ArtifactService artifacts;

    @BeforeEach
    void setUp()
            throws Exception
    {
        runtime = temporaryDirectory.resolve("runtime");
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        run(repository, "init", "--quiet", "--initial-branch=main");
        Files.writeString(repository.resolve("Deletes.java"), "class Deletes {}\n");
        run(repository, "add", "Deletes.java");
        run(repository, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "-m", "Add deletes");
        commit = git.run(repository, "rev-parse", "HEAD").output().strip();
        when(repositories.requireReadyRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, repository, 1, RepoRef.of("fork", "trino"),
                OptionalLong.of(7), RepoRef.of("trinodb", "trino"), "upstream", "origin", "main", ImmutableList.of()));
        when(builds.baseline(WORKSPACE)).thenReturn(Optional.of(new ContextBuildService.Baseline("main", commit)));
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(WORKSPACE)))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude", "sonnet", "Sonnet", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(resources.allocate(any())).thenAnswer(call -> {
            Path scratch = runtime.resolve(call.getArgument(0).toString());
            Files.createDirectories(scratch.resolve("provider"));
            return new TaskResources(scratch, scratch.resolve("events.jsonl"), scratch.resolve("blobs"), scratch.resolve("provider"));
        });
        when(driver.open(any(), any())).thenAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            if (conversationLost.get() && context.sessionReference().isPresent()) {
                throw new AgentUnavailableException("Codex App Server thread/resume failed: no rollout found for thread");
            }
            opened.add(context);
            return session;
        });
        when(session.reference()).thenAnswer(call -> new AgentSessionRef(LANE, 1, "session-" + opened.size()));
        when(session.start(any())).thenAnswer(call -> {
            prompts.add(call.<AgentTurnCommand>getArgument(0).message());
            RunningTurn turn = mock(RunningTurn.class);
            CompletableFuture<AgentTurnResult> result = Optional.ofNullable(agent.poll()).orElse(TestArtifactService::savesThePage).apply(opened.getLast());
            when(turn.completion()).thenReturn(result);
            turns.add(turn);
            return turn;
        });
        artifacts = new ArtifactService(store, models, drivers, resources, repositories, builds, git,
                new AgentProperties(runtime, Duration.ZERO, "", "codex", "0.159.3", temporaryDirectory.resolve("codex-home"), "claude", temporaryDirectory.resolve("claude-home")),
                new ObjectMapper(), Clock.systemUTC(), executor);
    }

    @AfterEach
    void stopWorkers()
    {
        executor.shutdownNow();
        executor.close();
    }

    @Test
    void writesAPageReadOnlyInTheWorkspaceCheckoutAndContinuesItsConversation()
    {
        Artifact asked = artifacts.ask(WORKSPACE, "Teach me how Iceberg deletes rows");
        assertThat(asked.state()).isEqualTo(Artifact.State.WRITING);
        assertThat(asked.title()).isEqualTo("Teach me how Iceberg deletes rows");
        Artifact ready = waitUntilEnded(asked.id());

        assertThat(ready.state()).isEqualTo(Artifact.State.READY);
        assertThat(ready.title()).isEqualTo("Delete files");
        assertThat(ready.commit()).contains(commit);
        assertThat(ready.session().orElseThrow().laneId()).isEqualTo("claude");
        assertThat(artifacts.page(WORKSPACE, asked.id())).contains(PAGE);
        AgentSessionContext first = opened.getFirst();
        assertThat(first.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(first.worktree()).isEqualTo(runtime.resolve("artifacts").resolve(WORKSPACE));
        assertThat(first.worktree().resolve("Deletes.java")).exists();
        assertThat(first.scratchWorktree()).isTrue();
        assertThat(first.sessionTools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("artifact_save");
            assertThat(tool.childCallable()).isFalse();
        });
        assertThat(prompts.getFirst()).contains("main at commit " + commit, "Teach me how Iceberg deletes rows").doesNotContain("<page>");

        artifacts.change(WORKSPACE, asked.id(), "Make the diagram simpler");
        Artifact changed = waitUntilEnded(asked.id());

        assertThat(changed.state()).isEqualTo(Artifact.State.READY);
        assertThat(changed.ask()).isEqualTo("Make the diagram simpler");
        // The same conversation goes on, so the agent is not sent the page it wrote.
        assertThat(opened.getLast().sessionReference().orElseThrow().providerValue()).isEqualTo("session-1");
        assertThat(prompts.getLast()).contains("Make the diagram simpler").doesNotContain("<page>");
    }

    @Test
    void aLostConversationGoesOnFreshWithTheCurrentPage()
    {
        String id = artifacts.ask(WORKSPACE, "Teach me delete files").id();
        waitUntilEnded(id);
        conversationLost.set(true);

        artifacts.change(WORKSPACE, id, "Add a quiz");
        Artifact changed = waitUntilEnded(id);

        assertThat(changed.state()).isEqualTo(Artifact.State.READY);
        assertThat(opened.getLast().sessionReference()).isEmpty();
        assertThat(prompts.getLast()).contains("<page>\n" + PAGE + "\n</page>", "Add a quiz");
    }

    @Test
    void writesOnePageAtATimeInAWorkspaceAndStopKeepsNoHalfPage()
    {
        CompletableFuture<AgentTurnResult> pending = new CompletableFuture<>();
        agent.add(context -> pending);
        String id = artifacts.ask(WORKSPACE, "Teach me delete files").id();
        waitUntil(() -> !turns.isEmpty());

        assertThatThrownBy(() -> artifacts.ask(WORKSPACE, "Explain snapshots"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Wait for “Teach me delete files” to finish");

        artifacts.stop(WORKSPACE, id);
        verify(turns.getFirst()).cancel();
        pending.complete(new AgentTurnResult(AgentTurnResult.State.CANCELLED, Optional.empty(), Optional.of("Cancelled"), Duration.ofSeconds(1), true));
        Artifact stopped = waitUntilEnded(id);

        assertThat(stopped.state()).isEqualTo(Artifact.State.FAILED);
        assertThat(stopped.failure()).contains("Stopped");
        assertThat(artifacts.page(WORKSPACE, id)).isEmpty();
        // The slot is free again.
        waitUntilEnded(artifacts.ask(WORKSPACE, "Explain snapshots").id());
    }

    @Test
    void aWriteThatEndsWithoutSavingFailsWithAReason()
    {
        agent.add(context -> CompletableFuture.completedFuture(completed()));
        Artifact failed = waitUntilEnded(artifacts.ask(WORKSPACE, "Teach me delete files").id());

        assertThat(failed.state()).isEqualTo(Artifact.State.FAILED);
        assertThat(failed.failure()).contains("The agent finished without saving a page");
    }

    @Test
    void nextCardsStartTheirPageOrGoAway()
    {
        agent.add(context -> {
            save(context, "{\"title\":\"Delete files\",\"html\":" + quoted(PAGE) + ",\"next\":[{\"title\":\"Equality deletes\",\"ask\":\"Explain equality deletes\"},"
                    + "{\"title\":\"Position deletes\",\"ask\":\"Explain position deletes\"}]}");
            return CompletableFuture.completedFuture(completed());
        });
        String id = artifacts.ask(WORKSPACE, "Teach me delete files").id();
        List<ArtifactNext> cards = waitUntilEnded(id).next();
        assertThat(cards).extracting(ArtifactNext::title).containsExactly("Equality deletes", "Position deletes");

        Artifact written = artifacts.writeNext(WORKSPACE, id, cards.getFirst().id());
        assertThat(written.ask()).isEqualTo("Explain equality deletes");
        waitUntilEnded(written.id());
        artifacts.dismissNext(WORKSPACE, id, cards.getLast().id());

        assertThat(store.find(WORKSPACE, id).orElseThrow().next()).extracting(ArtifactNext::state, ArtifactNext::artifactId)
                .containsExactly(tuple(ArtifactNext.State.WRITTEN, Optional.of(written.id())), tuple(ArtifactNext.State.DISMISSED, Optional.empty()));
        assertThatThrownBy(() -> artifacts.dismissNext(WORKSPACE, id, cards.getLast().id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupFailsWritesARestartCutShortAndDeletionRemovesPagesAndCheckout()
    {
        String id = artifacts.ask(WORKSPACE, "Teach me delete files").id();
        waitUntilEnded(id);
        store.save(Artifact.asked("cut", WORKSPACE, "Explain snapshots", Instant.now()));

        artifacts.settleInterrupted();
        assertThat(store.find(WORKSPACE, "cut").orElseThrow().failure()).contains("Stopped when ByteQuay closed");

        artifacts.deleteWorkspaceData(WORKSPACE);
        assertThat(artifacts.list(WORKSPACE)).isEmpty();
        assertThat(runtime.resolve("artifacts").resolve(WORKSPACE)).doesNotExist();
    }

    private static CompletableFuture<AgentTurnResult> savesThePage(AgentSessionContext context)
    {
        save(context, "{\"title\":\"Delete files\",\"html\":" + quoted(PAGE) + "}");
        return CompletableFuture.completedFuture(completed());
    }

    private static void save(AgentSessionContext context, String arguments)
    {
        assertThat(context.sessionTools().getFirst().call().apply(arguments)).startsWith("Saved");
    }

    private static String quoted(String text)
    {
        try {
            return new ObjectMapper().writeValueAsString(text);
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AgentTurnResult completed()
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("A page on delete files."), Optional.empty(), Duration.ofSeconds(1), true);
    }

    private Artifact waitUntilEnded(String id)
    {
        waitUntil(() -> store.find(WORKSPACE, id).filter(artifact -> artifact.state() != Artifact.State.WRITING).isPresent());
        return store.find(WORKSPACE, id).orElseThrow();
    }

    private static void waitUntil(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out");
            }
            Thread.onSpinWait();
        }
    }

    private void run(Path directory, String... arguments)
    {
        assertThat(git.run(directory, arguments).successful()).as(String.join(" ", arguments)).isTrue();
    }
}
