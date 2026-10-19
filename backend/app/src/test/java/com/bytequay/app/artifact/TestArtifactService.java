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
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ReviewTool;
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
import com.bytequay.core.domain.ArtifactItem;
import com.bytequay.core.domain.ArtifactPage;
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

import static com.bytequay.core.domain.ArtifactItem.Kind.ACTIVITY;
import static com.bytequay.core.domain.ArtifactItem.Kind.FAILURE;
import static com.bytequay.core.domain.ArtifactItem.Kind.OFFER;
import static com.bytequay.core.domain.ArtifactItem.Kind.PAGE;
import static com.bytequay.core.domain.ArtifactItem.Kind.REPLY;
import static com.bytequay.core.domain.ArtifactItem.Kind.USER;
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
    private static final String PAGE_HTML = "<!doctype html><link rel=\"stylesheet\" href=\"theme.css\"><h1>Deletes on read</h1>";
    private static final ProviderAgentEventSource CHILD =
            new ProviderAgentEventSource(Optional.empty(), Optional.of("parent"), Optional.empty(), Optional.empty(), Optional.empty());

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
    private final List<AgentEventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<RunningTurn> turns = new CopyOnWriteArrayList<>();
    // What the agent does in each turn, in order; past the queue it only finishes.
    private final Queue<Function<Turn, CompletableFuture<AgentTurnResult>>> agent = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean conversationLost = new AtomicBoolean();
    private Path runtime;
    private String commit;
    private ArtifactService artifacts;

    /** What the fake agent has in a turn: its session's tools and the sink its events go to. */
    private record Turn(AgentSessionContext context, AgentEventSink events)
    {
        void say(String text)
        {
            events.emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.FINAL, text, ProviderAgentEventSource.root()));
        }

        void read(String file)
        {
            events.emit(new ProviderAgentEvent.Tool(Optional.empty(), "Read", ProviderAgentEvent.ActivityState.COMPLETED, file, ProviderAgentEventSource.root()));
        }

        String call(String name, String arguments)
        {
            return context.sessionTools().stream().filter(tool -> tool.name().equals(name)).findFirst().map(ReviewTool::call).orElseThrow().apply(arguments);
        }
    }

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
            sinks.add(call.getArgument(1));
            return session;
        });
        when(session.reference()).thenAnswer(call -> new AgentSessionRef(LANE, 1, "session-" + opened.size()));
        when(session.start(any())).thenAnswer(call -> {
            prompts.add(call.<AgentTurnCommand>getArgument(0).message());
            RunningTurn turn = mock(RunningTurn.class);
            Turn seen = new Turn(opened.getLast(), sinks.getLast());
            when(turn.completion()).thenReturn(Optional.ofNullable(agent.poll()).orElse(ignored -> completed()).apply(seen));
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
    void talksThenOffersThenMakesThePageBesideTheChat()
    {
        agent.add(turn -> {
            turn.read("IcebergPageSourceProvider.java");
            turn.read("DeleteManager.java");
            turn.events().emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.FINAL, "a child's notes", CHILD));
            turn.say("They are applied per data file at scan time. The read path or the write path?");
            return completed();
        });
        ArtifactView started = artifacts.start(WORKSPACE, "Teach me how Iceberg position deletes work");
        assertThat(started.artifact().working()).isTrue();
        assertThat(started.items()).extracting(ArtifactItem::kind).containsExactly(USER);
        String id = started.artifact().id();
        ArtifactView answered = waitUntilAnswered(id);

        assertThat(answered.items()).extracting(ArtifactItem::kind, ArtifactItem::text).containsExactly(
                tuple(USER, "Teach me how Iceberg position deletes work"),
                tuple(ACTIVITY, "DeleteManager.java · 2 steps"),
                tuple(REPLY, "They are applied per data file at scan time. The read path or the write path?"));
        AgentSessionContext first = opened.getFirst();
        assertThat(first.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(first.worktree()).isEqualTo(runtime.resolve("artifacts").resolve(WORKSPACE));
        assertThat(first.scratchWorktree()).isTrue();
        assertThat(first.sessionTools()).extracting(ReviewTool::name, ReviewTool::childCallable)
                .containsExactly(tuple("artifact_offer", false), tuple("artifact_save", false));
        assertThat(prompts.getFirst()).contains("main at commit " + commit, "Teach me how Iceberg position deletes work").doesNotContain("Earlier in this conversation");

        agent.add(turn -> {
            turn.say("Then the read path it is.");
            assertThat(turn.call("artifact_offer", "{\"title\":\"Deletes on read\",\"covers\":\"How a scan skips deleted rows.\"}")).startsWith("Offered");
            return completed();
        });
        artifacts.send(WORKSPACE, id, "The read path, please");
        ArtifactView offered = waitUntilAnswered(id);
        ArtifactItem offer = offered.items().getLast();
        assertThat(offer.kind()).isEqualTo(OFFER);
        assertThat(offer.offer().orElseThrow()).isEqualTo(new ArtifactItem.Offer("Deletes on read", "How a scan skips deleted rows.", ArtifactItem.Offer.State.SHOWN));
        // The conversation goes on in the same session rather than starting over.
        assertThat(opened.getLast().sessionReference().orElseThrow().providerValue()).isEqualTo("session-1");

        agent.add(turn -> {
            assertThat(turn.call("artifact_save", "{\"title\":\"Deletes on read\",\"html\":" + quoted(PAGE_HTML) + "}")).startsWith("Saved page p1 version 1");
            turn.say("Here is the page.");
            return completed();
        });
        artifacts.answerOffer(WORKSPACE, id, offer.id(), true);
        ArtifactView made = waitUntilAnswered(id);
        assertThat(prompts.getLast()).contains(ArtifactService.MAKE_THE_PAGE);
        assertThat(made.items().stream().filter(item -> item.kind() == OFFER).findFirst().orElseThrow().offer().orElseThrow().state())
                .isEqualTo(ArtifactItem.Offer.State.ACCEPTED);
        assertThat(made.pages()).extracting(ArtifactPage::key, ArtifactPage::version, ArtifactPage::commit).containsExactly(tuple("p1", 1, commit));
        assertThat(made.artifact().title()).isEqualTo("Deletes on read");
        assertThat(made.items()).extracting(ArtifactItem::kind).endsWith(USER, PAGE, REPLY);
        assertThat(artifacts.pageHtml(WORKSPACE, id, made.pages().getFirst().id())).contains(PAGE_HTML);

        agent.add(turn -> {
            assertThat(turn.call("artifact_save", "{\"title\":\"Deletes on read\",\"html\":" + quoted(PAGE_HTML) + ",\"page\":\"p1\"}")).startsWith("Saved page p1 version 2");
            assertThat(turn.call("artifact_save", "{\"title\":\"Nope\",\"html\":" + quoted(PAGE_HTML) + ",\"page\":\"p7\"}")).contains("there is no page p7");
            return completed();
        });
        artifacts.send(WORKSPACE, id, "Make the diagram simpler");
        ArtifactView changed = waitUntilAnswered(id);
        assertThat(changed.pages()).extracting(ArtifactPage::key, ArtifactPage::version).containsExactly(tuple("p1", 1), tuple("p1", 2));
        // No reply event came, so the turn's final answer is the reply.
        assertThat(changed.items().getLast()).extracting(ArtifactItem::kind, ArtifactItem::text).containsExactly(REPLY, "Here is what I found.");
    }

    @Test
    void aLostConversationGoesOnFreshWithItsHistoryAndPages()
    {
        agent.add(turn -> {
            turn.call("artifact_save", "{\"title\":\"Deletes on read\",\"html\":" + quoted(PAGE_HTML) + "}");
            return completed();
        });
        String id = artifacts.start(WORKSPACE, "Make a page about position deletes").artifact().id();
        waitUntilAnswered(id);
        conversationLost.set(true);

        artifacts.send(WORKSPACE, id, "Add a quiz");
        ArtifactView answered = waitUntilAnswered(id);

        assertThat(opened.getLast().sessionReference()).isEmpty();
        assertThat(prompts.getLast()).contains("Earlier in this conversation", "[user] Make a page about position deletes", "[you saved] Deletes on read · v1",
                "Your page p1, version 1", "<page>\n" + PAGE_HTML + "\n</page>", "The user's message:\nAdd a quiz");
        assertThat(answered.items().getLast().kind()).isEqualTo(REPLY);
    }

    @Test
    void answersOneConversationAtATimeInAWorkspaceAndStopKeepsWhatWasSaid()
    {
        CompletableFuture<AgentTurnResult> pending = new CompletableFuture<>();
        agent.add(turn -> {
            turn.say("Looking at the read path.");
            return pending;
        });
        String id = artifacts.start(WORKSPACE, "Teach me position deletes").artifact().id();
        waitUntil(() -> !turns.isEmpty());

        assertThatThrownBy(() -> artifacts.start(WORKSPACE, "Explain snapshots"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Wait for “Teach me position deletes” to finish");

        artifacts.stop(WORKSPACE, id);
        verify(turns.getFirst()).cancel();
        pending.complete(new AgentTurnResult(AgentTurnResult.State.CANCELLED, Optional.empty(), Optional.of("Cancelled"), Duration.ofSeconds(1), true));
        ArtifactView stopped = waitUntilAnswered(id);

        assertThat(stopped.items()).extracting(ArtifactItem::kind, ArtifactItem::text).endsWith(tuple(REPLY, "Looking at the read path."), tuple(FAILURE, "Stopped"));
        waitUntilAnswered(artifacts.start(WORKSPACE, "Explain snapshots").artifact().id());
    }

    @Test
    void aFailedTurnSaysWhyAndAnOfferCanWait()
    {
        agent.add(turn -> {
            turn.call("artifact_offer", "{\"title\":\"Deletes on read\",\"covers\":\"How a scan skips deleted rows.\"}");
            return CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.INTERRUPTED, Optional.empty(), Optional.of("Claude turn timed out"),
                    Duration.ofMinutes(20), true));
        });
        String id = artifacts.start(WORKSPACE, "Teach me position deletes").artifact().id();
        ArtifactView failed = waitUntilAnswered(id);
        assertThat(failed.items().getLast()).extracting(ArtifactItem::kind, ArtifactItem::text).containsExactly(FAILURE, "Claude turn timed out");

        ArtifactItem offer = failed.items().stream().filter(item -> item.kind() == OFFER).findFirst().orElseThrow();
        ArtifactView dismissed = artifacts.answerOffer(WORKSPACE, id, offer.id(), false);
        assertThat(dismissed.items().stream().filter(item -> item.kind() == OFFER).findFirst().orElseThrow().offer().orElseThrow().state())
                .isEqualTo(ArtifactItem.Offer.State.DISMISSED);
        assertThatThrownBy(() -> artifacts.answerOffer(WORKSPACE, id, offer.id(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dismissed.artifact().working()).isFalse();
    }

    @Test
    void startupEndsTurnsARestartCutShortAndDeletionRemovesConversationsAndCheckout()
    {
        String id = artifacts.start(WORKSPACE, "Teach me position deletes").artifact().id();
        waitUntilAnswered(id);
        store.save(Artifact.started("cut", WORKSPACE, "Explain snapshots", Instant.now()).working(true, Instant.now()));

        artifacts.settleInterrupted();
        assertThat(artifacts.view(WORKSPACE, "cut").artifact().working()).isFalse();
        assertThat(artifacts.view(WORKSPACE, "cut").items()).extracting(ArtifactItem::text).containsExactly("Stopped when ByteQuay closed");

        artifacts.deleteWorkspaceData(WORKSPACE);
        assertThat(artifacts.list(WORKSPACE)).isEmpty();
        assertThat(runtime.resolve("artifacts").resolve(WORKSPACE)).doesNotExist();
    }

    private static CompletableFuture<AgentTurnResult> completed()
    {
        return CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Here is what I found."), Optional.empty(),
                Duration.ofSeconds(1), true));
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

    private ArtifactView waitUntilAnswered(String id)
    {
        waitUntil(() -> !artifacts.view(WORKSPACE, id).artifact().working());
        return artifacts.view(WORKSPACE, id);
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
