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
}
