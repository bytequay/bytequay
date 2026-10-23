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
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.service.pr.PullRequestDetailRefreshed;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestsRefreshed;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;

import static com.bytequay.app.review.ReviewTestData.openReview;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
        AgentReview review = pullRequestReview("review-queue", "workspace-1", "acme/widgets", 1, taskId.toString(), Instant.EPOCH);
        when(events.readHistory(eq(taskId), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0));
        when(events.subscribe(taskId, 0)).thenReturn(subscriber -> {
            subscriber.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));
            subscriber.onNext(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, taskId, Optional.empty(),
                    AgentEventSource.root(), Instant.EPOCH, new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.QUEUE_CHANGED, "Removed queued question")));
        });
        ReviewTaskObserver observer = new ReviewTaskObserver(events, mock(AgentReviewStore.class), services, mock(ObjectProvider.class), mock(ObjectProvider.class));
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
        ReviewTaskObserver observer = new ReviewTaskObserver(mock(AgentEventService.class), store, mock(ObjectProvider.class), refreshes, mock(ObjectProvider.class));
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
    void repeatedListRefreshesKeepOnlyOnePendingWarmPass()
            throws InterruptedException
    {
        AgentReviewStore store = mock(AgentReviewStore.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        ObjectProvider<PullRequestRefreshService> refreshes = mock(ObjectProvider.class);
        when(refreshes.getObject()).thenReturn(refresh);
        when(store.listOpenReviews()).thenReturn(ImmutableList.of(review("remote", 1, Optional.empty())));
        AgentReviewService service = mock(AgentReviewService.class);
        ObjectProvider<AgentReviewService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(service);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        doAnswer(invocation -> {
            started.countDown();
            released.await();
            finished.countDown();
            return null;
        }).when(refresh).warmDetail("acme/widgets", 1);
        ReviewTaskObserver observer = new ReviewTaskObserver(mock(AgentEventService.class), store, services, refreshes, mock(ObjectProvider.class));
        try {
            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            assertThat(started.await(1, SECONDS)).isTrue();
            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            observer.onDetailRefreshed(new PullRequestDetailRefreshed("acme/widgets", 1, Optional.of("head")));
            released.countDown();
            // The head notification follows every queued warm pass, so its completion proves the refresh queue was drained.
            verify(service, timeout(1000)).observeHead("acme/widgets", 1, "head");
            verify(refresh, times(2)).warmDetail("acme/widgets", 1);

            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            verify(refresh, timeout(1000).times(3)).warmDetail("acme/widgets", 1);
        }
        finally {
            released.countDown();
            finished.await(1, SECONDS);
            observer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void slowRemoteUpdatesDoNotDelayReviewProgress(boolean listRefresh)
            throws InterruptedException
    {
        AgentReviewStore store = mock(AgentReviewStore.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        ObjectProvider<PullRequestRefreshService> refreshes = mock(ObjectProvider.class);
        when(refreshes.getObject()).thenReturn(refresh);
        when(store.listOpenReviews()).thenReturn(ImmutableList.of(review("remote", 1, Optional.empty())));
        AgentReviewService service = mock(AgentReviewService.class);
        ObjectProvider<AgentReviewService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(service);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Answer<Void> waitForRelease = invocation -> {
            started.countDown();
            released.await();
            finished.countDown();
            return null;
        };
        doAnswer(waitForRelease).when(refresh).warmDetail("acme/widgets", 1);
        doAnswer(waitForRelease).when(service).observeHead("acme/widgets", 1, "head");
        ReviewTaskObserver observer = new ReviewTaskObserver(mock(AgentEventService.class), store, services, refreshes, mock(ObjectProvider.class));
        try {
            if (listRefresh) {
                observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            }
            else {
                observer.onDetailRefreshed(new PullRequestDetailRefreshed("acme/widgets", 1, Optional.of("head")));
            }
            assertThat(started.await(1, SECONDS)).isTrue();

            observer.reconcileLater("other-review");

            verify(service, timeout(1000)).reconcile("other-review");
        }
        finally {
            released.countDown();
            finished.await(1, SECONDS);
            observer.close();
        }
    }

    @Test
    void automaticClosureWaitsForEarlierCommandRecords()
            throws InterruptedException
    {
        AgentEventService events = mock(AgentEventService.class);
        AgentReview review = review("remote", 1, Optional.empty());
        TaskId taskId = TaskId.valueOf(review.taskId());
        AgentEventSource source = new AgentEventSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("maven-command"), Optional.empty());
        AgentEventEnvelope command = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, taskId, Optional.empty(),
                source, Instant.EPOCH, new AgentEventPayload.CommandActivity(ActivityState.COMPLETED, ImmutableList.of("mvn test"),
                        Optional.empty(), Optional.of(0), false, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty()));
        when(events.readHistory(eq(taskId), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0));
        when(events.subscribe(taskId, 0)).thenReturn(subscriber -> {
            subscriber.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));
            subscriber.onNext(command);
        });
        AgentReviewStore store = mock(AgentReviewStore.class);
        when(store.listOpenReviews()).thenReturn(ImmutableList.of(review));
        AgentReviewService service = mock(AgentReviewService.class);
        ObjectProvider<AgentReviewService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(service);
        when(service.endIfPullRequestEnded("acme/widgets", 1)).thenReturn(true);
        LocalRunService localRuns = mock(LocalRunService.class);
        ObjectProvider<LocalRunService> runServices = mock(ObjectProvider.class);
        when(runServices.getObject()).thenReturn(localRuns);
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            recording.countDown();
            released.await();
            finished.countDown();
            return null;
        }).when(localRuns).recordAgentCommand(review.id(), command);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        ObjectProvider<PullRequestRefreshService> refreshes = mock(ObjectProvider.class);
        when(refreshes.getObject()).thenReturn(refresh);
        CountDownLatch refreshed = new CountDownLatch(1);
        doAnswer(invocation -> {
            refreshed.countDown();
            return null;
        }).when(refresh).warmDetail("acme/widgets", 1);
        ReviewTaskObserver observer = new ReviewTaskObserver(events, store, services, refreshes, runServices);
        try {
            observer.watch(review);
            assertThat(recording.await(1, SECONDS)).isTrue();
            observer.onDetailRefreshed(new PullRequestDetailRefreshed("acme/widgets", 1, Optional.of("head")));
            observer.onPullRequestsRefreshed(new PullRequestsRefreshed());
            // The refresh executor has run work submitted after the closure notification, but closure still waits for the command record.
            assertThat(refreshed.await(1, SECONDS)).isTrue();
            verify(service, never()).endIfPullRequestEnded("acme/widgets", 1);

            released.countDown();

            verify(service, timeout(1000)).endIfPullRequestEnded("acme/widgets", 1);
            verify(service, never()).observeHead(eq("acme/widgets"), eq(1), any());
        }
        finally {
            released.countDown();
            finished.await(1, SECONDS);
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
        ReviewTaskObserver observer = new ReviewTaskObserver(
                mock(AgentEventService.class),
                mock(AgentReviewStore.class),
                services,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
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
        return openReview(id, "workspace-1", "acme/widgets", OptionalInt.of(number), TaskId.random().toString(), sourceTaskId, Instant.EPOCH);
    }
}
