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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.context.ContextUsageRecorder;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestReviewTaskObserver
{
    @Test
    void startupReplayConfirmsOnlyCompletedByteQuayContextReceipts()
    {
        AgentEventService events = mock(AgentEventService.class);
        AgentReviewStore reviews = mock(AgentReviewStore.class);
        AgentTurnStore turns = mock(AgentTurnStore.class);
        ContextUsageRecorder usage = mock(ContextUsageRecorder.class);
        ObjectProvider<AgentReviewService> reviewService = mock(ObjectProvider.class);
        ObjectProvider<PullRequestRefreshService> refresh = mock(ObjectProvider.class);
        when(reviewService.getObject()).thenReturn(mock(AgentReviewService.class));
        TaskId taskId = TaskId.random();
        AgentReview review = new AgentReview("review-1", "workspace-1", "acme/widgets", OptionalInt.of(1), OptionalLong.empty(), taskId.toString(), Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
        String search = "context-search:" + "a".repeat(64);
        String note = "context-note:" + "b".repeat(64);
        when(reviews.listOpenReviews()).thenReturn(ImmutableList.of(review));
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of());
        when(turns.list(taskId)).thenReturn(ImmutableList.of());
        when(events.readHistory(eq(taskId), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(),
                OptionalLong.empty(), OptionalLong.empty(), 4));
        when(events.subscribe(taskId, 4)).thenReturn(subscriber -> subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription()
        {
            @Override
            public void request(long count) {}

            @Override
            public void cancel() {}
        }));
        when(events.readAfter(taskId, 0, 200)).thenReturn(ImmutableList.of(
                tool(taskId, 1, "bytequay/review_get_context", ActivityState.COMPLETED, search),
                tool(taskId, 2, "mcp__bytequay__review_get_context", ActivityState.COMPLETED, note),
                tool(taskId, 3, "third-party/review_get_context", ActivityState.COMPLETED, "context-note:" + "c".repeat(64)),
                tool(taskId, 4, "bytequay/review_get_context", ActivityState.FAILED, "context-search:" + "d".repeat(64))));

        ReviewTaskObserver observer = new ReviewTaskObserver(events, reviews, turns, reviewService, refresh, mock(ObjectProvider.class), usage);
        try {
            observer.restore();
            verify(usage, timeout(1000)).confirm("workspace-1", search, ContextUsageEvent.Stage.FOUND);
            verify(usage, timeout(1000)).confirm("workspace-1", search, ContextUsageEvent.Stage.SCOPE_DEMAND);
            verify(usage, timeout(1000)).confirm("workspace-1", search, ContextUsageEvent.Stage.NO_MATCH);
            verify(usage, timeout(1000)).confirm("workspace-1", note, ContextUsageEvent.Stage.OPENED);
            verifyNoMoreInteractions(usage);
        }
        finally {
            observer.close();
        }
    }

    @Test
    void closingReplayConfirmsRecentReceiptBeyondTheFirstFiveThousandEvents()
    {
        AgentEventService events = mock(AgentEventService.class);
        AgentReviewStore reviews = mock(AgentReviewStore.class);
        AgentTurnStore turns = mock(AgentTurnStore.class);
        ContextUsageRecorder usage = mock(ContextUsageRecorder.class);
        TaskId taskId = TaskId.random();
        AgentReview review = new AgentReview("review-1", "workspace-1", "acme/widgets", OptionalInt.of(1), OptionalLong.empty(), taskId.toString(), Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
        String ref = "context-note:" + "b".repeat(64);
        when(turns.list(taskId)).thenReturn(ImmutableList.of());
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of());
        when(events.readHistory(eq(taskId), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(),
                OptionalLong.empty(), OptionalLong.empty(), 5002));
        when(events.readAfter(taskId, 2, 200)).thenReturn(ImmutableList.of(tool(taskId, 5001,
                "bytequay/review_get_context", ActivityState.COMPLETED, ref)));

        ReviewTaskObserver observer = new ReviewTaskObserver(events, reviews, turns, mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), usage);
        try {
            observer.confirmRetainedUsage(review);
            verify(usage).confirm("workspace-1", ref, ContextUsageEvent.Stage.OPENED);
            verify(events).readAfter(taskId, 2, 200);
        }
        finally {
            observer.close();
        }
    }

    @Test
    void removingTheFinalQueuedQuestionReconcilesAnIdleReview()
    {
        AgentEventService events = mock(AgentEventService.class);
        AgentReviewService service = mock(AgentReviewService.class);
        ObjectProvider<AgentReviewService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(service);
        TaskId taskId = TaskId.random();
        AgentReview review = new AgentReview("review-queue", "workspace-1", "acme/widgets", OptionalInt.of(1), OptionalLong.empty(), taskId.toString(), Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
        when(events.readHistory(eq(taskId), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0));
        when(events.subscribe(taskId, 0)).thenReturn(subscriber -> {
            subscriber.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));
            subscriber.onNext(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, taskId, Optional.empty(),
                    AgentEventSource.root(), Instant.EPOCH, new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.QUEUE_CHANGED, "Removed queued question")));
        });
        ReviewTaskObserver observer = new ReviewTaskObserver(events, mock(AgentReviewStore.class), mock(AgentTurnStore.class), services,
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ContextUsageRecorder.class));
        try {
            observer.watch(review);
            verify(service, timeout(1000)).reconcile(review.id());
        }
        finally {
            observer.close();
        }
    }

    private static AgentEventEnvelope tool(TaskId taskId, long sequence, String name, ActivityState state, String ref)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, taskId, Optional.empty(),
                AgentEventSource.root(), Instant.EPOCH, new AgentEventPayload.ToolActivity(name, state, "context-receipt:" + ref));
    }
}
