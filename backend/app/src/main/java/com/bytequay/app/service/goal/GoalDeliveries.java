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
package com.bytequay.app.service.goal;

import com.bytequay.app.context.ContextText;
import com.bytequay.app.review.PullRequestDetailRefreshed;
import com.bytequay.app.review.PullRequestsRefreshed;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Follows the pull requests of the Goals' and Epics' open work: the work picks up a pull request its task published, a linked one still open is
 * refreshed whenever the lists are, and each change in its real status is recorded once on the work it concerns and, when it has a goal,
 * told to the goal's agent: a pull request open and waiting for review, a review approving it or still requesting changes, checks failing on
 * its head commit, and its merge. Whether the work is done stays the user's decision.
 */
@Component
public class GoalDeliveries
{
    private static final Logger log = LoggerFactory.getLogger(GoalDeliveries.class);
    private static final int MAX_REVIEW = 1_000;
    private static final int MAX_CHECKS = 5;
    // Conclusions that need the author; a cancelled or skipped run, or one waiting for approval, does not.
    private static final Set<String> FAILED = ImmutableSet.of("failure", "timed_out", "startup_failure");

    private final WorkspaceStore workspaces;
    private final ObjectProvider<WorkItemService> work;
    private final ObjectProvider<GoalConversationService> conversations;
    private final ObjectProvider<PullRequestRefreshService> refresh;
    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final Executor executor;

    @Autowired
    public GoalDeliveries(WorkspaceStore workspaces, ObjectProvider<WorkItemService> work, ObjectProvider<GoalConversationService> conversations,
            ObjectProvider<PullRequestRefreshService> refresh, PullRequestStore pullRequests, PrDetailStore details)
    {
        this(workspaces, work, conversations, refresh, pullRequests, details, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("goal-deliveries-", 0).factory()));
    }

    GoalDeliveries(WorkspaceStore workspaces, ObjectProvider<WorkItemService> work, ObjectProvider<GoalConversationService> conversations,
            ObjectProvider<PullRequestRefreshService> refresh, PullRequestStore pullRequests, PrDetailStore details, Executor executor)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.work = requireNonNull(work, "work is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** Merges that happened while ByteQuay was stopped are recorded once it starts. */
    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        executor.execute(() -> record(Optional.empty()));
    }

    /** A detail refresh may show the merge of a pull request that Goal work links. */
    @EventListener
    public void detailRefreshed(PullRequestDetailRefreshed event)
    {
        String key = GoalItem.numberedKey(event.repo(), event.number());
        executor.execute(() -> record(Optional.of(key)));
    }

    /** A list refresh says nothing about a linked pull request, so the Goals' open work asks for each one it should follow. */
    @EventListener
    public void listsRefreshed(PullRequestsRefreshed event)
    {
        executor.execute(this::follow);
    }

    void follow()
    {
        for (WorkItemView item : open()) {
            try {
                WorkItemView current = work.getObject().linkRelated(item.workspaceId(), item.id());
                for (GoalItemView link : current.links()) {
                    if (link.kind() == GoalItemKind.PULL_REQUEST && link.state() == GoalItemState.WORKING) {
                        GoalReferenceReader.NumberedKey pull = GoalReferenceReader.NumberedKey.parse(link.key());
                        refresh.getObject().refreshDetail(pull.repo(), pull.number());
                    }
                }
            }
            catch (RuntimeException failure) {
                log.debug("Goal work {} could not follow its pull requests: {}", item.id(), failure.toString());
            }
        }
        record(Optional.empty());
    }

    /**
     * Records each change in the real status of the work's pull requests once per work item, as a receipt and a word to the goal's agent: its
     * merge; while it is open, that it waits for review, which goes with the agent's next event rather than asking it, each reviewer's latest
     * verdict approving it or still requesting changes, and checks failing on its head commit.
     *
     * @param pullRequest the one pull request a detail refresh read, or every linked one
     */
    void record(Optional<String> pullRequest)
    {
        for (WorkItemView item : open()) {
            for (GoalItemView link : item.links()) {
                if (link.kind() != GoalItemKind.PULL_REQUEST || !pullRequest.map(link.key()::equals).orElse(true)) {
                    continue;
                }
                String about = "pull request " + link.label() + " for \"" + item.title() + "\"";
                if (link.state() == GoalItemState.MERGED) {
                    recordOnce(item, "merged:" + link.key(), "Pull request " + link.label() + " merged: " + link.title(),
                            "Pull request " + link.label() + " for \"" + item.title() + "\" merged. The user can now accept the result.", false);
                }
                else if (link.state() == GoalItemState.WORKING) {
                    recordOnce(item, "opened:" + link.key(), "Pull request " + link.label() + " opened: " + link.title(),
                            "Pull request " + link.label() + " for \"" + item.title() + "\" is open and waits for review.", true);
                    Optional<StoredPrDetail> detail = stored(link.key());
                    for (PrReviewState review : verdicts(detail, "CHANGES_REQUESTED")) {
                        String said = review.body() == null || review.body().isBlank() ? "" : ": " + ContextText.excerpt(review.body(), MAX_REVIEW);
                        recordOnce(item, "changes:" + review.githubId(), "@" + review.login() + " requested changes on " + link.label() + said,
                                "@" + review.login() + " requested changes on " + about + said, false);
                    }
                    for (PrReviewState review : verdicts(detail, "APPROVED")) {
                        recordOnce(item, "approved:" + review.githubId(), "@" + review.login() + " approved " + link.label(),
                                "@" + review.login() + " approved " + about + ".", false);
                    }
                    detail.filter(StoredPrDetail::checkRunsComplete).ifPresent(found -> {
                        List<String> failed = found.checkRuns().stream()
                                .filter(run -> run.conclusion() != null && FAILED.contains(run.conclusion().toLowerCase(Locale.ROOT)))
                                .map(PrCheckRunState::name).distinct().limit(MAX_CHECKS).collect(toImmutableList());
                        String head = found.raw() == null || found.raw().headSha() == null ? "" : found.raw().headSha();
                        if (!failed.isEmpty()) {
                            // Once per head commit: a new push may fail again, and that is news.
                            recordOnce(item, "checks:" + link.key() + "@" + head, "Checks failed on " + link.label() + ": " + String.join(", ", failed),
                                    "Checks failed on " + about + ": " + String.join(", ", failed) + ".", false);
                        }
                    });
                }
            }
        }
    }

    private void recordOnce(WorkItemView item, String operation, String receipt, String event, boolean routine)
    {
        if (item.history().stream().anyMatch(recorded -> recorded.operationId().equals(operation))) {
            return;
        }
        try {
            work.getObject().receipt(item.workspaceId(), item.id(), operation, receipt);
            item.goalId().ifPresent(goalId -> conversations.ifAvailable(conversation -> {
                if (routine) {
                    conversation.report(item.workspaceId(), goalId, operation + ":" + item.id(), item.id(), event);
                }
                else {
                    conversation.tell(item.workspaceId(), goalId, operation + ":" + item.id(), event);
                }
            }));
        }
        catch (RuntimeException failure) {
            log.warn("{} was not recorded on Goal work {}", operation, item.id(), failure);
        }
    }

    /** A pull request's stored detail, when ByteQuay holds one. */
    private Optional<StoredPrDetail> stored(String key)
    {
        GoalReferenceReader.NumberedKey pull = GoalReferenceReader.NumberedKey.parse(key);
        return pullRequests.findIdByRepoAndNumber(pull.repo(), pull.number()).flatMap(details::find);
    }

    /** The reviews whose reviewer's latest verdict is this one, once the stored reviews are complete. */
    private static List<PrReviewState> verdicts(Optional<StoredPrDetail> detail, String verdict)
    {
        return detail.filter(StoredPrDetail::reviewsComplete)
                .map(found -> PrReviewState.latestVerdicts(found.reviews()).stream()
                        .filter(review -> verdict.equals(review.state()) && review.githubId() != null)
                        .collect(toImmutableList()))
                .orElse(ImmutableList.of());
    }

    private List<WorkItemView> open()
    {
        return workspaces.findAll().stream().map(Workspace::id).flatMap(workspaceId -> work.getObject().list(workspaceId).stream())
                .filter(item -> (item.goalId().isPresent() || item.epicId().isPresent()) && item.state() != WorkItem.State.DONE && item.state() != WorkItem.State.DROPPED)
                .collect(toImmutableList());
    }
}
