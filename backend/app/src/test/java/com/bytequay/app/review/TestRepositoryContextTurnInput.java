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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.context.ContextContribution;
import com.bytequay.core.context.ContextDelivery;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.review.AgentReviewProjection.ContextDeliveryState.Outcome.CONFIRMED;
import static com.bytequay.app.review.AgentReviewProjection.ContextDeliveryState.Outcome.PREPARED;
import static com.bytequay.app.review.AgentReviewProjection.ContextDeliveryState.Outcome.SUBMITTED;
import static com.bytequay.app.review.AgentReviewProjection.ContextDeliveryState.Outcome.UNCERTAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRepositoryContextTurnInput
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final TaskId TASK = TaskId.valueOf("e4469e62-6122-4c61-b279-459a6ad35f20");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(
            Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of());
    private static final AgentReview REVIEW = new AgentReview("review", "workspace", "example/repository", 1, OptionalLong.empty(), TASK.toString(),
            AgentReview.State.OPEN, false, Optional.empty(), Optional.of(HEAD), 0, NOW, NOW, Optional.empty());

    @Test
    void suppliesExactFrozenBootstrapAndPersistsItsBindingBeforeSubmission()
    {
        Fixture fixture = new Fixture();
        String input = fixture.prepare("Review this PR");
        ReviewContextSnapshot snapshot = fixture.run.get().context().orElseThrow();
        ContextDelivery delivery = snapshot.deliveries().getFirst();

        assertThat(input).startsWith("Review this PR").contains("untrusted reference material", snapshot.bootstrap().orElseThrow().text(), HEAD);
        assertThat(delivery.packetDigest()).isEqualTo(snapshot.bootstrap().orElseThrow().digest());
        assertThat(delivery.inputDigest()).isEqualTo(ContextDelivery.digest(input));
        assertThat(delivery.inputUtf8Bytes()).isEqualTo(ContextLimits.bytes(input));
        assertThat(delivery.contributionCount()).isZero();
        assertThat(delivery.discussionMessageId()).isEmpty();
        assertThat(fixture.prepare("Review this PR")).isEqualTo(input);
        verify(fixture.reviews, times(1)).updateRun(any());
        assertThatThrownBy(() -> fixture.prepare("Changed input")).isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be replaced");
    }

    @Test
    void ordinaryHistoricalAndDisabledRunsKeepTheirOriginalInput()
    {
        Fixture fixture = new Fixture();
        fixture.run.set(run(Optional.empty()));
        assertThat(fixture.prepare("Original")).isEqualTo("Original");
        fixture.run.set(run(Optional.of(snapshot(false))));
        assertThat(fixture.prepare("Original")).isEqualTo("Original");
        when(fixture.reviews.findReviewByTask(TASK.toString())).thenReturn(Optional.empty());
        assertThat(fixture.prepare("Ordinary input")).isEqualTo("Ordinary input");
        verify(fixture.reviews, never()).updateRun(any());
    }

    @Test
    void validationGetsTheCompletedBriefAndLaterContributionsCannotRewriteItsBinding()
    {
        Fixture fixture = new Fixture();
        ReviewInvestigation investigation = new ReviewInvestigation("Changes", ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewRun sealed = fixture.run.get().withInvestigation(investigation, NOW)
                .replaceCurrentStep(ReviewStep.initial().withInvestigation(investigation), NOW)
                .withContextContribution(new ContextContribution(1, "Use the snapshot isolation contract", ImmutableList.of(), ImmutableList.of(),
                        ImmutableList.of("Source version remains unverified")), NOW)
                .withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW);
        fixture.run.set(sealed);
        fixture.activate("review-step:run:2");

        String input = fixture.prepare("Validate hypotheses");
        ContextDelivery delivery = fixture.run.get().context().orElseThrow().deliveries().getFirst();

        assertThat(input).contains("Use the snapshot isolation contract", "Source version remains unverified").doesNotContain("Original bootstrap");
        assertThat(delivery.contributionCount()).isEqualTo(1);
        ReviewContextSnapshot later = fixture.run.get().context().orElseThrow().withContribution(
                new ContextContribution(3, "Later source evidence", ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));
        assertThat(later.packetFor(delivery.contributionCount()).digest()).isEqualTo(delivery.packetDigest());
        assertThat(later.packetFor(2).digest()).isNotEqualTo(delivery.packetDigest());
    }

    @Test
    void queuedQuestionUsesItsOriginalRunsSettingAndCapturesAtExecution()
    {
        Fixture fixture = new Fixture();
        ReviewRun original = run("old-run", Optional.of(snapshot(true)));
        fixture.run.set(run(Optional.of(snapshot(false))));
        QueuedInputId queueId = QueuedInputId.random();
        fixture.activate("queue:" + queueId + ":accepted-turn");
        ReviewMessage message = new ReviewMessage("question", REVIEW.id(), Optional.of("finding"), Optional.empty(), ReviewMessage.Author.USER,
                "Does the older finding apply?", 1, "old-run", Optional.empty(), Optional.of(queueId.toString()), 0, NOW);
        when(fixture.reviews.findRun("old-run")).thenReturn(Optional.of(original));
        when(fixture.reviews.listMessages(REVIEW.id())).thenReturn(ImmutableList.of(message));
        when(fixture.queue.find(queueId)).thenReturn(Optional.of(QueuedAgentInput.queued(queueId, TASK, 1, "review-ask:question",
                new BlobRef("question.txt", "text/plain", 1), "Question", SETTINGS, NOW).starting(fixture.turn.id(), EventId.random(), NOW)));

        assertThat(fixture.prepare("Question about the old card")).contains("Original bootstrap");
        assertThat(fixture.run.get().context().orElseThrow().deliveries().getFirst().discussionMessageId()).contains(message.id());
    }

    @Test
    void rejectsCancelledAssignmentsAndValidationWithoutABrief()
    {
        Fixture fixture = new Fixture();
        when(fixture.turns.findActive(TASK)).thenReturn(Optional.of(fixture.turn.requestCancellation(NOW)));
        assertThatThrownBy(() -> fixture.prepare("Review")).isInstanceOf(ReviewConflictException.class).hasMessageContaining("stopped or replaced");
        fixture.run.set(fixture.run.get().withStep(new ReviewStep(2, ReviewStep.Kind.VALIDATE, false, Optional.empty(), Optional.empty()), NOW));
        fixture.activate("review-step:run:2");
        assertThatThrownBy(() -> fixture.prepare("Validate")).isInstanceOf(ReviewConflictException.class).hasMessageContaining("completed investigation brief");
        verify(fixture.reviews, never()).updateRun(any());
    }

    @Test
    void measuresTheWholeComposedInputInUtf8BeforePersistingIt()
    {
        int overhead = ContextLimits.bytes(new Fixture().prepare("x")) - 1;
        int capacity = ContextDelivery.MAXIMUM_INPUT_BYTES - overhead;
        String message = "界".repeat(capacity / 3) + "x".repeat(capacity % 3);
        Fixture accepted = new Fixture();
        assertThat(ContextLimits.bytes(accepted.prepare(message))).isEqualTo(ContextDelivery.MAXIMUM_INPUT_BYTES);
        Fixture rejected = new Fixture();
        assertThatThrownBy(() -> rejected.prepare(message + "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8 limit");
        verify(rejected.reviews, never()).updateRun(any());
    }

    @Test
    void deliveryLabelsRequireActualProviderAcknowledgment()
    {
        AgentTurn accepted = AgentTurn.accepted(TurnId.random(), TASK, "key", SETTINGS, NOW);
        AgentTurn submitted = accepted.markDeliveryStarted(NOW);
        AgentTurn acknowledged = submitted.running(new ProviderTurnRef("provider-turn"), Optional.empty());

        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.of(accepted)).outcome()).isEqualTo(PREPARED);
        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.of(submitted)).outcome()).isEqualTo(SUBMITTED);
        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.of(acknowledged)).outcome()).isEqualTo(CONFIRMED);
        AgentTurn interrupted = submitted.finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("Backend interrupted"), NOW);
        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.of(interrupted)).outcome()).isEqualTo(UNCERTAIN);
        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.of(accepted.finish(TurnState.CANCELLED, AgentMetrics.empty(), Optional.of("User stopped"), NOW)))
                .outcome()).isEqualTo(PREPARED);
        assertThat(AgentReviewProjection.ContextDeliveryState.of(Optional.empty()).outcome()).isEqualTo(UNCERTAIN);
    }

    private static ReviewContextSnapshot snapshot(boolean enabled)
    {
        return new ReviewContextSnapshot("context", enabled, 1, 42, Optional.of(HEAD), enabled ? Optional.of(ContextPacket.create("Original bootstrap — 资料")) : Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static ReviewRun run(Optional<ReviewContextSnapshot> context)
    {
        return run("run", context);
    }

    private static ReviewRun run(String id, Optional<ReviewContextSnapshot> context)
    {
        return new ReviewRun(id, REVIEW.id(), 1, ReviewRun.Scope.FULL, "main", HEAD, HEAD, HEAD, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(ReviewStep.initial()), false,
                ImmutableMap.of(), ImmutableMap.of(), context, Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private static class Fixture
    {
        private final AgentReviewStore reviews = mock(AgentReviewStore.class);
        private final AgentTurnStore turns = mock(AgentTurnStore.class);
        private final QueuedInputStore queue = mock(QueuedInputStore.class);
        private final AgentTask task = AgentTask.created(TASK, true, "Review", TitleSource.USER, new AgentLaneId("codex"),
                new TaskWorktree(TASK, REVIEW.workspaceId(), new RepositoryIdentity("example", "repository"), Path.of("/tmp/context-test").resolve(TASK.toString()),
                        "bytequay/task/" + TASK, HEAD),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty()), NOW);
        private final AtomicReference<ReviewRun> run = new AtomicReference<>(run(Optional.of(snapshot(true))));
        private final RepositoryContextTurnInput input = new RepositoryContextTurnInput(reviews, new ReviewToolAssignment(turns, queue, reviews),
                new ReviewLocks(), Clock.fixed(NOW, ZoneOffset.UTC));
        private AgentTurn turn;

        private Fixture()
        {
            when(reviews.findReviewByTask(TASK.toString())).thenReturn(Optional.of(REVIEW));
            when(reviews.findReview(REVIEW.id())).thenReturn(Optional.of(REVIEW));
            when(reviews.listRuns(REVIEW.id())).thenAnswer(ignored -> ImmutableList.of(run.get()));
            when(reviews.updateRun(any())).thenAnswer(invocation -> {
                ReviewRun updated = invocation.getArgument(0);
                run.set(updated.withVersion(updated.version() + 1));
                return run.get();
            });
            activate("review-step:run:1");
        }

        private void activate(String key)
        {
            turn = AgentTurn.accepted(TurnId.random(), TASK, key, SETTINGS, NOW);
            when(turns.findActive(TASK)).thenReturn(Optional.of(turn));
        }

        private String prepare(String message)
        {
            return input.prepare(task, turn, message);
        }
    }
}
