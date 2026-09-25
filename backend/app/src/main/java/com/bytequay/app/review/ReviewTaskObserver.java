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
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.context.ContextUsageRecorder;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.google.common.collect.ImmutableSet;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Drives review progression from the durable task event stream with no renderer present.
 *
 * <p>One in-process subscription per open review reacts to settled lifecycle events by asking the
 * review service to reconcile; the service reads the durable turn and claim facts, so a duplicate
 * or early event is harmless. Startup restores every open review's subscription, and pull-request
 * refreshes record newer heads for the user's next Update review action.
 */
@Component
public class ReviewTaskObserver
{
    private static final Logger log = LoggerFactory.getLogger(ReviewTaskObserver.class);
    private static final long USAGE_REPLAY_EVENTS = 5000;
    private static final Pattern CONTEXT_RECEIPT = Pattern.compile("^context-receipt:(context-(search|note):[0-9a-f-]{36,64})$");
    private static final Set<String> REVIEW_CONTEXT_TOOLS = ImmutableSet.of("bytequay/review_get_context", "mcp__bytequay__review_get_context");
    private static final Set<AgentEventPayload.LifecyclePhase> SETTLING = EnumSet.of(
            AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
            AgentEventPayload.LifecyclePhase.INTERRUPTED,
            AgentEventPayload.LifecyclePhase.TASK_COMPLETED,
            AgentEventPayload.LifecyclePhase.TASK_FAILED,
            AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED,
            AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED);
    private static final Set<ActivityState> MIRRORED_STATES = EnumSet.of(
            ActivityState.STARTED,
            ActivityState.COMPLETED,
            ActivityState.FAILED);

    private final AgentEventService events;
    private final AgentReviewStore store;
    private final AgentTurnStore turns;
    private final ObjectProvider<AgentReviewService> reviews;
    private final ObjectProvider<PullRequestRefreshService> refresh;
    private final ObjectProvider<LocalRunService> localRuns;
    private final ContextUsageRecorder usage;
    private final Map<String, Flow.Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("review-reconcile-", 0).factory());

    public ReviewTaskObserver(
            AgentEventService events,
            AgentReviewStore store,
            AgentTurnStore turns,
            ObjectProvider<AgentReviewService> reviews,
            ObjectProvider<PullRequestRefreshService> refresh,
            ObjectProvider<LocalRunService> localRuns,
            ContextUsageRecorder usage)
    {
        this.events = requireNonNull(events, "events is null");
        this.store = requireNonNull(store, "store is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.localRuns = requireNonNull(localRuns, "localRuns is null");
        this.usage = requireNonNull(usage, "usage is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        for (AgentReview review : store.listOpenReviews()) {
            watch(review);
            executor.execute(() -> {
                try {
                    replayUsage(review);
                }
                catch (RuntimeException failure) {
                    log.debug("Review {} could not replay context usage: {}", review.id(), failure.toString());
                }
            });
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
            events.subscribe(taskId, after).subscribe(new Subscriber(review));
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

    /** Replays retained receipts before a closed review loses startup recovery. */
    public void confirmRetainedUsage(AgentReview review)
    {
        replayUsage(requireNonNull(review, "review is null"));
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

    /** Only filters, and never throws: an exception from {@code onNext} would cancel the subscription without {@code onError}. */
    static boolean mirrorsAgentCommand(AgentEventEnvelope item)
    {
        try {
            return item.payload() instanceof AgentEventPayload.CommandActivity command
                    && MIRRORED_STATES.contains(command.state())
                    && !command.background()
                    && MavenCommands.detect(command.argv()).isPresent();
        }
        catch (RuntimeException failure) {
            log.debug("Command event {} could not be checked for mirroring: {}", item.eventId(), failure.toString());
            return false;
        }
    }

    private void mirrorLater(String reviewId, AgentEventEnvelope item)
    {
        try {
            executor.execute(() -> {
                try {
                    // Reports are parsed here, off the stream, and the row is one short insert.
                    localRuns.getObject().recordAgentCommand(reviewId, item);
                }
                catch (RuntimeException failure) {
                    log.warn("Review {} could not mirror an agent command: {}", reviewId, failure.toString());
                }
            });
        }
        catch (RuntimeException rejected) {
            log.debug("Review {} is closing; an agent command is not mirrored: {}", reviewId, rejected.toString());
        }
    }

    private void replayUsage(AgentReview review)
    {
        TaskId taskId = TaskId.valueOf(review.taskId());
        Set<String> acceptedTurns = turns.list(taskId).stream()
                .filter(turn -> turn.providerTurnReference().isPresent()).map(turn -> turn.id().toString())
                .collect(java.util.stream.Collectors.toSet());
        store.listRuns(review.id()).stream().flatMap(run -> run.context().stream())
                .filter(snapshot -> snapshot.deliveries().stream().anyMatch(delivery -> delivery.contributionCount() == 0
                        && acceptedTurns.contains(delivery.turnId())))
                .forEach(snapshot -> usage.confirm(review.workspaceId(), snapshot.id(), ContextUsageEvent.Stage.PROVIDED));
        long latest = events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
        long cursor = Math.max(0, latest - USAGE_REPLAY_EVENTS);
        for (int page = 0; page < 25; page++) {
            var eventsPage = events.readAfter(taskId, cursor, 200);
            eventsPage.forEach(event -> confirmUsage(review, event));
            if (eventsPage.size() < 200) {
                return;
            }
            cursor = eventsPage.getLast().sequence();
        }
        log.debug("Review {} has more retained activity than the 5000-event usage replay bound; older unconfirmed context remains prepared", review.id());
    }

    private void confirmUsage(AgentReview review, AgentEventEnvelope event)
    {
        if (!(event.payload() instanceof AgentEventPayload.ToolActivity tool) || tool.state() != ActivityState.COMPLETED
                || !REVIEW_CONTEXT_TOOLS.contains(tool.name())) {
            return;
        }
        Matcher receipt = CONTEXT_RECEIPT.matcher(tool.summary());
        if (receipt.matches()) {
            if (receipt.group(2).equals("search")) {
                usage.confirm(review.workspaceId(), receipt.group(1), ContextUsageEvent.Stage.FOUND);
                usage.confirm(review.workspaceId(), receipt.group(1), ContextUsageEvent.Stage.SCOPE_DEMAND);
                usage.confirm(review.workspaceId(), receipt.group(1), ContextUsageEvent.Stage.NO_MATCH);
            }
            else {
                usage.confirm(review.workspaceId(), receipt.group(1), ContextUsageEvent.Stage.OPENED);
            }
        }
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
        private final AgentReview review;

        private Subscriber(AgentReview review)
        {
            this.review = review;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            subscriptions.put(review.id(), subscription);
            subscription.request(1);
        }

        @Override
        public void onNext(AgentEventEnvelope item)
        {
            if (item.payload() instanceof AgentEventPayload.ToolActivity tool && tool.state() == ActivityState.COMPLETED
                    && REVIEW_CONTEXT_TOOLS.contains(tool.name()) && CONTEXT_RECEIPT.matcher(tool.summary()).matches()) {
                executor.execute(() -> {
                    try {
                        confirmUsage(review, item);
                    }
                    catch (RuntimeException failure) {
                        log.debug("Review {} could not confirm context usage: {}", review.id(), failure.toString());
                    }
                });
            }
            else if (mirrorsAgentCommand(item)) {
                mirrorLater(review.id(), item);
            }
            if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle && SETTLING.contains(lifecycle.phase())) {
                reconcileLater(review.id());
            }
            Flow.Subscription subscription = subscriptions.get(review.id());
            if (subscription != null) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            subscriptions.remove(review.id());
            log.warn("Review {} lost its task stream: {}", review.id(), throwable.toString());
        }

        @Override
        public void onComplete()
        {
            subscriptions.remove(review.id());
            reconcileLater(review.id());
        }
    }
}
