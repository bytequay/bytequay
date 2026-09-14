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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Binds a session's tools to its accepted turn, independently of the model's arguments. */
@Component
public class ReviewToolAssignment
{
    private final AgentTurnStore turns;
    private final QueuedInputStore queue;
    private final AgentReviewStore reviews;

    public ReviewToolAssignment(AgentTurnStore turns, QueuedInputStore queue, AgentReviewStore reviews)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    public Optional<TurnId> capture(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return turns.findActive(id)
                .filter(turn -> turn.taskId().equals(id) && turn.state().active() && turn.cancellationRequestedAt().isEmpty())
                .map(AgentTurn::id);
    }

    /** Called under the same review lock that advances its steps. */
    public Binding resolve(String reviewId, Optional<TurnId> capturedTurn)
    {
        AgentReview review = reviews.findReview(reviewId)
                .filter(value -> value.state() == AgentReview.State.OPEN)
                .orElseThrow(() -> new ReviewConflictException("review: this review has ended"));
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentTurn turn = turns.findActive(taskId)
                .filter(value -> value.taskId().equals(taskId) && capturedTurn.filter(value.id()::equals).isPresent())
                .filter(value -> value.state().active() && value.cancellationRequestedAt().isEmpty())
                .orElseThrow(() -> new ReviewConflictException("turn: this tool belongs to a stopped or replaced turn"));
        List<ReviewRun> runs = reviews.listRuns(reviewId);
        if (runs.isEmpty()) {
            throw new ReviewConflictException("review: this review has no run yet");
        }
        ReviewRun latest = runs.getLast();
        if (turn.idempotencyKey().startsWith("review-step:")) {
            ReviewStep step = latest.currentStep()
                    .filter(value -> latest.active() && value.key(latest.id()).equals(turn.idempotencyKey()))
                    .orElseThrow(() -> new ReviewConflictException("step: this turn's review assignment has been replaced"));
            return new Binding(latest, Optional.of(step), Optional.empty());
        }
        Optional<ReviewMessage> discussion = reviews.listMessages(reviewId).stream()
                .filter(message -> message.author() == ReviewMessage.Author.USER)
                .filter(message -> discussionTurn(message, taskId).filter(turn.id()::equals).isPresent()
                        || (message.turnId().isEmpty() && message.queuedInputId().isEmpty() && turn.idempotencyKey().equals("review-ask:" + message.id())))
                .findFirst();
        if (discussion.isPresent()) {
            ReviewRun source = reviews.findRun(discussion.orElseThrow().runId())
                    .filter(value -> value.reviewId().equals(reviewId))
                    .orElseThrow(() -> new ReviewConflictException("discussion: its reviewed version is unavailable"));
            return new Binding(source, Optional.empty(), discussion);
        }
        boolean pendingDiscussion = turn.idempotencyKey().startsWith("review-ask:")
                || queue.listQueued(taskId).stream().anyMatch(input -> input.turnId().filter(turn.id()::equals).isPresent()
                        && input.idempotencyKey().startsWith("review-ask:"));
        if (pendingDiscussion) {
            throw new ReviewConflictException("discussion: its question is still being saved; retry this tool call");
        }
        return new Binding(latest, Optional.empty(), Optional.empty());
    }

    private Optional<TurnId> discussionTurn(ReviewMessage message, TaskId taskId)
    {
        return message.queuedInputId().map(QueuedInputId::valueOf)
                .flatMap(queue::find)
                .filter(input -> input.taskId().equals(taskId))
                .flatMap(QueuedAgentInput::turnId)
                .or(() -> message.turnId().map(TurnId::valueOf));
    }

    public record Binding(ReviewRun run, Optional<ReviewStep> step, Optional<ReviewMessage> discussion)
    {
        public Binding
        {
            requireNonNull(run, "run is null");
            requireNonNull(step, "step is null");
            requireNonNull(discussion, "discussion is null");
        }
    }
}
