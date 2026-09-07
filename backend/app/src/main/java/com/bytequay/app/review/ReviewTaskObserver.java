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
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/**
 * Drives review progression from the durable task event stream with no renderer present.
 *
 * <p>One in-process subscription per open review reacts to settled lifecycle events by asking the
 * review service to reconcile; the service reads the durable turn and claim facts, so a duplicate
 * or early event is harmless. Startup restores every open review's subscription, and pull-request
 * refreshes feed observed heads through the same reconcile path.
 */
@Component
public class ReviewTaskObserver
{
    private static final Logger log = LoggerFactory.getLogger(ReviewTaskObserver.class);
    private static final Set<AgentEventPayload.LifecyclePhase> SETTLING = EnumSet.of(
            AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
            AgentEventPayload.LifecyclePhase.INTERRUPTED,
            AgentEventPayload.LifecyclePhase.TASK_COMPLETED,
            AgentEventPayload.LifecyclePhase.TASK_FAILED,
            AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED,
            AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED);

    private final AgentEventService events;
    private final AgentReviewStore store;
    private final ObjectProvider<AgentReviewService> reviews;
    private final ObjectProvider<PullRequestRefreshService> refresh;
    private final Map<String, Flow.Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("review-reconcile-", 0).factory());

    public ReviewTaskObserver(
            AgentEventService events,
            AgentReviewStore store,
            ObjectProvider<AgentReviewService> reviews,
            ObjectProvider<PullRequestRefreshService> refresh)
    {
        this.events = requireNonNull(events, "events is null");
        this.store = requireNonNull(store, "store is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        for (AgentReview review : store.listOpenReviews()) {
            watch(review);
            reconcileLater(review.id());
        }
    }

    @EventListener
    public void onDetailRefreshed(PullRequestDetailRefreshed event)
    {
        event.headSha().ifPresent(head -> executor.execute(() -> {
            try {
                reviews.getObject().observeHead(event.repo(), event.number(), head);
            }
            catch (RuntimeException failure) {
                log.warn("Cannot observe head {} for {}#{}: {}", head, event.repo(), event.number(), failure.toString());
            }
        }));
    }

    /** A list refresh says nothing about heads; each open review asks for its own conditional detail refresh. */
    @EventListener
    public void onPullRequestsRefreshed(PullRequestsRefreshed event)
    {
        for (AgentReview review : store.listOpenReviews()) {
            executor.execute(() -> {
                try {
                    refresh.getObject().refreshDetail(review.repo(), review.number());
                }
                catch (RuntimeException failure) {
                    log.debug("Review {} could not refresh its pull request: {}", review.id(), failure.toString());
                }
            });
        }
    }

    public void watch(AgentReview review)
    {
        requireNonNull(review, "review is null");
        if (subscriptions.containsKey(review.id())) {
            return;
        }
        TaskId taskId = TaskId.valueOf(review.taskId());
        long after;
        try {
            after = events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
        }
        catch (RuntimeException failure) {
            log.warn("Review {} cannot read its task log; reconciling on demand only: {}", review.id(), failure.toString());
            return;
        }
        try {
            events.subscribe(taskId, after).subscribe(new Subscriber(review.id()));
        }
        catch (RuntimeException failure) {
            log.warn("Review {} cannot subscribe to its task: {}", review.id(), failure.toString());
        }
    }

    public void unwatch(String reviewId)
    {
        Flow.Subscription subscription = subscriptions.remove(reviewId);
        if (subscription != null) {
            subscription.cancel();
        }
    }

    private void reconcileLater(String reviewId)
    {
        executor.execute(() -> {
            try {
                reviews.getObject().reconcile(reviewId);
            }
            catch (RuntimeException failure) {
                log.warn("Review {} reconcile failed: {}", reviewId, failure.toString());
            }
        });
    }

    @PreDestroy
    public void close()
    {
        subscriptions.values().forEach(Flow.Subscription::cancel);
        subscriptions.clear();
        executor.shutdownNow();
    }

    private final class Subscriber
            implements Flow.Subscriber<AgentEventEnvelope>
    {
        private final String reviewId;

        private Subscriber(String reviewId)
        {
            this.reviewId = reviewId;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            subscriptions.put(reviewId, subscription);
            subscription.request(1);
        }

        @Override
        public void onNext(AgentEventEnvelope item)
        {
            if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle && SETTLING.contains(lifecycle.phase())) {
                reconcileLater(reviewId);
            }
            Flow.Subscription subscription = subscriptions.get(reviewId);
            if (subscription != null) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            subscriptions.remove(reviewId);
            log.warn("Review {} lost its task stream: {}", reviewId, throwable.toString());
        }

        @Override
        public void onComplete()
        {
            subscriptions.remove(reviewId);
            reconcileLater(reviewId);
        }
    }
}
