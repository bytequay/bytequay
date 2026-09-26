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
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Runs each discussion question in its own disposable, read-only provider session.
 *
 * <p>The session is opened directly on the driver under a synthetic resource identity, so it
 * registers nothing, records no turn, holds no worktree claim and discards its events. It carries
 * the task's real identity so a process surviving an unclean shutdown stays attributable.
 */
@Service
public class DefaultTaskDiscussionService
        implements TaskDiscussionService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultTaskDiscussionService.class);
    private static final Duration MAXIMUM_RUNTIME = Duration.ofMinutes(3);
    private static final int EVENT_PAGE_SIZE = 1000;

    private final TaskDiscussionStore store;
    private final AgentTaskService tasks;
    private final AgentResourceService resources;
    private final AgentDriverRegistry drivers;
    private final TaskLogStore logStore;
    private final Clock clock;
    // One slot per task holds the ids of the discussions its one read-only session is answering. In
    // flight is not durable: after a restart nothing is running, and a persisted running row would be
    // a lie needing a recovery sweep. The read route projects this map instead.
    private final ConcurrentMap<String, Set<String>> answering = new ConcurrentHashMap<>();
    private final Executor answers;

    @Autowired
    public DefaultTaskDiscussionService(
            TaskDiscussionStore store,
            AgentTaskService tasks,
            AgentResourceService resources,
            AgentDriverRegistry drivers,
            TaskLogStore logStore,
            Clock clock)
    {
        this(store, tasks, resources, drivers, logStore, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Lets a test run the answer on the calling thread rather than waiting on a virtual one. */
    DefaultTaskDiscussionService(
            TaskDiscussionStore store,
            AgentTaskService tasks,
            AgentResourceService resources,
            AgentDriverRegistry drivers,
            TaskLogStore logStore,
            Clock clock,
            Executor answers)
    {
        this.answers = requireNonNull(answers, "answers is null");
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.resources = requireNonNull(resources, "resources is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public TaskDiscussionView open(OpenDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = askableTask(command.workspaceId(), command.taskId());
        String normalized = DiscussionQuotes.normalize(command.quote());
        checkArgument(normalized.length() >= DiscussionQuotes.MINIMUM_QUOTE_CHARACTERS, "Select more of the passage to discuss it");

        String hash = DiscussionQuotes.hash(command.quote(), command.occurrence());
        return claimed(task, slot -> {
            TaskDiscussion discussion = store.findByAnchor(task.id().toString(), command.turnId(), hash).orElseGet(() -> create(task, command, hash));
            return submit(task, discussion, command.question(), slot);
        });
    }

    @Override
    public TaskDiscussionView ask(AskDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = askableTask(command.workspaceId(), command.taskId());
        return claimed(task, slot -> submit(task, discussion(task, command.discussionId()), command.question(), slot));
    }

    @Override
    public TaskDiscussionView retry(String workspaceId, String taskId, String discussionId, String messageId)
    {
        AgentTask task = askableTask(workspaceId, taskId);
        return claimed(task, slot -> {
            TaskDiscussion discussion = discussion(task, discussionId);
            List<DiscussionMessage> messages = discussion.messages();
            DiscussionMessage last = messages.isEmpty() ? null : messages.getLast();
            // The claim refuses a question in flight, so a trailing question without an error is one a restart orphaned.
            if (last == null || !last.id().equals(messageId) || last.author() != DiscussionMessage.Author.USER) {
                throw new AgentConflictException("That question is not waiting to be retried");
            }
            return dispatch(task, discussion.update(
                    discussion.resolved(),
                    ImmutableList.<DiscussionMessage>builder()
                            .addAll(messages.subList(0, messages.size() - 1))
                            .add(new DiscussionMessage(last.id(), last.author(), last.body(), last.createdAt(), Optional.empty()))
                            .build(),
                    discussion.sessionReference(),
                    clock.instant()), slot);
        });
    }

    @Override
    public TaskDiscussionView resolve(String workspaceId, String taskId, String discussionId, boolean resolved, long expectedVersion)
    {
        AgentTask task = tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId));
        TaskDiscussion discussion = discussion(task, discussionId);
        if (discussion.version() != expectedVersion) {
            throw new AgentConflictException("This discussion changed while you were reading it");
        }
        TaskDiscussion updated = store.update(discussion.update(
                resolved, discussion.messages(), discussion.sessionReference(), clock.instant()));
        return TaskDiscussionView.of(updated, pending(updated));
    }

    @Override
    public List<TaskDiscussionView> list(String workspaceId, String taskId)
    {
        AgentTask task = tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId));
        // Also read the slot before the rows: an answer stored and released between the two would
        // otherwise show its question as neither pending nor answered, which reads as a failure.
        Set<String> before = ImmutableSet.copyOf(answering.getOrDefault(task.id().toString(), ImmutableSet.of()));
        return store.listByTask(task.id().toString()).stream()
                .map(discussion -> TaskDiscussionView.of(discussion, before.contains(discussion.id()) || pending(discussion)))
                .collect(toImmutableList());
    }

    private AgentTask askableTask(String workspaceId, String taskId)
    {
        AgentTask task = tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId));
        if (task.taskWorktree().isEmpty() || task.worktreeRemovedAt().isPresent()) {
            throw new AgentConflictException("This task's files were released, so its discussions are read-only");
        }
        return task;
    }

    private TaskDiscussion discussion(AgentTask task, String discussionId)
    {
        return store.find(discussionId)
                .filter(found -> found.taskId().equals(task.id().toString()))
                .orElseThrow(() -> new AgentConflictException("This discussion no longer exists"));
    }

    /** Runs {@code work} holding the task's slot; work that throws before an answer job starts gives the slot back. */
    private TaskDiscussionView claimed(AgentTask task, Function<Set<String>, TaskDiscussionView> work)
    {
        Set<String> slot = claim(task);
        try {
            return work.apply(slot);
        }
        catch (RuntimeException failure) {
            release(task, slot);
            throw failure;
        }
    }

    /**
     * One read-only session per task, whichever discussion it answers. Keyed by the canonical id, because a path id is parsed
     * leniently and another spelling of it must not claim a second slot.
     */
    private Set<String> claim(AgentTask task)
    {
        Set<String> slot = ConcurrentHashMap.newKeySet();
        if (answering.putIfAbsent(task.id().toString(), slot) != null) {
            throw new AgentConflictException("This task is already answering a question");
        }
        return slot;
    }

    /** Releases only this slot: two slots holding the same ids are equal sets, so the map's own remove could drop a newer claim. */
    private void release(AgentTask task, Set<String> slot)
    {
        answering.computeIfPresent(task.id().toString(), (key, current) -> current == slot ? null : current);
    }

    private boolean pending(TaskDiscussion discussion)
    {
        return answering.getOrDefault(discussion.taskId(), ImmutableSet.of()).contains(discussion.id());
    }

    private TaskDiscussion create(AgentTask task, OpenDiscussionCommand command, String hash)
    {
        if (store.listByTask(task.id().toString()).size() >= MAXIMUM_DISCUSSIONS_PER_TASK) {
            throw new AgentConflictException("This task already holds the most discussions it can");
        }
        Seeded seeded = seed(task.id(), TurnId.valueOf(command.turnId()));
        checkArgument(DiscussionQuotes.occursIn(command.quote(), seeded.answer()), "That passage is not in the answer being discussed");
        Instant now = clock.instant();
        return store.insertOrGet(new TaskDiscussion(
                UUID.randomUUID().toString(),
                task.id().toString(),
                new DiscussionAnchor(command.turnId(), command.quote().strip(), command.occurrence(), hash),
                seeded.text(),
                task.laneId().value(),
                task.defaults().model(),
                Optional.empty(),
                ImmutableList.of(),
                false,
                now,
                now,
                0));
    }

    /**
     * Renders the task's conversation through {@code upTo}, the completed turn being discussed, as the
     * starting context a discussion inherits; that turn's own final answer is the last thing in it.
     *
     * <p>Only the reader's messages and the root thread's final answers are kept. Streaming deltas,
     * tool calls, tool output, plans and activity are excluded: they are the overwhelming majority of
     * the volume and the discussion can read the repository instead. Bodies are carried verbatim as the
     * markdown they already are.
     *
     * <p>The result is snapshotted when a discussion is created and never recomputed, because the task
     * runtime log is deleted after its retention period while the thread outlives it.
     *
     * @throws AgentUnavailableException when that turn has no recorded final answer, which is the
     *         only honest response to an anchor this task's history cannot support
     */
    private Seeded seed(TaskId taskId, TurnId upTo)
    {
        StringBuilder seed = new StringBuilder("--- conversation so far ---\n");
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                boolean anchored = envelope.turnId().filter(upTo::equals).isPresent();
                if (envelope.payload() instanceof AgentEventPayload.UserMessage message) {
                    append(seed, "[user]", message.text());
                }
                else if (isRootAnswer(envelope)) {
                    String answer = ((AgentEventPayload.AssistantMessage) envelope.payload()).text();
                    append(seed, "[agent]", answer);
                    if (anchored) {
                        requireSeedBudget(seed, "This task's history is too long to discuss; start from an earlier turn");
                        return new Seeded(seed.toString().stripTrailing(), answer);
                    }
                }
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                throw new AgentUnavailableException("That turn has no recorded answer to discuss");
            }
            afterSequence = page.getLast().sequence();
        }
    }

    /** Not checkState: the seed is rendered on the request thread, and an unmapped IllegalStateException reaches the client as a bare 500. */
    private static void requireSeedBudget(StringBuilder seed, String reason)
    {
        if (seed.length() > TaskDiscussion.MAX_SEED_CHARACTERS) {
            throw new AgentConflictException(reason);
        }
    }

    /** A subagent's answer is its own; only the root thread answers for the task. */
    private static boolean isRootAnswer(AgentEventEnvelope envelope)
    {
        return envelope.source().parentProviderThreadId().isEmpty()
                && envelope.payload() instanceof AgentEventPayload.AssistantMessage message
                && message.messageKind() == MessageKind.ANSWER
                && message.phase() == MessagePhase.FINAL;
    }

    private static void append(StringBuilder seed, String label, String body)
    {
        if (!body.isBlank()) {
            seed.append('\n').append(label).append('\n').append(body.strip()).append('\n');
        }
    }

    /** Records the question, then answers it on a virtual thread so the caller is not held. */
    private TaskDiscussionView submit(AgentTask task, TaskDiscussion discussion, String question, Set<String> slot)
    {
        Instant now = clock.instant();
        return dispatch(task, discussion.update(
                discussion.resolved(),
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(discussion.messages())
                        .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.USER, question, now, Optional.empty()))
                        .build(),
                discussion.sessionReference(),
                now), slot);
    }

    /**
     * Stores the pending state and schedules the answer, which gives the task's slot back when it
     * ends. The id enters the slot before the row shows the question.
     */
    private TaskDiscussionView dispatch(AgentTask task, TaskDiscussion pending, Set<String> slot)
    {
        slot.add(pending.id());
        TaskDiscussion asked = store.update(pending);
        String discussionId = asked.id();
        answers.execute(() -> {
            try {
                answer(task, discussionId);
            }
            finally {
                release(task, slot);
            }
        });
        return TaskDiscussionView.of(asked, true);
    }

    private void answer(AgentTask task, String discussionId)
    {
        TaskDiscussion discussion = store.find(discussionId).orElse(null);
        if (discussion == null) {
            return;
        }
        try {
            run(task, discussion, discussion.sessionReference());
        }
        catch (RuntimeException failure) {
            if (discussion.sessionReference().isEmpty()) {
                log.warn("Discussion {} could not be answered", discussionId, failure);
                fail(discussionId, reasonOf(failure));
                return;
            }
            // The recorded conversation is gone. Re-seeding costs the provider's memory of what it
            // read, not the thread: the same renderer carries the premise and every prior message.
            log.info("Discussion {} lost its provider conversation; re-seeding", discussionId, failure);
            try {
                run(task, discussion, Optional.empty());
            }
            catch (RuntimeException reseeded) {
                log.warn("Discussion {} could not be answered after re-seeding", discussionId, reseeded);
                fail(discussionId, reasonOf(reseeded));
            }
        }
    }

    private static String reasonOf(RuntimeException failure)
    {
        return failure.getMessage() == null ? "The answer could not be produced" : failure.getMessage();
    }

    private void run(AgentTask task, TaskDiscussion discussion, Optional<DiscussionSession> resume)
    {
        // A resumed conversation already holds the preamble, the premise and the exchange;
        // sending them again would duplicate the whole thread in its context.
        String prompt = resume.isPresent() ? lastQuestion(discussion)
                : DiscussionPrompt.render(discussion.seed(), discussion.anchor().quote(), earlier(discussion), lastQuestion(discussion));
        Answered answered = converse(task, discussion.providerModel(), resume, prompt, MAXIMUM_RUNTIME);
        record(discussion.id(), answered.result(), answered.reference());
    }

    /** One question in a disposable read-only session on the task's worktree; approvals are denied and events discarded. */
    private Answered converse(AgentTask task, Optional<String> model, Optional<DiscussionSession> resume, String prompt, Duration limit)
    {
        TaskId scratchId = TaskId.random();
        boolean processMayBeRunning = false;
        AgentSession session = null;
        try {
            TaskResources scratch = resources.allocate(scratchId);
            AgentSessionContext context = new AgentSessionContext(
                    task.id().toString(),
                    task.taskWorktree().orElseThrow().canonicalWorktree(),
                    scratch.runtimeDirectory(),
                    scratch.providerDirectory(),
                    AgentPermissionPolicy.AUTO,
                    resume.map(reference -> new AgentSessionRef(task.laneId(), reference.contractVersion(), reference.providerValue())),
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    model,
                    Optional.empty(),
                    SessionAccess.READ_ONLY,
                    Optional.empty(),
                    "");
            processMayBeRunning = true;
            session = drivers.require(task.laneId()).open(context, event -> {});
            AgentTurnResult result = session.start(new AgentTurnCommand(
                    UUID.randomUUID().toString(), prompt, ImmutableList.of(), limit))
                    .completion().toCompletableFuture().join();
            AgentSessionRef reference = session.reference();
            session.close();
            session = null;
            processMayBeRunning = false;
            return new Answered(result, reference);
        }
        finally {
            if (session != null) {
                processMayBeRunning = !closed(session, scratchId);
            }
            if (processMayBeRunning) {
                log.warn("Retaining discussion resources because the provider may still be running for {}", scratchId);
            }
            else {
                try {
                    resources.delete(scratchId);
                }
                catch (RuntimeException failure) {
                    log.warn("Could not remove discussion resources for {}", scratchId, failure);
                }
            }
        }
    }

    private static List<DiscussionMessage> earlier(TaskDiscussion discussion)
    {
        List<DiscussionMessage> messages = discussion.messages();
        return messages.isEmpty() ? ImmutableList.of() : messages.subList(0, messages.size() - 1);
    }

    private static String lastQuestion(TaskDiscussion discussion)
    {
        return discussion.messages().getLast().body();
    }

    private void record(String discussionId, AgentTurnResult result, AgentSessionRef reference)
    {
        String answer = result.finalResponse().orElse("").strip();
        if (result.state() != AgentTurnResult.State.COMPLETED || answer.isEmpty()) {
            fail(discussionId, result.terminalReason().orElse("The answer could not be produced"));
            return;
        }
        store.find(discussionId).ifPresent(discussion -> store.update(discussion.update(
                discussion.resolved(),
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(discussion.messages())
                        .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.AGENT, answer, clock.instant(), Optional.empty()))
                        .build(),
                Optional.of(new DiscussionSession(reference.contractVersion(), reference.providerValue())),
                clock.instant())));
    }

    /** Marks the question that failed, and forgets a session that may be the reason it did. */
    private void fail(String discussionId, String reason)
    {
        store.find(discussionId).ifPresent(discussion -> {
            List<DiscussionMessage> messages = discussion.messages();
            if (messages.isEmpty() || messages.getLast().author() != DiscussionMessage.Author.USER) {
                return;
            }
            store.update(discussion.update(
                    discussion.resolved(),
                    ImmutableList.<DiscussionMessage>builder()
                            .addAll(messages.subList(0, messages.size() - 1))
                            .add(messages.getLast().failed(reason))
                            .build(),
                    Optional.empty(),
                    clock.instant()));
        });
    }

    private static boolean closed(AgentSession session, TaskId scratchId)
    {
        try {
            session.close();
            return true;
        }
        catch (RuntimeException failure) {
            log.warn("Could not verify the discussion provider stopped for {}", scratchId, failure);
            return false;
        }
    }

    /**
     * The starting context a discussion inherits, and the answer its quote must occur in. Both come
     * from one pass, because the log is read from disk and the quote check needs the same answer the
     * seed ends with.
     */
    private record Seeded(String text, String answer) {}

    private record Answered(AgentTurnResult result, AgentSessionRef reference) {}
}
