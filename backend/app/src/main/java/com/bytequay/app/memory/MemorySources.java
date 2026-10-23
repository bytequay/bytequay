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
package com.bytequay.app.memory;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.review.MavenCommands;
import com.bytequay.app.review.ReviewToolAssignment;
import com.bytequay.app.utils.ContextText;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewMessage;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Binds the source of a memory an agent remembers, so it can only rest on what ByteQuay recorded: the user's words in the turn it
 * answers, or a command that finished with exit code 0 in its task. A model never names a source itself.
 */
@Component
public class MemorySources
{
    static final int MAX_RECORDS = 4000;
    static final String NO_WORDS = "quote: this turn answers no message the user wrote, so it has no words to remember; remember the user's corrections in the turn "
            + "that answers them";
    private static final int MAX_PAGES = 3;

    private final AgentTurnStore turns;
    private final AgentEventService events;
    private final TaskLogStore logs;
    private final AgentReviewStore reviews;
    private final ReviewToolAssignment assignments;
    private final GitCommandRunner git;

    public MemorySources(AgentTurnStore turns, AgentEventService events, TaskLogStore logs, AgentReviewStore reviews, ReviewToolAssignment assignments, GitCommandRunner git)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.events = requireNonNull(events, "events is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * The user's words the turn answers: a review discussion question, or the messages the user sent in the turn, including steers.
     * A quote must be the user's own words from them; without one, the first message is quoted. ByteQuay's own requests, such as a
     * review step or a generated follow-up, carry no user words.
     */
    public MemorySource userWords(AgentTask task, TurnId turnId, Optional<String> quote)
    {
        AgentTurn turn = active(task, turnId);
        Optional<AgentReview> review = reviews.findReviewByTask(task.id().toString());
        if (review.isPresent()) {
            checkArgument(!turn.idempotencyKey().startsWith("review-step:"), NO_WORDS);
            Optional<ReviewMessage> discussion;
            try {
                discussion = assignments.resolve(review.orElseThrow().id(), Optional.of(turnId)).discussion();
            }
            catch (ReviewConflictException refusal) {
                throw new IllegalArgumentException(refusal.getMessage(), refusal);
            }
            if (discussion.isPresent()) {
                ReviewMessage message = discussion.orElseThrow();
                return MemorySource.user(words(quote, message.body()), message.createdAt(),
                        new MemorySource.UserPart(MemorySource.Where.REVIEW_MESSAGE, review.orElseThrow().id(), message.id()));
            }
        }
        else {
            checkArgument(turn.developmentOrigin().isEmpty() && !turn.idempotencyKey().startsWith("local-review:"), NO_WORDS);
        }
        List<AgentEventEnvelope> messages = userMessages(task, turnId);
        checkArgument(!messages.isEmpty(), NO_WORDS);
        AgentEventEnvelope bound = quote.map(MemorySources::collapsed)
                .map(wanted -> messages.stream().filter(message -> collapsed(text(message)).contains(wanted)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("quote: the user did not write these words in this turn; quote them exactly")))
                .orElse(messages.getFirst());
        return MemorySource.user(words(quote, text(bound)), bound.occurredAt(),
                new MemorySource.UserPart(MemorySource.Where.TASK_MESSAGE, task.id().toString(), bound.eventId().toString()));
    }

    /** The newest run of the command in the task that finished with exit code 0, at the head the task's worktree holds now. */
    public MemorySource passingCommand(AgentTask task, TurnId turnId, String command)
    {
        active(task, turnId);
        String typed = MavenCommands.typedCommand(ImmutableList.of(command));
        checkArgument(!typed.isEmpty() && typed.length() <= MemorySource.CommandPart.MAX_COMMAND_LENGTH, "command: give the command exactly as you ran it");
        AgentEventEnvelope run = logs.findLatest(task.id(), envelope -> passed(envelope, typed), MAX_RECORDS)
                .orElseThrow(() -> new IllegalArgumentException("command: no run of this command finished with exit code 0 in this task; remember only a command "
                        + "that passed, exactly as you ran it"));
        AgentEventPayload.CommandActivity activity = (AgentEventPayload.CommandActivity) run.payload();
        return MemorySource.command(ContextText.excerpt(typed, MemorySource.MAX_QUOTE_BYTES), run.occurredAt(), new MemorySource.CommandPart(task.id().toString(),
                run.turnId().orElse(turnId).toString(), typed, activity.cwd().filter(directory -> !directory.isBlank()).orElse("."), head(task)));
    }

    private AgentTurn active(AgentTask task, TurnId turnId)
    {
        return turns.findActive(task.id()).filter(turn -> turn.id().equals(turnId))
                .orElseThrow(() -> new IllegalArgumentException("This tool belongs to a stopped or replaced turn"));
    }

    private static boolean passed(AgentEventEnvelope envelope, String typed)
    {
        return envelope.payload() instanceof AgentEventPayload.CommandActivity command
                && (command.state() == ProviderAgentEvent.ActivityState.COMPLETED || command.state() == ProviderAgentEvent.ActivityState.FAILED)
                && command.exitCode().equals(Optional.of(0)) && !command.background() && MavenCommands.typedCommand(command.argv()).equals(typed);
    }

    /** The user's messages in the turn, oldest first, read back from the newest history page until the one that started the turn. */
    private List<AgentEventEnvelope> userMessages(AgentTask task, TurnId turnId)
    {
        List<AgentEventEnvelope> found = new ArrayList<>();
        OptionalLong before = OptionalLong.empty();
        for (int page = 0; page < MAX_PAGES; page++) {
            AgentHistoryPage history = events.readHistory(task.id(), before, OptionalLong.empty());
            history.events().stream()
                    .filter(envelope -> envelope.turnId().filter(turnId::equals).isPresent() && envelope.source().equals(AgentEventSource.root())
                            && envelope.payload() instanceof AgentEventPayload.UserMessage)
                    .forEach(found::add);
            boolean started = found.stream().anyMatch(envelope -> ((AgentEventPayload.UserMessage) envelope.payload()).deliveryKind()
                    != AgentEventPayload.DeliveryKind.STEER);
            if (started || history.beforeCursor().isEmpty()) {
                break;
            }
            before = history.beforeCursor();
        }
        return found.stream().sorted(Comparator.comparingLong(AgentEventEnvelope::sequence)).collect(toImmutableList());
    }

    private String head(AgentTask task)
    {
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new IllegalArgumentException("command: this task has no worktree"));
        GitCommandRunner.Result head = git.run(worktree.canonicalWorktree(), "rev-parse", "--verify", "HEAD^{commit}");
        return head.successful() && !head.truncated() ? head.output().strip() : worktree.startCommit();
    }

    private static String text(AgentEventEnvelope envelope)
    {
        return ((AgentEventPayload.UserMessage) envelope.payload()).text();
    }

    /** The quote, or the start of the message the user wrote, within a source's quote limit. */
    private static String words(Optional<String> quote, String message)
    {
        String words = quote.map(String::strip).orElseGet(() -> ContextText.excerpt(message.strip(), MemorySource.MAX_QUOTE_BYTES));
        checkArgument(!words.isBlank(), "quote: the user's words are empty");
        checkArgument(ContextText.excerpt(words, MemorySource.MAX_QUOTE_BYTES).equals(words), "quote: keep the user's words within %s bytes", MemorySource.MAX_QUOTE_BYTES);
        return words;
    }

    private static String collapsed(String text)
    {
        return text.strip().replaceAll("\\s+", " ");
    }
}
