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
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
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
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
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
import com.bytequay.core.domain.ArtifactOffer;
import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
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
    private static final String QUESTION_INPUT = "{\"questions\":[{\"question\":\"Which path?\",\"header\":\"Path\",\"multiSelect\":false,"
            + "\"options\":[{\"label\":\"Read path\",\"description\":\"How a scan skips rows\"},{\"label\":\"Write path\",\"description\":\"How deletes are written\"}]}]}";
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

        /** A finished step as Claude reports one: its output as the summary, and a description only when the agent gave one. */
        void step(String name, String output, Optional<String> description)
        {
            events.emit(new ProviderAgentEvent.Tool(Optional.empty(), name, ProviderAgentEvent.ActivityState.COMPLETED, output, ProviderAgentEventSource.root(), Optional.empty(),
                    description));
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
            turn.step("Grep", "74:import io.trino.plugin.iceberg.delete.DeleteManager;", Optional.of("Find where deletes are read"));
            turn.step("Read", "public class DeleteManager", Optional.empty());
            turn.events().emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.FINAL, "a child's notes", CHILD));
            turn.events().emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, "They are", ProviderAgentEventSource.root()));
            turn.say("They are applied per data file at scan time. The read path or the write path?");
            return completed();
        });
        ArtifactView started = artifacts.start(WORKSPACE, "Teach me how Iceberg position deletes work");
        assertThat(started.artifact().working()).isTrue();
        String id = started.artifact().id();
        ArtifactView answered = waitUntilAnswered(id);

        // The turn reads as a task's turn does; streamed pieces stay out, and the reply event makes the final answer redundant.
        assertThat(transcript(id)).containsExactly(
                "user: Teach me how Iceberg position deletes work",
                "turn_started: Turn started",
                "tool Grep completed",
                "tool Read completed",
                "child answer: a child's notes",
                "answer: They are applied per data file at scan time. The read path or the write path?",
                "turn_completed: Turn completed");
        List<AgentEventEnvelope> events = artifacts.events(WORKSPACE, id, 0);
        assertThat(events).extracting(AgentEventEnvelope::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(events).extracting(event -> event.taskId().toString()).containsOnly(id);
        assertThat(events).extracting(event -> event.turnId().orElseThrow()).containsOnly(events.getFirst().turnId().orElseThrow());
        assertThat(artifacts.events(WORKSPACE, id, 5)).extracting(AgentEventEnvelope::sequence).containsExactly(6L, 7L);
        AgentSessionContext first = opened.getFirst();
        assertThat(first.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(first.worktree()).isEqualTo(runtime.resolve("artifacts").resolve(WORKSPACE));
        assertThat(first.scratchWorktree()).isTrue();
        assertThat(first.sessionTools()).extracting(ReviewTool::name, ReviewTool::childCallable)
                .containsExactly(tuple("artifact_offer", false), tuple("artifact_save", false));
        assertThat(prompts.getFirst()).contains("main at commit " + commit, "Teach me how Iceberg position deletes work").doesNotContain("Earlier in this conversation");
        assertThat(answered.offers()).isEmpty();

        agent.add(turn -> {
            turn.say("Then the read path it is.");
            assertThat(turn.call("artifact_offer", "{\"title\":\"Deletes on read\",\"covers\":\"How a scan skips deleted rows.\"}")).startsWith("Offered");
            return completed();
        });
        artifacts.send(WORKSPACE, id, "The read path, please");
        ArtifactView offered = waitUntilAnswered(id);
        ArtifactOffer offer = offered.offers().getFirst();
        // The offer belongs to the turn that made it, so it shows at the end of that turn.
        assertThat(offer).extracting(ArtifactOffer::title, ArtifactOffer::covers, ArtifactOffer::state, ArtifactOffer::turnId)
                .containsExactly("Deletes on read", "How a scan skips deleted rows.", ArtifactOffer.State.SHOWN, lastTurn(id));
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
        assertThat(made.offers()).extracting(ArtifactOffer::state).containsExactly(ArtifactOffer.State.ACCEPTED);
        assertThat(made.pages()).extracting(ArtifactPage::key, ArtifactPage::version, ArtifactPage::commit, ArtifactPage::turnId)
                .containsExactly(tuple("p1", 1, commit, Optional.of(lastTurn(id))));
        assertThat(made.artifact().title()).isEqualTo("Deletes on read");
        assertThat(transcript(id)).endsWith("user: Make the page.", "turn_started: Turn started", "answer: Here is the page.", "turn_completed: Turn completed");
        assertThat(artifacts.pageHtml(WORKSPACE, id, made.pages().getFirst().id())).contains(PAGE_HTML);

        agent.add(turn -> {
            assertThat(turn.call("artifact_save", "{\"title\":\"Deletes on read\",\"html\":" + quoted(PAGE_HTML) + ",\"page\":\"p1\"}")).startsWith("Saved page p1 version 2");
            assertThat(turn.call("artifact_save", "{\"title\":\"Nope\",\"html\":" + quoted(PAGE_HTML) + ",\"page\":\"p7\"}")).contains("there is no page p7");
            return completed();
        });
        artifacts.send(WORKSPACE, id, "Make the diagram simpler");
        ArtifactView changed = waitUntilAnswered(id);
        assertThat(changed.pages()).extracting(ArtifactPage::key, ArtifactPage::version).containsExactly(tuple("p1", 1), tuple("p1", 2));
        // Two versions of one page are one page in the list.
        assertThat(artifacts.list(WORKSPACE)).extracting(ArtifactRow::pages).containsExactly(1);
        // No reply event came, so the turn's final answer is the reply.
        assertThat(transcript(id)).endsWith("answer: Here is what I found.", "turn_completed: Turn completed");
    }

    @Test
    void asksWithATasksQuestionCardAndClosesAnOpenOneWithItsTurn()
    {
        CompletableFuture<ProviderApprovalDecision> answered = new CompletableFuture<>();
        agent.add(turn -> {
            assertThat(turn.context().asksUser()).isTrue();
            // A read-only session grants nothing else, so any other request is refused at once.
            assertThat(turn.context().approvals().request(approval("Bash", "{\"command\":\"rm -rf build\"}"), Duration.ofMinutes(1)).toCompletableFuture().join().decision())
                    .isEqualTo(ProviderApprovalDecision.Decision.DENY);
            return turn.context().approvals().request(approval("AskUserQuestion", QUESTION_INPUT), Duration.ofMinutes(1)).toCompletableFuture()
                    .thenApply(decision -> {
                        answered.complete(decision);
                        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Then the read path."), Optional.empty(), Duration.ofSeconds(1), true);
                    });
        });
        String id = artifacts.start(WORKSPACE, "Teach me position deletes").artifact().id();
        waitUntil(() -> !artifacts.view(WORKSPACE, id).questions().isEmpty());
        String question = artifacts.view(WORKSPACE, id).questions().getFirst();

        // The question is the approval a task records for it, so the window draws the same card.
        AgentEventPayload.ApprovalRequested requested = (AgentEventPayload.ApprovalRequested) artifacts.events(WORKSPACE, id, 0).getLast().payload();
        assertThat(requested.approvalId()).isEqualTo(question);
        assertThat(requested.scope()).containsExactly("AskUserQuestion");
        assertThat(requested.detail()).isEqualTo(QUESTION_INPUT);
        assertThat(requested.actionable()).isTrue();

        artifacts.answerQuestion(WORKSPACE, id, question, true, ImmutableMap.of("Which path?", "Read path"));
        assertThat(answered.join()).extracting(ProviderApprovalDecision::decision, ProviderApprovalDecision::answers)
                .containsExactly(ProviderApprovalDecision.Decision.ALLOW, ImmutableMap.of("Which path?", "Read path"));
        waitUntilAnswered(id);
        assertThat(transcript(id)).endsWith("approval_requested", "approval_resolved", "answer: Then the read path.", "turn_completed: Turn completed");
        assertThat(artifacts.view(WORKSPACE, id).questions()).isEmpty();
        assertThatThrownBy(() -> artifacts.answerQuestion(WORKSPACE, id, question, true, ImmutableMap.of())).isInstanceOf(IllegalArgumentException.class);

        agent.add(turn -> turn.context().approvals().request(approval("AskUserQuestion", QUESTION_INPUT), Duration.ofMinutes(1)).toCompletableFuture()
                .thenApply(decision -> new AgentTurnResult(AgentTurnResult.State.CANCELLED, Optional.empty(), Optional.of("Cancelled"), Duration.ofSeconds(1), true)));
        artifacts.send(WORKSPACE, id, "And the write path?");
        waitUntil(() -> !artifacts.view(WORKSPACE, id).questions().isEmpty());
        artifacts.stop(WORKSPACE, id);
        waitUntilAnswered(id);
        // Stop ends the wait at once, and the open question is closed with its turn.
        List<AgentEventEnvelope> events = artifacts.events(WORKSPACE, id, 0);
        assertThat(events.get(events.size() - 2).payload()).isInstanceOfSatisfying(AgentEventPayload.ApprovalResolved.class,
                resolved -> assertThat(resolved.outcome()).isEqualTo(AgentEventPayload.ApprovalOutcome.CANCELLED));
        assertThat(transcript(id)).last().isEqualTo("interrupted: Stopped");
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
        waitUntilAnswered(id);

        assertThat(opened.getLast().sessionReference()).isEmpty();
        assertThat(prompts.getLast()).contains("Earlier in this conversation", "[user] Make a page about position deletes", "[you] Here is what I found.",
                "Your page p1, version 1", "<page>\n" + PAGE_HTML + "\n</page>", "The user's message:\nAdd a quiz").doesNotContain("[user] Add a quiz");
        assertThat(transcript(id)).last().isEqualTo("turn_completed: Turn completed");
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
        waitUntilAnswered(id);

        // A stopped turn ends as a task's interrupted turn does.
        assertThat(transcript(id)).endsWith("answer: Looking at the read path.", "interrupted: Stopped");
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
        assertThat(transcript(id)).endsWith("error: Claude turn timed out", "turn_completed: Turn failed: Claude turn timed out");

        ArtifactOffer offer = failed.offers().getFirst();
        ArtifactView dismissed = artifacts.answerOffer(WORKSPACE, id, offer.id(), false);
        assertThat(dismissed.offers()).extracting(ArtifactOffer::state).containsExactly(ArtifactOffer.State.DISMISSED);
        assertThatThrownBy(() -> artifacts.answerOffer(WORKSPACE, id, offer.id(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dismissed.artifact().working()).isFalse();
    }

    @Test
    void startupEndsTurnsARestartCutShortAndDeletionRemovesConversationsAndCheckout()
    {
        String id = artifacts.start(WORKSPACE, "Teach me position deletes").artifact().id();
        ArtifactView answered = waitUntilAnswered(id);
        String turn = lastTurn(id);
        // As if ByteQuay closed while the turn was still answering.
        store.save(answered.artifact().working(true, Instant.now()));
        // Conversation ids are UUIDs, as a task's are, since its events carry it as their task id.
        String cut = UUID.randomUUID().toString();
        store.save(Artifact.started(cut, WORKSPACE, "Explain snapshots", Instant.now()).working(true, Instant.now()));

        artifacts.settleInterrupted();
        assertThat(artifacts.view(WORKSPACE, id).artifact().working()).isFalse();
        assertThat(transcript(id)).last().isEqualTo("interrupted: Stopped when ByteQuay closed");
        assertThat(lastTurn(id)).isEqualTo(turn);
        assertThat(artifacts.view(WORKSPACE, cut).artifact().working()).isFalse();
        assertThat(transcript(cut)).containsExactly("interrupted: Stopped when ByteQuay closed");

        artifacts.deleteWorkspaceData(WORKSPACE);
        assertThat(artifacts.list(WORKSPACE)).isEmpty();
        assertThat(runtime.resolve("artifacts").resolve(WORKSPACE)).doesNotExist();
    }

    /** The conversation's events as short lines, in order: what its transcript shows. */
    private List<String> transcript(String id)
    {
        return artifacts.events(WORKSPACE, id, 0).stream()
                .map(event -> (event.source().parentProviderThreadId().isPresent() ? "child " : "") + switch (event.payload()) {
                    case AgentEventPayload.UserMessage message -> "user: " + message.text();
                    case AgentEventPayload.AssistantMessage message -> message.messageKind().name().toLowerCase(Locale.ROOT) + ": " + message.text();
                    case AgentEventPayload.ToolActivity tool -> "tool " + tool.name() + " " + tool.state().name().toLowerCase(Locale.ROOT);
                    case AgentEventPayload.Lifecycle lifecycle -> lifecycle.phase().name().toLowerCase(Locale.ROOT) + ": " + lifecycle.message();
                    case AgentEventPayload.Error error -> "error: " + error.message();
                    default -> event.type().name().toLowerCase(Locale.ROOT);
                })
                .collect(toImmutableList());
    }

    private static ProviderApprovalRequest approval(String tool, String input)
    {
        return new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Allow Claude " + tool, input, ImmutableList.of(tool),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false, ProviderAgentEventSource.root(), Instant.now());
    }

    private String lastTurn(String id)
    {
        return artifacts.events(WORKSPACE, id, 0).getLast().turnId().orElseThrow().toString();
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
