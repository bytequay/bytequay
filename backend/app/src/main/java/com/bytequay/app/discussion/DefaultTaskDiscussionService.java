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
import com.bytequay.agent.core.OneShotSession;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.service.ai.BetterWordsService;
import com.bytequay.app.service.ai.BetterWordsService.ReplyVoice;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewNotFoundException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
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
import java.util.function.Supplier;

import static com.bytequay.app.discussion.DiscussionPrompt.SUMMARY_REQUEST;
import static com.bytequay.app.discussion.DiscussionPrompt.isSummaryRequest;
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
    private static final Duration MAXIMUM_BATCH_RUNTIME = Duration.ofMinutes(5);
    private static final int EVENT_PAGE_SIZE = 1000;
    private static final int MAXIMUM_REPLY_CONTEXT_CHARACTERS = 20_000;

    private final TaskDiscussionStore store;
    private final AgentTaskService tasks;
    private final AgentResourceService resources;
    private final AgentDriverRegistry drivers;
    private final TaskLogStore logStore;
    private final Clock clock;
    private final TaskPullRequestOwnership ownership;
    private final ReviewCommentReader comments;
    private final BetterWordsService writer;
    private final AgentReviewStore reviews;
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
            Clock clock,
            TaskPullRequestOwnership ownership,
            ReviewCommentReader comments,
            BetterWordsService writer,
            AgentReviewStore reviews)
    {
        this(store, tasks, resources, drivers, logStore, clock, ownership, comments, writer, reviews, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Lets a test run the answer on the calling thread rather than waiting on a virtual one. */
    DefaultTaskDiscussionService(
            TaskDiscussionStore store,
            AgentTaskService tasks,
            AgentResourceService resources,
            AgentDriverRegistry drivers,
            TaskLogStore logStore,
            Clock clock,
            TaskPullRequestOwnership ownership,
            ReviewCommentReader comments,
            BetterWordsService writer,
            AgentReviewStore reviews,
            Executor answers)
    {
        this.answers = requireNonNull(answers, "answers is null");
        this.store = requireNonNull(store, "store is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.resources = requireNonNull(resources, "resources is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.comments = requireNonNull(comments, "comments is null");
        this.writer = requireNonNull(writer, "writer is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @Override
    public TaskDiscussionView open(OpenDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        return open(askableTask(command.workspaceId(), command.taskId()), command);
    }

    private TaskDiscussionView open(AgentTask task, OpenDiscussionCommand command)
    {
        String normalized = DiscussionQuotes.normalize(command.quote());
        checkArgument(normalized.length() >= DiscussionQuotes.MINIMUM_QUOTE_CHARACTERS, "Select more of the passage to discuss it");

        String hash = DiscussionQuotes.hash(command.quote(), command.occurrence());
        return claimed(task, slot -> {
            TaskDiscussion discussion = store.findByAnchor(task.id().toString(), command.turnId(), hash).orElseGet(() -> create(task, command, hash));
            return submit(task, discussion, command.question(), slot, Optional.empty());
        });
    }

    @Override
    public TaskDiscussionView ask(AskDiscussionCommand command)
    {
        requireNonNull(command, "command is null");
        return ask(askableTask(command.workspaceId(), command.taskId()), command);
    }

    private TaskDiscussionView ask(AgentTask task, AskDiscussionCommand command)
    {
        return claimed(task, slot -> submit(task, discussion(task, command.discussionId()), command.question(), slot, Optional.empty()));
    }

    @Override
    public TaskDiscussionView retry(String workspaceId, String taskId, String discussionId, String messageId)
    {
        return retry(askableTask(workspaceId, taskId), discussionId, messageId);
    }

    private TaskDiscussionView retry(AgentTask task, String discussionId, String messageId)
    {
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
                    clock.instant()), slot, Optional.empty());
        });
    }

    @Override
    public TaskDiscussionView resolve(String workspaceId, String taskId, String discussionId, boolean resolved, long expectedVersion)
    {
        return resolve(tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId)), discussionId, resolved, expectedVersion);
    }

    private TaskDiscussionView resolve(AgentTask task, String discussionId, boolean resolved, long expectedVersion)
    {
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
        return views(tasks.getInteractive(requireNonNull(workspaceId, "workspaceId is null"), TaskId.valueOf(taskId)));
    }

    private List<TaskDiscussionView> views(AgentTask task)
    {
        // Also read the slot before the rows: an answer stored and released between the two would
        // otherwise show its question as neither pending nor answered, which reads as a failure.
        Set<String> before = ImmutableSet.copyOf(answering.getOrDefault(task.id().toString(), ImmutableSet.of()));
        return store.listByTask(task.id().toString()).stream()
                .map(discussion -> TaskDiscussionView.of(discussion, before.contains(discussion.id()) || pending(discussion)))
                .collect(toImmutableList());
    }

    @Override
    public TaskDiscussionView askComment(AskCommentCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = askableTask(command.workspaceId(), command.taskId());
        // A private thread writes nothing to GitHub, so a merged or closed pull request still takes questions.
        if (!ownership.owns(task, command.repo(), command.number(), false)) {
            throw new AgentConflictException("This pull request is not this task's");
        }
        return askComment(task, command, () -> seed(task.id(), Optional.empty()).text());
    }

    /**
     * Asks on the host task's thread for one comment of the command's pull request.
     *
     * @param premise renders what a new thread starts from, before anything is stored, so a refusal leaves nothing behind
     */
    private TaskDiscussionView askComment(AgentTask task, AskCommentCommand command, Supplier<String> premise)
    {
        return claimed(task, slot -> {
            Optional<TaskDiscussion> existing = store.findByAnchor(task.id().toString(), ReviewCommentAnchor.TURN, ReviewCommentAnchor.hash(command.key()));
            checkArgument(existing.flatMap(TaskDiscussion::comment)
                    .filter(comment -> !comment.repo().equalsIgnoreCase(command.repo()) || comment.number() != command.number())
                    .isEmpty(), "That comment is not in this pull request");
            if (command.question().isBlank() && existing.filter(thread -> !thread.messages().isEmpty()).isPresent()) {
                release(task, slot);
                return TaskDiscussionView.of(existing.orElseThrow(), false);
            }
            TaskDiscussion thread;
            Optional<String> history = Optional.empty();
            if (existing.isPresent()) {
                thread = existing.orElseThrow();
            }
            else {
                ReviewCommentAnchor comment = comments.find(command.repo(), command.number(), command.key())
                        .orElseThrow(() -> new IllegalArgumentException("That comment is not in this pull request"));
                requireCommentThreadRoom(store.listByTask(task.id().toString()));
                history = Optional.of(premise.get());
                thread = store.insertOrGet(commentThread(UUID.randomUUID().toString(), task, comment, ImmutableList.of(), clock.instant()));
            }
            return submit(task, thread, command.question().isBlank() ? SUMMARY_REQUEST : command.question(), slot, history);
        });
    }

    @Override
    public void summarize(String workspaceId, String taskId, List<ReviewCommentAnchor> comments)
    {
        List<ReviewCommentAnchor> requested = ImmutableList.copyOf(comments);
        AgentTask task = askableTask(workspaceId, taskId);
        Set<String> slot = claim(task);
        try {
            List<TaskDiscussion> existing = store.listByTask(task.id().toString());
            Set<String> covered = new HashSet<>();
            existing.forEach(discussion -> discussion.comment().ifPresent(comment -> covered.add(comment.key())));
            List<ReviewCommentAnchor> uncovered = requested.stream().filter(comment -> covered.add(comment.key())).collect(toImmutableList());
            if (uncovered.isEmpty()) {
                release(task, slot);
                return;
            }
            requireCommentThreadRoom(existing);
            // Rendered before anything is stored, so a refusal leaves no thread behind.
            String history = seed(task.id(), Optional.empty()).text();
            List<ReviewCommentAnchor> fresh = uncovered.stream()
                    .limit(MAXIMUM_COMMENT_THREADS_PER_TASK - existing.stream().filter(discussion -> discussion.comment().isPresent()).count())
                    .collect(toImmutableList());
            List<String> ids = fresh.stream().map(comment -> UUID.randomUUID().toString()).collect(toImmutableList());
            // Every id is pending before its row shows the hidden request, so no poll sees a question that is neither pending nor failed.
            slot.addAll(ids);
            Instant now = clock.instant();
            ImmutableList.Builder<TaskDiscussion> kept = ImmutableList.builder();
            for (int index = 0; index < fresh.size(); index++) {
                String id = ids.get(index);
                TaskDiscussion stored = store.insertOrGet(commentThread(id, task, fresh.get(index),
                        ImmutableList.of(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.USER, SUMMARY_REQUEST, now, Optional.empty())), now));
                if (stored.id().equals(id)) {
                    kept.add(stored);
                }
                else {
                    slot.remove(id);
                }
            }
            List<TaskDiscussion> threads = kept.build();
            if (threads.isEmpty()) {
                release(task, slot);
                return;
            }
            answers.execute(() -> {
                try {
                    summarizeBatch(task, threads, history);
                }
                finally {
                    release(task, slot);
                }
            });
        }
        catch (RuntimeException failure) {
            release(task, slot);
            throw failure;
        }
    }

    @Override
    public String draftReply(String workspaceId, String taskId, String discussionId)
    {
        return draftReply(askableTask(workspaceId, taskId), discussionId, ReplyVoice.AUTHOR);
    }

    private String draftReply(AgentTask task, String discussionId, ReplyVoice voice)
    {
        TaskDiscussion thread = discussion(task, discussionId);
        ReviewCommentAnchor comment = thread.comment()
                .orElseThrow(() -> new IllegalArgumentException("Only a private thread on a review comment can draft a reply"));
        if (thread.messages().stream().noneMatch(message -> message.author() == DiscussionMessage.Author.AGENT)) {
            throw new AgentConflictException("Ask the agent before drafting a reply");
        }
        return writer.draftReply(replyContext(comment, thread.messages()), comment.repo(), task.workspaceId(), voice);
    }

    @Override
    public List<TaskDiscussionView> listReviewThreads(String reviewId)
    {
        return views(reviewTask(requireReview(reviewId)));
    }

    @Override
    public TaskDiscussionView openReviewDiscussion(String reviewId, String turnId, String quote, int occurrence, String question)
    {
        AgentReview review = requireReview(reviewId);
        return open(askableReviewDiscussionTask(review), new OpenDiscussionCommand(review.workspaceId(), review.taskId(), turnId, quote, occurrence, question));
    }

    @Override
    public TaskDiscussionView askReviewDiscussion(String reviewId, String discussionId, String question)
    {
        AgentReview review = requireReview(reviewId);
        AskDiscussionCommand command = new AskDiscussionCommand(review.workspaceId(), review.taskId(), discussionId, question);
        AgentTask task = askableReviewDiscussionTask(review);
        TaskDiscussion discussion = discussion(task, discussionId);
        checkArgument(discussion.comment().isEmpty(), "Use the comment action to ask about a GitHub comment");
        return ask(task, command);
    }

    @Override
    public TaskDiscussionView askReviewComment(String reviewId, String key, String question)
    {
        AgentReview review = requireReview(reviewId);
        AgentTask task = askableReviewTask(review);
        // The review's own pull request; a client never names another one.
        return askComment(task, new AskCommentCommand(review.workspaceId(), review.taskId(), review.repo(), review.number().getAsInt(), key, question),
                () -> reviewPremise(review));
    }

    @Override
    public TaskDiscussionView retryReviewThread(String reviewId, String discussionId, String messageId)
    {
        AgentReview review = requireReview(reviewId);
        AgentTask task = askableReviewDiscussionTask(review);
        if (discussion(task, discussionId).comment().isPresent()) {
            askableReviewTask(review);
        }
        return retry(task, discussionId, messageId);
    }

    @Override
    public TaskDiscussionView resolveReviewThread(String reviewId, String discussionId, boolean resolved, long expectedVersion)
    {
        return resolve(reviewTask(requireReview(reviewId)), discussionId, resolved, expectedVersion);
    }

    @Override
    public String draftReviewReply(String reviewId, String discussionId)
    {
        return draftReply(askableReviewTask(requireReview(reviewId)), discussionId, ReplyVoice.REVIEWER);
    }

    private AgentReview requireReview(String reviewId)
    {
        return reviews.findReview(requireNonNull(reviewId, "reviewId is null"))
                .orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
    }

    /** Not getInteractive: a review's task is workflow-owned, and only the review routes reach it. */
    private AgentTask reviewTask(AgentReview review)
    {
        return tasks.get(TaskId.valueOf(review.taskId()));
    }

    /** A review answers while it is open, about today's pull request, from its working copy. */
    private AgentTask askableReviewTask(AgentReview review)
    {
        if (review.replay().isPresent()) {
            throw new AgentConflictException("A replay reviews past code, so it has no private threads");
        }
        if (review.number().isEmpty()) {
            throw new AgentConflictException("This review has no pull request");
        }
        return askableReviewDiscussionTask(review);
    }

    private AgentTask askableReviewDiscussionTask(AgentReview review)
    {
        AgentTask task = reviewTask(review);
        if (review.state() != AgentReview.State.OPEN || task.taskWorktree().isEmpty() || task.worktreeRemovedAt().isPresent()) {
            throw new AgentConflictException("This review has ended, so its threads are read-only");
        }
        return task;
    }

    private String reviewPremise(AgentReview review)
    {
        return ReviewPremise.render(reviews.listRuns(review.id()), reviews.listFindings(review.id()));
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
     * One read-only session per task, whichever discussion it answers; this refusal is also the watcher's busy check. Keyed by the
     * canonical id, because a path id is parsed leniently and another spelling of it must not claim a second slot.
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
        if (store.listByTask(task.id().toString()).stream().filter(discussion -> discussion.comment().isEmpty()).count() >= MAXIMUM_DISCUSSIONS_PER_TASK) {
            throw new AgentConflictException("This task already holds the most discussions it can");
        }
        Seeded seeded = seed(task.id(), Optional.of(TurnId.valueOf(command.turnId())));
        checkArgument(DiscussionQuotes.occursIn(command.quote(), seeded.answer()), "That passage is not in the answer being discussed");
        Instant now = clock.instant();
        return store.insertOrGet(new TaskDiscussion(
                UUID.randomUUID().toString(),
                task.id().toString(),
                new DiscussionAnchor(command.turnId(), command.quote().strip(), command.occurrence(), hash),
                Optional.empty(),
                Optional.of(seeded.text()),
                task.laneId().value(),
                task.defaults().model(),
                Optional.empty(),
                ImmutableList.of(),
                false,
                now,
                now,
                0));
    }

    private static void requireCommentThreadRoom(List<TaskDiscussion> existing)
    {
        if (existing.stream().filter(discussion -> discussion.comment().isPresent()).count() >= MAXIMUM_COMMENT_THREADS_PER_TASK) {
            throw new AgentConflictException("This task already holds the most private threads it can");
        }
    }

    private static TaskDiscussion commentThread(String id, AgentTask task, ReviewCommentAnchor comment, List<DiscussionMessage> messages, Instant now)
    {
        return new TaskDiscussion(id, task.id().toString(), comment.anchor(), Optional.of(comment), Optional.empty(), task.laneId().value(), task.defaults().model(),
                Optional.empty(), messages, false, now, now, 0);
    }

    /**
     * Renders the task's conversation as the starting context a discussion inherits: through
     * {@code upTo}, the completed turn a passage discussion quotes, or through the latest root final
     * answer when empty. That answer is the last thing in it.
     *
     * <p>Only the reader's messages and the root thread's final answers are kept. Streaming deltas,
     * tool calls, tool output, plans and activity are excluded: they are the overwhelming majority of
     * the volume and the discussion can read the repository instead. Bodies are carried verbatim as the
     * markdown they already are.
     *
     * <p>A passage discussion snapshots the result when it is created, because the task runtime log is
     * deleted after its retention period while the thread outlives it. A comment thread renders it again
     * for every run without a session, which asking allows only while the worktree, and so the log, lives.
     *
     * @throws AgentUnavailableException when that turn, or the task, has no recorded final answer, which
     *         is the only honest response to a premise this task's history cannot support
     */
    private Seeded seed(TaskId taskId, Optional<TurnId> upTo)
    {
        StringBuilder seed = new StringBuilder("--- conversation so far ---\n");
        int latestLength = 0;
        String latestAnswer = null;
        long afterSequence = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, afterSequence, EVENT_PAGE_SIZE);
            for (AgentEventEnvelope envelope : page) {
                if (envelope.payload() instanceof AgentEventPayload.UserMessage message) {
                    append(seed, "[user]", message.text());
                }
                else if (envelope.rootAnswer().isPresent()) {
                    String answer = envelope.rootAnswer().orElseThrow();
                    append(seed, "[agent]", answer);
                    if (upTo.isEmpty()) {
                        // The history only grows, so a cut that is already over budget stays over it.
                        requireSeedBudget(seed, "This task's history is too long to ask about");
                        latestLength = seed.length();
                        latestAnswer = answer;
                    }
                    else if (envelope.turnId().filter(upTo.orElseThrow()::equals).isPresent()) {
                        requireSeedBudget(seed, "This task's history is too long to discuss; start from an earlier turn");
                        return new Seeded(seed.toString().stripTrailing(), answer);
                    }
                }
            }
            if (page.size() < EVENT_PAGE_SIZE) {
                break;
            }
            afterSequence = page.getLast().sequence();
        }
        if (upTo.isPresent()) {
            throw new AgentUnavailableException("That turn has no recorded answer to discuss");
        }
        if (latestAnswer == null) {
            throw new AgentUnavailableException("Waiting for the agent's first answer");
        }
        seed.setLength(latestLength);
        return new Seeded(seed.toString().stripTrailing(), latestAnswer);
    }

    /** Not checkState: the history is rendered on the request thread too, and an unmapped IllegalStateException reaches the client as a bare 500. */
    private static void requireSeedBudget(StringBuilder seed, String reason)
    {
        if (seed.length() > TaskDiscussion.MAX_SEED_CHARACTERS) {
            throw new AgentConflictException(reason);
        }
    }

    private static void append(StringBuilder seed, String label, String body)
    {
        if (!body.isBlank()) {
            seed.append('\n').append(label).append('\n').append(body.strip()).append('\n');
        }
    }

    /** Records the question, then answers it on a virtual thread so the caller is not held. */
    private TaskDiscussionView submit(AgentTask task, TaskDiscussion discussion, String question, Set<String> slot, Optional<String> history)
    {
        Instant now = clock.instant();
        return dispatch(task, discussion.update(
                discussion.resolved(),
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(discussion.messages())
                        .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.USER, question, now, Optional.empty()))
                        .build(),
                discussion.sessionReference(),
                now), slot, history);
    }

    /**
     * Stores the pending state and schedules the answer, which gives the task's slot back when it
     * ends. The id enters the slot before the row shows the question.
     */
    private TaskDiscussionView dispatch(AgentTask task, TaskDiscussion pending, Set<String> slot, Optional<String> history)
    {
        slot.add(pending.id());
        TaskDiscussion asked = store.update(pending);
        String discussionId = asked.id();
        answers.execute(() -> {
            try {
                answer(task, discussionId, history);
            }
            finally {
                release(task, slot);
            }
        });
        return TaskDiscussionView.of(asked, true);
    }

    private void answer(AgentTask task, String discussionId, Optional<String> history)
    {
        TaskDiscussion discussion = store.find(discussionId).orElse(null);
        if (discussion == null) {
            return;
        }
        try {
            run(task, discussion, discussion.sessionReference(), history);
        }
        catch (RuntimeException failure) {
            if (discussion.sessionReference().isEmpty()) {
                log.warn("Discussion {} could not be answered", discussionId, failure);
                fail(discussionId, reasonOf(failure));
                return;
            }
            // The recorded conversation is gone. Re-seeding costs the provider's memory of what it
            // read, not the thread: the context tool still holds the premise and every prior message.
            log.info("Discussion {} lost its provider conversation; re-seeding", discussionId, failure);
            try {
                run(task, discussion, Optional.empty(), Optional.empty());
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

    /**
     * Answers the discussion's last question.
     *
     * @param history the task history the caller already rendered for a run without a session
     */
    private void run(AgentTask task, TaskDiscussion discussion, Optional<DiscussionSession> resume, Optional<String> history)
    {
        // A resumed conversation already holds the preamble, the premise and the exchange;
        // sending them again would duplicate the whole thread in its context.
        Optional<AgentReview> review = discussion.comment().flatMap(ignored -> reviews.findReviewByTask(task.id().toString()));
        List<DiscussionMessage> earlier = earlier(discussion);
        String prompt = resume.isPresent() ? lastQuestion(discussion) : discussion.comment()
                .map(ignored -> DiscussionPrompt.renderComments(review.isPresent(), 1, !earlier.isEmpty(), lastQuestion(discussion)))
                .orElseGet(() -> DiscussionPrompt.render(discussion.anchor().quote(), !earlier.isEmpty(), lastQuestion(discussion)));
        String source = discussion.comment().map(comment -> SavedDiscussionContext.comments(ImmutableList.of(comment.snapshot())))
                .orElseGet(() -> JsonNodeFactory.instance.objectNode().put("passage", discussion.anchor().quote()).toString());
        DiscussionContextBridge context = SavedDiscussionContext.bridge(
                () -> discussion.seed().or(() -> history).orElseGet(() -> review.map(this::reviewPremise).orElseGet(() -> seed(task.id(), Optional.empty()).text())),
                source, earlier);
        Answered answered = converse(task, discussion.providerModel(), resume, prompt, context, MAXIMUM_RUNTIME);
        record(discussion.id(), answered.result(), answered.reference());
    }

    /** One question in a disposable read-only session on the task's worktree; approvals are denied and events discarded. */
    private Answered converse(AgentTask task, Optional<String> model, Optional<DiscussionSession> resume, String prompt, DiscussionContextBridge context, Duration limit)
    {
        drivers.checkWorkspaceAccess(task.laneId(), task.workspaceId());
        OneShotSession.Answer answer = OneShotSession.run(resources, drivers.require(task.laneId()), (scratchId, scratch) -> new AgentSessionContext(
                        task.id().toString(), task.taskWorktree().orElseThrow().canonicalWorktree(), scratch.runtimeDirectory(), scratch.providerDirectory(),
                        AgentPermissionPolicy.AUTO, resume.map(reference -> new AgentSessionRef(task.laneId(), reference.contractVersion(), reference.providerValue())),
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                        model, Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(), "", Optional.of(context)),
                prompt, limit, turn -> {});
        return new Answered(answer.result(), answer.reference());
    }

    /**
     * One session over every new comment; each thread then holds its own verdict and no session,
     * because a provider conversation shared by several threads would interleave their follow-ups.
     */
    private void summarizeBatch(AgentTask task, List<TaskDiscussion> threads, String history)
    {
        List<String> verdicts;
        try {
            String prompt = DiscussionPrompt.renderComments(false, threads.size(), false, DiscussionPrompt.summaryRequest(threads.size()));
            DiscussionContextBridge context = SavedDiscussionContext.bridge(() -> history,
                    SavedDiscussionContext.comments(threads.stream().map(thread -> thread.comment().orElseThrow().snapshot()).collect(toImmutableList())), ImmutableList.of());
            AgentTurnResult result = converse(task, task.defaults().model(), Optional.empty(), prompt, context, MAXIMUM_BATCH_RUNTIME).result();
            String answer = result.finalResponse().orElse("").strip();
            if (result.state() != AgentTurnResult.State.COMPLETED || answer.isEmpty()) {
                threads.forEach(thread -> fail(thread.id(), result.terminalReason().orElse("The answer could not be produced")));
                return;
            }
            verdicts = CommentSummary.parse(answer, threads.size());
        }
        catch (RuntimeException failure) {
            log.warn("Review comments on task {} could not be summarized", task.id(), failure);
            threads.forEach(thread -> fail(thread.id(), reasonOf(failure)));
            return;
        }
        for (int index = 0; index < threads.size(); index++) {
            String id = threads.get(index).id();
            String verdict = verdicts.get(index);
            // One thread's conflict or storage error must not strand the verdicts after it.
            try {
                store.find(id).ifPresent(discussion -> store.update(discussion.update(
                        discussion.resolved(),
                        ImmutableList.<DiscussionMessage>builder()
                                .addAll(discussion.messages())
                                .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.AGENT, verdict, clock.instant(), Optional.empty()))
                                .build(),
                        Optional.empty(),
                        clock.instant())));
            }
            catch (RuntimeException failure) {
                log.warn("The summary for discussion {} could not be stored", id, failure);
                fail(id, reasonOf(failure));
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
        store.find(discussionId).ifPresent(discussion -> {
            String body = answer;
            if (discussion.comment().isPresent() && isSummaryRequest(discussion.messages().getLast())) {
                try {
                    body = CommentSummary.parse(answer, 1).getFirst();
                }
                catch (IllegalArgumentException invalid) {
                    fail(discussionId, invalid.getMessage());
                    return;
                }
            }
            store.update(discussion.update(
                    discussion.resolved(),
                    ImmutableList.<DiscussionMessage>builder()
                            .addAll(discussion.messages())
                            .add(new DiscussionMessage(UUID.randomUUID().toString(), DiscussionMessage.Author.AGENT, body, clock.instant(), Optional.empty()))
                            .build(),
                    Optional.of(new DiscussionSession(reference.contractVersion(), reference.providerValue())),
                    clock.instant()));
        });
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

    /**
     * The comment and the visible thread as JSON data for the writing assistant, dropping the oldest
     * messages until it fits. The hidden summary request is left out; a summary goes in as its JSON text.
     */
    private static String replyContext(ReviewCommentAnchor comment, List<DiscussionMessage> messages)
    {
        List<DiscussionMessage> visible = messages.stream().filter(message -> !isSummaryRequest(message)).collect(toImmutableList());
        for (int first = 0; ; first++) {
            ObjectNode context = JsonNodeFactory.instance.objectNode();
            context.putRawValue("comment", new RawValue(comment.snapshot()));
            ArrayNode thread = context.putArray("thread");
            visible.subList(first, visible.size()).forEach(message -> thread.addObject()
                    .put("from", message.author() == DiscussionMessage.Author.USER ? "you" : "agent")
                    .put("text", message.body()));
            String json = context.toString();
            if (json.length() <= MAXIMUM_REPLY_CONTEXT_CHARACTERS || first == visible.size()) {
                return json;
            }
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
