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
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final TaskDiscussionStore store;
    private final AgentTaskService tasks;
    private final AgentResourceService resources;
    private final AgentDriverRegistry drivers;
    private final DiscussionSeed seeds;
    private final Clock clock;
    // In flight is not durable: after a restart nothing is running, and a persisted running row
    // would be a lie needing a recovery sweep. The read route projects this set instead.
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService answers = Executors.newVirtualThreadPerTaskExecutor();

    public DefaultTaskDiscussionService(
            TaskDiscussionStore store,
            AgentTaskService tasks,
            AgentResourceService resources,
            AgentDriverRegistry drivers,
            TaskLogStore logStore,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.resources = requireNonNull(resources, "resources is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.seeds = new DiscussionSeed(requireNonNull(logStore, "logStore is null"));
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public TaskDiscussionView open(OpenDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = askableTask(command.workspaceId(), command.taskId());
        String normalized = DiscussionQuotes.normalize(command.quote());
        checkQuote(normalized.length() >= DiscussionQuotes.MINIMUM_QUOTE_CHARACTERS, "Select more of the passage to discuss it");

        String hash = DiscussionQuotes.hash(command.quote(), command.occurrence());
        Optional<TaskDiscussion> existing = store.findByAnchor(command.taskId(), command.turnId(), hash);
        TaskDiscussion discussion = existing.orElseGet(() -> create(task, command, hash));
        return submit(task, discussion, command.question());
    }

    @Override
    public TaskDiscussionView ask(AskDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = askableTask(command.workspaceId(), command.taskId());
        TaskDiscussion discussion = store.find(command.discussionId())
                .filter(found -> found.taskId().equals(command.taskId()))
                .orElseThrow(() -> new AgentConflictException("This discussion no longer exists"));
        return submit(task, discussion, command.question());
    }

    @Override
    public TaskDiscussionView retry(String workspaceId, String taskId, String discussionId, String messageId)
    {
        AgentTask task = askableTask(workspaceId, taskId);
        TaskDiscussion discussion = store.find(discussionId)
                .filter(found -> found.taskId().equals(taskId))
                .orElseThrow(() -> new AgentConflictException("This discussion no longer exists"));
        List<DiscussionMessage> messages = discussion.messages();
        DiscussionMessage last = messages.isEmpty() ? null : messages.getLast();
        if (last == null || !last.id().equals(messageId) || last.error().isEmpty()) {
            throw new AgentConflictException("That question is not waiting to be retried");
        }
        return dispatch(task, discussion.update(
                discussion.resolved(),
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(messages.subList(0, messages.size() - 1))
                        .add(new DiscussionMessage(last.id(), last.author(), last.body(), last.createdAt(), Optional.empty()))
                        .build(),
                discussion.sessionReference(),
                clock.instant()));
    }

    @Override
    public TaskDiscussionView resolve(String workspaceId, String taskId, String discussionId, boolean resolved, long expectedVersion)
    {
        tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId));
        TaskDiscussion discussion = store.find(discussionId)
                .filter(found -> found.taskId().equals(taskId))
                .orElseThrow(() -> new AgentConflictException("This discussion no longer exists"));
        if (discussion.version() != expectedVersion) {
            throw new AgentConflictException("This discussion changed while you were reading it");
        }
        TaskDiscussion updated = store.update(discussion.update(
                resolved, discussion.messages(), discussion.sessionReference(), clock.instant()));
        return TaskDiscussionView.of(updated, inFlight.contains(updated.id()));
    }

    @Override
    public List<TaskDiscussionView> list(String workspaceId, String taskId)
    {
        tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId));
        return store.listByTask(taskId).stream()
                .map(discussion -> TaskDiscussionView.of(discussion, inFlight.contains(discussion.id())))
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

    private TaskDiscussion create(AgentTask task, OpenDiscussionCommand command, String hash)
    {
        if (store.listByTask(command.taskId()).size() >= MAXIMUM_DISCUSSIONS_PER_TASK) {
            throw new AgentConflictException("This task already holds the most discussions it can");
        }
        DiscussionSeed.Seeded seeded = seeds.build(task.id(), TurnId.valueOf(command.turnId()));
        checkQuote(DiscussionQuotes.occursIn(command.quote(), seeded.answer()), "That passage is not in the answer being discussed");
        Instant now = clock.instant();
        return store.insertOrGet(new TaskDiscussion(
                UUID.randomUUID().toString(),
                command.taskId(),
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

    /** Records the question, then answers it on a virtual thread so the caller is not held. */
    private TaskDiscussionView submit(AgentTask task, TaskDiscussion discussion, String question)
    {
        Instant now = clock.instant();
        return dispatch(task, discussion.update(
                discussion.resolved(),
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(discussion.messages())
                        .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.USER, question, now, Optional.empty()))
                        .build(),
                discussion.sessionReference(),
                now));
    }

    /** Stores the pending state and schedules the answer, refusing a second question in flight. */
    private TaskDiscussionView dispatch(AgentTask task, TaskDiscussion pending)
    {
        if (!inFlight.add(pending.id())) {
            throw new AgentConflictException("This discussion is still answering your last question");
        }
        TaskDiscussion asked;
        try {
            asked = store.update(pending);
        }
        catch (RuntimeException failure) {
            inFlight.remove(pending.id());
            throw failure;
        }
        String discussionId = asked.id();
        answers.execute(() -> {
            try {
                answer(task, discussionId);
            }
            finally {
                inFlight.remove(discussionId);
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
                    discussion.sessionReference().map(reference -> new AgentSessionRef(task.laneId(), reference.contractVersion(), reference.providerValue())),
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    discussion.providerModel(),
                    Optional.empty(),
                    SessionAccess.READ_ONLY,
                    Optional.empty(),
                    "");
            processMayBeRunning = true;
            session = drivers.require(task.laneId()).open(context, event -> {});
            List<DiscussionMessage> priorAnswers = resumable(discussion) ? List.of() : earlier(discussion);
            String prompt = DiscussionPrompt.render(
                    discussion.seed(), discussion.anchor().quote(), priorAnswers, lastQuestion(discussion));
            AgentTurnResult result = session.start(new AgentTurnCommand(
                    UUID.randomUUID().toString(), prompt, ImmutableList.of(), MAXIMUM_RUNTIME))
                    .completion().toCompletableFuture().join();
            AgentSessionRef reference = session.reference();
            session.close();
            session = null;
            processMayBeRunning = false;
            record(discussionId, result, reference, task);
        }
        catch (RuntimeException failure) {
            log.warn("Discussion {} could not be answered", discussionId, failure);
            fail(discussionId, failure.getMessage() == null ? "The answer could not be produced" : failure.getMessage());
        }
        finally {
            if (session != null) {
                processMayBeRunning = !closed(session, discussionId);
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

    /** A resumed conversation already holds the seed and the prior exchange; a fresh one does not. */
    private static boolean resumable(TaskDiscussion discussion)
    {
        return discussion.sessionReference().isPresent();
    }

    private static List<DiscussionMessage> earlier(TaskDiscussion discussion)
    {
        List<DiscussionMessage> messages = discussion.messages();
        return messages.isEmpty() ? List.of() : messages.subList(0, messages.size() - 1);
    }

    private static String lastQuestion(TaskDiscussion discussion)
    {
        return discussion.messages().getLast().body();
    }

    private void record(String discussionId, AgentTurnResult result, AgentSessionRef reference, AgentTask task)
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

    private static boolean closed(AgentSession session, String discussionId)
    {
        try {
            session.close();
            return true;
        }
        catch (RuntimeException failure) {
            log.warn("Could not verify the discussion provider stopped for {}", discussionId, failure);
            return false;
        }
    }

    private static void checkQuote(boolean condition, String message)
    {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
