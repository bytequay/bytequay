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
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.agent.runtime.TaskEventWatcher;
import com.bytequay.app.service.pr.PullRequestDetailRefreshed;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestsRefreshed;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Drives review progression from the durable task event stream with no renderer present.
 *
 * <p>One in-process subscription per open review reacts to settled lifecycle events by asking the
 * review service to reconcile; the service reads the durable turn and claim facts, so a duplicate
 * or early event is harmless. Startup restores every open review's subscription, and pull-request
 * refreshes record newer heads and catch up idle clean working copies without starting another review,
 * or end the review once GitHub reports its pull request merged or closed.
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
            AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED,
            AgentEventPayload.LifecyclePhase.QUEUE_CHANGED);
    private static final Set<ActivityState> MIRRORED_STATES = EnumSet.of(ActivityState.STARTED, ActivityState.COMPLETED, ActivityState.FAILED);

    private final TaskEventWatcher watcher;
    private final AgentReviewStore store;
    private final ObjectProvider<AgentReviewService> reviews;
    private final ObjectProvider<PullRequestRefreshService> refresh;
    private final ObjectProvider<LocalRunService> localRuns;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("review-reconcile-", 0).factory());
    // Keep background GitHub refreshes and head-follow work off the ordered task-event queue.
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("review-refresh-", 0).factory());
    private final Set<String> pendingRefreshes = ConcurrentHashMap.newKeySet();

    public ReviewTaskObserver(
            AgentEventService events,
            AgentReviewStore store,
            ObjectProvider<AgentReviewService> reviews,
            ObjectProvider<PullRequestRefreshService> refresh,
            ObjectProvider<LocalRunService> localRuns)
    {
        this.watcher = new TaskEventWatcher(requireNonNull(events, "events is null"), "Review");
        this.store = requireNonNull(store, "store is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.localRuns = requireNonNull(localRuns, "localRuns is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        for (AgentReview review : store.listOpenReviews()) {
            watch(review);
            reconcileLater(review.id());
        }
    }

    /** A pull request merged or closed on GitHub ends its open reviews; one still open may have a newer head to follow. */
    @EventListener
    public void onDetailRefreshed(PullRequestDetailRefreshed event)
    {
        // Closure can remove the working copy, so earlier command records must finish first.
        executor.execute(() -> {
            try {
                AgentReviewService service = reviews.getObject();
                if (!service.endIfPullRequestEnded(event.repo(), event.number())) {
                    event.headSha().ifPresent(head -> refreshExecutor.execute(() -> followHead(event.repo(), event.number(), head)));
                }
            }
            catch (RuntimeException failure) {
                log.warn("Cannot follow {}#{} after its refresh: {}", event.repo(), event.number(), failure.toString());
            }
        });
    }

    private void followHead(String repo, int number, String head)
    {
        try {
            reviews.getObject().observeHead(repo, number, head);
        }
        catch (RuntimeException failure) {
            log.warn("Cannot follow {}#{} after its refresh: {}", repo, number, failure.toString());
        }
    }

    /**
     * A list refresh says nothing about heads, so each open review asks for a warm refresh of its pull request. Discovery
     * stores a push or a merge as a newer update time, which the warm refresh fetches; otherwise only the warm pass's own
     * timers apply, so a quiet merged pull request costs no GitHub request.
     */
    @EventListener
    public void onPullRequestsRefreshed(PullRequestsRefreshed event)
    {
        for (AgentReview review : store.listOpenReviews()) {
            if (review.sourceTaskId().isPresent() || review.number().isEmpty()) {
                continue;
            }
            String key = review.repo() + "#" + review.number().getAsInt();
            if (!pendingRefreshes.add(key)) {
                continue;
            }
            refreshExecutor.execute(() -> {
                // A new list update during this read still needs one subsequent pass against its newer cache facts.
                pendingRefreshes.remove(key);
                try {
                    refresh.getObject().warmDetail(review.repo(), review.number().getAsInt());
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
        watcher.watch(review.id(), TaskId.valueOf(review.taskId()), item -> {
            if (mirrorsAgentCommand(item)) {
                runsLater(review.id(), runs -> runs.recordAgentCommand(review.id(), item));
            }
            if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle && SETTLING.contains(lifecycle.phase())) {
                // Queued after every command event of the turn, so a completion that did arrive always wins.
                runsLater(review.id(), runs -> runs.settleAgentRuns(review.id()));
                reconcileLater(review.id());
            }
        }, () -> reconcileLater(review.id()));
    }

    public void unwatch(String reviewId)
    {
        watcher.unwatch(reviewId);
    }

    public void reconcileLater(String reviewId)
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

    private void runsLater(String reviewId, Consumer<LocalRunService> action)
    {
        try {
            executor.execute(() -> {
                try {
                    // Reports are parsed here, off the stream, and each row change is one short write.
                    action.accept(localRuns.getObject());
                }
                catch (RuntimeException failure) {
                    log.warn("Review {} could not mirror its agent's runs: {}", reviewId, failure.toString());
                }
            });
        }
        catch (RuntimeException rejected) {
            log.debug("Review {} is closing; its agent's runs are not mirrored: {}", reviewId, rejected.toString());
        }
    }

    @PreDestroy
    public void close()
    {
        watcher.close();
        refreshExecutor.shutdownNow();
        executor.shutdownNow();
    }
}
