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
import com.bytequay.app.service.pr.PullRequestRefreshService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewTaskObserver
{
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
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        try {
            observer.watch(review);
            verify(service, timeout(1000)).reconcile(review.id());
        }
        finally {
            observer.close();
        }
    }

    @Test
    void aListRefreshWarmsEachRemoteReviewsPullRequestInsteadOfRefetchingIt()
    {
        AgentReviewStore store = mock(AgentReviewStore.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        ObjectProvider<PullRequestRefreshService> refreshes = mock(ObjectProvider.class);
        when(refreshes.getObject()).thenReturn(refresh);
        when(store.listOpenReviews()).thenReturn(ImmutableList.of(review("remote", 1, Optional.empty()), review("local", 2, Optional.of("source-task"))));
        ReviewTaskObserver observer = new ReviewTaskObserver(mock(AgentEventService.class), store, mock(AgentTurnStore.class), mock(ObjectProvider.class),
                refreshes, mock(ObjectProvider.class));
        try {
            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            verify(refresh, timeout(1000)).warmDetail("acme/widgets", 1);
            // A skipped review is never queued, so this cannot race.
            verify(refresh, never()).warmDetail("acme/widgets", 2);
            verify(refresh, never()).refreshDetail(anyString(), anyInt());
        }
        finally {
            observer.close();
        }
    }

    @Test
    void aRefreshedPullRequestThatEndedEndsItsReviewsInsteadOfFollowingItsHead()
    {
        AgentReviewService service = mock(AgentReviewService.class);
        ObjectProvider<AgentReviewService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(service);
        when(service.endIfPullRequestEnded("acme/widgets", 1)).thenReturn(true);
        ReviewTaskObserver observer = new ReviewTaskObserver(mock(AgentEventService.class), mock(AgentReviewStore.class), mock(AgentTurnStore.class), services,
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        try {
            observer.onDetailRefreshed(new PullRequestDetailRefreshed("acme/widgets", 1, Optional.of("ended-head")));
            observer.onDetailRefreshed(new PullRequestDetailRefreshed("acme/widgets", 2, Optional.of("open-head")));
            // One refresh is handled at a time, so once the open one follows its head the ended one is done.
            verify(service, timeout(1000)).observeHead("acme/widgets", 2, "open-head");
            verify(service).endIfPullRequestEnded("acme/widgets", 1);
            verify(service, never()).observeHead(eq("acme/widgets"), eq(1), any());
        }
        finally {
            observer.close();
        }
    }

    private static AgentReview review(String id, int number, Optional<String> sourceTaskId)
    {
        return new AgentReview(id, "workspace-1", "acme/widgets", OptionalInt.of(number), OptionalLong.empty(), TaskId.random().toString(), sourceTaskId,
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }
}
