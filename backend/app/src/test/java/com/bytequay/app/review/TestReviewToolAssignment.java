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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static com.bytequay.core.review.ReviewStep.Kind.INVESTIGATE;
import static com.bytequay.core.review.ReviewStep.Kind.VALIDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReviewToolAssignment
{
    private static final String REVIEW_ID = "review";
    private static final TaskId TASK_ID = TaskId.valueOf("e4469e62-6122-4c61-b279-459a6ad35f20");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(
            Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of());

    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final QueuedInputStore queue = mock(QueuedInputStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final ReviewToolAssignment assignments = new ReviewToolAssignment(turns, queue, reviews);
    private final AgentReview review = pullRequestReview(REVIEW_ID, "workspace", "acme/widgets", 1, TASK_ID.toString(), NOW);
    private ReviewRun run;

    @BeforeEach
    void setUp()
    {
        run = run("run-1", step(1, INVESTIGATE, false));
        when(reviews.findReview(REVIEW_ID)).thenReturn(Optional.of(review));
        when(reviews.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run));
        when(reviews.listMessages(REVIEW_ID)).thenReturn(ImmutableList.of());
        when(queue.listQueued(TASK_ID)).thenReturn(ImmutableList.of());
    }

    @Test
    void capturesOnlyTheCurrentUncancelledTurn()
    {
        assertThat(assignments.capture(TASK_ID.toString())).isEmpty();
        AgentTurn turn = activate("review-step:run-1:1");
        assertThat(assignments.capture(TASK_ID.toString())).contains(turn.id());
        when(turns.findActive(TASK_ID)).thenReturn(Optional.of(turn.requestCancellation(NOW)));
        assertThat(assignments.capture(TASK_ID.toString())).isEmpty();
    }

    @Test
    void acceptedTurnBindsBeforeTheRunHasRecordedItsTurnId()
    {
        AgentTurn turn = activate("review-step:run-1:1");

        ReviewToolAssignment.Binding bound = assignments.resolve(REVIEW_ID, Optional.of(turn.id()));

        assertThat(bound.run()).isSameAs(run);
        assertThat(bound.step()).contains(step(1, INVESTIGATE, false));
        assertThat(bound.discussion()).isEmpty();
    }

    @Test
    void stoppedCancelledOrReplacedTurnCannotUseAnOldClosure()
    {
        AgentTurn original = activate("review-step:run-1:1");
        Optional<TurnId> captured = assignments.capture(TASK_ID.toString());
        activate("review-step:run-1:1");
        assertStaleTurn(captured);

        when(turns.findActive(TASK_ID)).thenReturn(Optional.of(original.requestCancellation(NOW)));
        assertStaleTurn(captured);

        AgentTurn completed = original.markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        when(turns.findActive(TASK_ID)).thenReturn(Optional.of(completed));
        assertStaleTurn(captured);

        when(turns.findActive(TASK_ID)).thenReturn(Optional.empty());
        assertStaleTurn(captured);
    }

    @Test
    void anUnboundBridgeCannotAcquireALaterTurnsPrivileges()
    {
        Optional<TurnId> captured = assignments.capture(TASK_ID.toString());
        activate("review-step:run-1:1");
        assertStaleTurn(captured);
    }

    @Test
    void oldStepAndOldRepairAttemptAreRejectedEvenWhileTheTurnIsActive()
    {
        AgentTurn investigation = activate("review-step:run-1:1");
        replaceRun(run.withStep(step(2, VALIDATE, false), NOW));
        assertStaleStep(investigation);

        AgentTurn validation = activate("review-step:run-1:2");
        replaceRun(run.replaceCurrentStep(step(2, VALIDATE, true), NOW));
        assertStaleStep(validation);

        AgentTurn repair = activate("review-step:run-1:2:repair");
        assertThat(assignments.resolve(REVIEW_ID, Optional.of(repair.id())).step()).contains(step(2, VALIDATE, true));
    }

    @Test
    void anOlderRunOrEndedRunCannotReceiveWorkflowReports()
    {
        AgentTurn original = activate("review-step:run-1:1");
        ReviewRun next = run("run-2", step(1, INVESTIGATE, false));
        when(reviews.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run, next));
        assertStaleStep(original);

        replaceRun(run.ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW));
        assertStaleStep(original);
    }

    @Test
    void directDiscussionRetainsItsSourceRunAndTarget()
    {
        AgentTurn question = activate("review-ask:message-1");
        ReviewRun earlier = run("earlier", step(1, INVESTIGATE, false));
        ReviewMessage message = question("earlier", Optional.of(question.id().toString()), Optional.empty());
        when(reviews.listMessages(REVIEW_ID)).thenReturn(ImmutableList.of(message));
        when(reviews.findRun("earlier")).thenReturn(Optional.of(earlier));

        ReviewToolAssignment.Binding bound = assignments.resolve(REVIEW_ID, Optional.of(question.id()));

        assertThat(bound.run()).isSameAs(earlier);
        assertThat(bound.discussion()).contains(message);
        assertThat(bound.step()).isEmpty();
    }

    @Test
    void queuedDiscussionUsesTheActuallyClaimedTurn()
    {
        QueuedInputId queueId = QueuedInputId.random();
        AgentTurn question = activate("queue:" + queueId + ":accepted-turn");
        QueuedAgentInput input = queued(queueId, question, "review-ask:message-1");
        ReviewMessage message = question("run-1", Optional.empty(), Optional.of(queueId.toString()));
        when(queue.find(queueId)).thenReturn(Optional.of(input));
        when(reviews.listMessages(REVIEW_ID)).thenReturn(ImmutableList.of(message));

        ReviewToolAssignment.Binding bound = assignments.resolve(REVIEW_ID, Optional.of(question.id()));

        assertThat(bound.discussion()).contains(message);
        assertThat(bound.step()).isEmpty();
    }

    @Test
    void missingQuestionAssociationIsRetryableAndDoesNotBecomeGenericInput()
    {
        AgentTurn direct = activate("review-ask:message-1");
        assertPendingQuestion(direct);

        QueuedInputId queueId = QueuedInputId.random();
        AgentTurn question = activate("queue:" + queueId + ":accepted-turn");
        when(queue.listQueued(TASK_ID)).thenReturn(ImmutableList.of(queued(queueId, question, "review-ask:message-1")));
        assertPendingQuestion(question);
    }

    @Test
    void persistedQuestionCanBindWhileTheAcceptedTurnLinkIsBeingSaved()
    {
        AgentTurn turn = activate("review-ask:message-1");
        ReviewMessage message = question("run-1", Optional.empty(), Optional.empty());
        when(reviews.listMessages(REVIEW_ID)).thenReturn(ImmutableList.of(message));

        assertThat(assignments.resolve(REVIEW_ID, Optional.of(turn.id())).discussion()).contains(message);
    }

    @Test
    void genericUserInputOnlyReceivesDiscussionAccess()
    {
        AgentTurn turn = activate("user-input-1");

        ReviewToolAssignment.Binding bound = assignments.resolve(REVIEW_ID, Optional.of(turn.id()));

        assertThat(bound.run()).isSameAs(run);
        assertThat(bound.step()).isEmpty();
        assertThat(bound.discussion()).isEmpty();
    }

    @Test
    void explanationRefreshKeepsStepAndCardBindingsButGenericInputUsesTheFindingsRun()
    {
        ReviewRun explanation = run("refresh", ReviewStep.explanation());
        when(reviews.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run, explanation));
        AgentTurn refresh = activate("review-step:refresh:1");

        ReviewToolAssignment.Binding assigned = assignments.resolve(REVIEW_ID, Optional.of(refresh.id()));
        assertThat(assigned.run()).isSameAs(explanation);
        assertThat(assigned.step()).contains(ReviewStep.explanation());

        AgentTurn input = activate("user-input-1");
        assertThat(assignments.resolve(REVIEW_ID, Optional.of(input.id())).run()).isSameAs(run);

        AgentTurn question = activate("review-ask:message-1");
        ReviewMessage message = new ReviewMessage("message-1", REVIEW_ID, Optional.empty(), Optional.of("refresh:purpose"), ReviewMessage.Author.USER,
                "Explain the intention", 1, explanation.id(), Optional.of(question.id().toString()), Optional.empty(), 0, NOW);
        when(reviews.listMessages(REVIEW_ID)).thenReturn(ImmutableList.of(message));
        ReviewToolAssignment.Binding discussion = assignments.resolve(REVIEW_ID, Optional.of(question.id()));
        assertThat(discussion.run()).isSameAs(explanation);
        assertThat(discussion.discussion()).contains(message);
        assertThat(discussion.step()).isEmpty();
    }

    @Test
    void anEndedReviewRejectsEvenItsPreviouslyAcceptedTurn()
    {
        AgentTurn turn = activate("review-step:run-1:1");
        when(reviews.findReview(REVIEW_ID)).thenReturn(Optional.of(review.closed(NOW)));

        assertThatThrownBy(() -> assignments.resolve(REVIEW_ID, Optional.of(turn.id())))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("review has ended");
    }

    private AgentTurn activate(String key)
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), TASK_ID, key, SETTINGS, NOW);
        when(turns.findActive(TASK_ID)).thenReturn(Optional.of(turn));
        return turn;
    }

    private ReviewRun run(String id, ReviewStep step)
    {
        ReviewRun result = new ReviewRun(id, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", "base", "base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(step), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(reviews.findRun(id)).thenReturn(Optional.of(result));
        return result;
    }

    private void replaceRun(ReviewRun updated)
    {
        run = updated;
        when(reviews.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run));
        when(reviews.findRun(run.id())).thenReturn(Optional.of(run));
    }

    private static ReviewStep step(int number, ReviewStep.Kind kind, boolean repair)
    {
        return new ReviewStep(number, kind, repair, Optional.empty(), Optional.empty());
    }

    private static ReviewMessage question(String runId, Optional<String> turnId, Optional<String> queuedId)
    {
        return new ReviewMessage("message-1", REVIEW_ID, Optional.of("finding-1"), Optional.empty(), ReviewMessage.Author.USER,
                "Does this finding apply to the old version?", 1, runId, turnId, queuedId, 0, NOW);
    }

    private static QueuedAgentInput queued(QueuedInputId id, AgentTurn turn, String key)
    {
        return QueuedAgentInput.queued(id, TASK_ID, 1, key, new BlobRef("question.txt", "text/plain", 1), "Question", SETTINGS, NOW)
                .starting(turn.id(), EventId.random(), NOW);
    }

    private void assertStaleTurn(Optional<TurnId> captured)
    {
        assertThatThrownBy(() -> assignments.resolve(REVIEW_ID, captured))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("stopped or replaced turn");
    }

    private void assertStaleStep(AgentTurn turn)
    {
        assertThatThrownBy(() -> assignments.resolve(REVIEW_ID, Optional.of(turn.id())))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("assignment has been replaced");
    }

    private void assertPendingQuestion(AgentTurn turn)
    {
        assertThatThrownBy(() -> assignments.resolve(REVIEW_ID, Optional.of(turn.id())))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("question is still being saved");
    }
}
