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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The answer path, driven by a scripted provider: what each question sends, what a lost session
 * costs, and what a failure leaves behind for the reader.
 */
class TestTaskDiscussionAnswers
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final TaskId TASK = TaskId.random();
    private static final String SEED = "--- conversation so far ---\n\n[agent]\nIt throws INVALID_TABLE_PROPERTY.";

    private final Map<String, TaskDiscussion> rows = new LinkedHashMap<>();
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<Optional<AgentSessionRef>> opened = new ArrayList<>();
    private final List<Runnable> held = new ArrayList<>();
    private final ScriptedDriver driver = new ScriptedDriver();
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private boolean hold;
    private boolean answerDuringList;
    private final DefaultTaskDiscussionService service = service();

    @Test
    void sendsTheWholeEnvelopeFirstAndOnlyTheQuestionOnceTheConversationIsRecorded()
    {
        store(discussion(Optional.empty(), List.of()));
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        assertThat(opened).containsExactly(Optional.empty());
        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains(SEED, "INVALID_TABLE_PROPERTY", "why per thread?");
            assertThat(prompt).contains("--- question ---");
        });
        // The answer landed with the session it ran in, so the next question can resume it.
        assertThat(rows.get("d1").sessionReference()).contains(new DiscussionSession(1, "session-1"));

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?"));

        assertThat(opened).last().isEqualTo(Optional.of(new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 1, "session-1")));
        // A resumed conversation already holds the premise, so re-sending it would duplicate.
        assertThat(prompts.getLast()).doesNotContain(SEED);
        assertThat(prompts.getLast()).contains("and on four cores?");
    }

    @Test
    void reseedsWithEveryPriorMessageWhenTheRecordedSessionIsGone()
    {
        store(discussion(Optional.of(new DiscussionSession(1, "lost-session")), List.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "The pool is sized from CPU count.", NOW, Optional.empty()))));
        driver.failResume = true;

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?"));

        // The resume is tried once, then the same renderer carries the whole conversation instead.
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getLast()).contains(SEED, "why per thread?", "The pool is sized from CPU count.", "and on four cores?");
        assertThat(rows.get("d1").messages()).hasSize(4);
        assertThat(rows.get("d1").messages().getLast().body()).isEqualTo("Answered.");
    }

    @Test
    void marksTheQuestionThatFailedAndForgetsTheSessionThatMayBeWhy()
    {
        store(discussion(Optional.of(new DiscussionSession(1, "session-1")), List.of()));
        driver.result = new AgentTurnResult(AgentTurnResult.State.FAILED, Optional.empty(),
                Optional.of("The provider stopped"), Duration.ZERO, true);

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        TaskDiscussion after = rows.get("d1");
        assertThat(after.messages()).singleElement().satisfies(message -> {
            assertThat(message.author()).isEqualTo(DiscussionMessage.Author.USER);
            assertThat(message.error()).contains("The provider stopped");
        });
        // A session that may itself be the failure is not carried into the retry.
        assertThat(after.sessionReference()).isEmpty();
    }

    @Test
    void retryRerunsTheRecordedQuestionRatherThanAppendingAnother()
    {
        store(discussion(Optional.empty(), List.of(new DiscussionMessage(
                "m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.of("The provider stopped")))));

        service.retry("w1", TASK.toString(), "d1", "m1");

        TaskDiscussion after = rows.get("d1");
        assertThat(after.messages()).hasSize(2);
        assertThat(after.messages().getFirst().id()).isEqualTo("m1");
        assertThat(after.messages().getFirst().error()).isEmpty();
        assertThat(after.messages().getLast().author()).isEqualTo(DiscussionMessage.Author.AGENT);
        assertThat(prompts.getLast()).contains("why per thread?");
    }

    @Test
    void opensEveryAnswerReadOnlyAtTheTaskWorktreeUnderTheTaskIdentity()
    {
        store(discussion(Optional.empty(), List.of()));
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        AgentSessionContext context = driver.lastContext;
        assertThat(context.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(context.taskId()).isEqualTo(TASK.toString());
        assertThat(context.worktree()).isEqualTo(worktree().canonicalWorktree());
        // Scratch is its own, so the task's provider configuration is never clobbered.
        assertThat(context.runtimeDirectory()).isNotEqualTo(worktree().canonicalWorktree());
    }

    @Test
    void refusesASecondQuestionOnTheTaskWhileOneIsAnsweringAndStoresNothingForIt()
    {
        store(discussion(Optional.empty(), List.of()));
        answered(TurnId.random(), "It throws INVALID_TABLE_PROPERTY.");
        hold = true;

        assertThat(service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?")).pending()).isTrue();

        assertThatThrownBy(() -> service.open(new OpenDiscussionCommand("w1", TASK.toString(), TurnId.random().toString(), "INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already answering");
        assertThat(rows).containsOnlyKeys("d1");
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isTrue());

        runHeld();

        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isFalse());
        assertThat(service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?")).pending()).isTrue();
    }

    @Test
    void retryAnswersAQuestionARestartOrphaned()
    {
        store(discussion(Optional.empty(), List.of(new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()))));

        service.retry("w1", TASK.toString(), "d1", "m1");

        assertThat(rows.get("d1").messages()).extracting(DiscussionMessage::author)
                .containsExactly(DiscussionMessage.Author.USER, DiscussionMessage.Author.AGENT);
        assertThat(rows.get("d1").messages().getFirst().id()).isEqualTo("m1");
    }

    @Test
    void anAnswerStoredWhileTheListIsReadLeavesItsQuestionPendingRatherThanFailed()
    {
        store(discussion(Optional.empty(), ImmutableList.of()));
        hold = true;
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));
        answerDuringList = true;

        // The rows were read with the question trailing, and the answer landed before the slot was consulted.
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> {
            assertThat(view.messages()).hasSize(1);
            assertThat(view.pending()).isTrue();
        });
        assertThat(rows.get("d1").messages()).hasSize(2);
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isFalse());
    }

    @Test
    void anotherSpellingOfTheTaskIdSharesItsSlotAndItsThreads()
    {
        String shouted = TASK.toString().toUpperCase(Locale.ROOT);
        TurnId turn = TurnId.random();
        store(discussion(Optional.empty(), ImmutableList.of()));
        answered(turn, "It throws INVALID_TABLE_PROPERTY with the joined path.");
        hold = true;
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        assertThatThrownBy(() -> service.open(new OpenDiscussionCommand("w1", shouted, turn.toString(), "the joined path", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already answering");
        runHeld();

        String id = service.open(new OpenDiscussionCommand("w1", shouted, turn.toString(), "the joined path", 1, "why?")).id();

        assertThat(rows.get(id).taskId()).isEqualTo(TASK.toString());
        assertThat(service.list("w1", shouted)).extracting(TaskDiscussionView::id).containsExactlyInAnyOrder("d1", id);
    }

    private void runHeld()
    {
        hold = false;
        List<Runnable> jobs = ImmutableList.copyOf(held);
        held.clear();
        jobs.forEach(Runnable::run);
    }

    private DefaultTaskDiscussionService service()
    {
        TaskDiscussionStore store = mock(TaskDiscussionStore.class);
        when(store.find(any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
        when(store.update(any())).thenAnswer(call -> {
            TaskDiscussion updated = call.getArgument(0, TaskDiscussion.class);
            rows.put(updated.id(), updated);
            return updated;
        });
        when(store.findByAnchor(any(), any(), any())).thenAnswer(call -> rows.values().stream()
                .filter(row -> row.taskId().equals(call.getArgument(0)) && row.anchor().turnId().equals(call.getArgument(1))
                        && row.anchor().quoteHash().equals(call.getArgument(2)))
                .findFirst());
        when(store.listByTask(any())).thenAnswer(call -> {
            List<TaskDiscussion> read = rows.values().stream().filter(row -> row.taskId().equals(call.getArgument(0))).collect(toImmutableList());
            if (answerDuringList) {
                answerDuringList = false;
                runHeld();
            }
            return read;
        });
        when(store.insertOrGet(any())).thenAnswer(call -> {
            TaskDiscussion inserted = call.getArgument(0, TaskDiscussion.class);
            Optional<TaskDiscussion> existing = rows.values().stream()
                    .filter(row -> row.taskId().equals(inserted.taskId()) && row.anchor().quoteHash().equals(inserted.anchor().quoteHash()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            store(inserted);
            return inserted;
        });
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        AgentResourceService resources = mock(AgentResourceService.class);
        when(resources.allocate(any())).thenAnswer(call -> {
            Path root = Path.of("/tmp/bytequay-discussion-scratch").resolve(call.getArgument(0).toString());
            return new TaskResources(root, root.resolve("events.jsonl"), root.resolve("blobs"), root.resolve("provider/scratch"));
        });
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(driver);
        TaskLogStore logStore = mock(TaskLogStore.class);
        when(logStore.readAfter(any(), anyLong(), anyInt())).thenAnswer(call -> events.stream()
                .filter(event -> event.sequence() > call.getArgument(1, Long.class))
                .limit(call.getArgument(2, Integer.class))
                .collect(toImmutableList()));
        return new DefaultTaskDiscussionService(store, tasks, resources, drivers, logStore, Clock.fixed(NOW, ZoneOffset.UTC), job -> {
            if (hold) {
                held.add(job);
            }
            else {
                job.run();
            }
        });
    }

    private void store(TaskDiscussion discussion)
    {
        rows.put(discussion.id(), discussion);
    }

    private void answered(TurnId turn, String answer)
    {
        events.add(event(turn, new AgentEventPayload.UserMessage("Rename the flag", ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN)));
        events.add(event(turn, new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, answer)));
    }

    private AgentEventEnvelope event(TurnId turn, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), events.size() + 1, TASK, Optional.of(turn),
                AgentEventSource.root(), NOW, payload);
    }

    private static TaskDiscussion discussion(Optional<DiscussionSession> session, List<DiscussionMessage> messages)
    {
        return new TaskDiscussion("d1", TASK.toString(),
                new DiscussionAnchor("turn-10", "INVALID_TABLE_PROPERTY", 1, DiscussionQuotes.hash("INVALID_TABLE_PROPERTY", 1)),
                SEED, "claude", Optional.empty(), session, messages, false, NOW, NOW, 0);
    }

    private static TaskWorktree worktree()
    {
        return new TaskWorktree(TASK, "w1", new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-discussions").resolve(TASK.toString()), "bytequay/task/" + TASK, "a".repeat(40));
    }

    private static AgentTask task()
    {
        return AgentTask.created(TASK, true, "Discussable task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree(),
                new AgentTaskDefaults(Optional.of("opus"), Optional.empty(), AgentPermissionPolicy.AUTO), NOW);
    }

    /** A provider that records what it was asked and answers however the test needs. */
    private final class ScriptedDriver
            implements AgentDriver
    {
        private boolean failResume;
        private AgentSessionContext lastContext;
        private AgentTurnResult result = new AgentTurnResult(
                AgentTurnResult.State.COMPLETED, Optional.of("Answered."), Optional.empty(), Duration.ZERO, true);

        @Override
        public AgentLaneId id()
        {
            return AgentLaneId.CLAUDE_CLI;
        }

        // A discussion never probes or inspects capabilities, so a fixture that pretends to
        // answer either is claiming more than this test knows.
        @Override
        public com.bytequay.agent.spi.AgentProviderStatus probe()
        {
            throw new UnsupportedOperationException("This scripted provider is never probed");
        }

        @Override
        public com.bytequay.agent.spi.AgentCapabilities capabilities()
        {
            throw new UnsupportedOperationException("This scripted provider declares no capabilities");
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            opened.add(context.sessionReference());
            lastContext = context;
            if (failResume && context.sessionReference().isPresent()) {
                throw new com.bytequay.agent.spi.AgentUnavailableException("No conversation found with that session id");
            }
            return new ScriptedSession(context);
        }

        private final class ScriptedSession
                implements AgentSession
        {
            private final AgentSessionRef reference;

            private ScriptedSession(AgentSessionContext context)
            {
                this.reference = context.sessionReference()
                        .orElseGet(() -> new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 1, "session-1"));
            }

            @Override
            public AgentSessionRef reference()
            {
                return reference;
            }

            @Override
            public RunningTurn start(AgentTurnCommand command)
            {
                prompts.add(command.message());
                return new RunningTurn()
                {
                    @Override
                    public ProviderTurnRef reference()
                    {
                        return new ProviderTurnRef(command.turnId());
                    }

                    @Override
                    public CompletionStage<AgentTurnResult> completion()
                    {
                        return CompletableFuture.completedFuture(result);
                    }

                    @Override
                    public void cancel() {}
                };
            }

            @Override
            public void close() {}
        }
    }
}
