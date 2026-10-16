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

import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The user's local pending review for one pull request, and the only path from it to GitHub.
 *
 * <p>Nothing here publishes by itself: every send is one explicit user action that revalidates the
 * pull request and the exact saved item payload before ByteQuay's existing GitHub
 * write path is called.
 */
@Service
public class ReviewPublicationService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewPublicationService.class);
    private static final List<String> EVENTS = ImmutableList.of("COMMENT", "APPROVE", "REQUEST_CHANGES");
    private static final int MAX_BODY = 60_000;
    // GitHub answers these before acting on a write, as do the checks before one; the work-item publish uses the same set.
    private static final Set<Integer> REFUSED = ImmutableSet.of(400, 401, 403, 404, 422, 429);

    private final ReviewDraftStore drafts;
    private final AgentReviewStore reviews;
    private final PullRequestService pullRequests;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(32);
    // Attempts a send in this process still owns. A RESERVED item outside it lost its sender to a
    // restart or a failed save, so nobody knows whether it landed: it reads as UNCERTAIN.
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ReviewPublicationService(ReviewDraftStore drafts, AgentReviewStore reviews, PullRequestService pullRequests, Clock clock)
    {
        this.drafts = requireNonNull(drafts, "drafts is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public ReviewDraft draft(String repo, int number)
    {
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = stored(repo, number);
            if (draft.items().stream().noneMatch(item -> item.commitId().isEmpty() && item.publication().isEmpty())) {
                return draft;
            }
            Optional<String> head = draft.items().stream().anyMatch(item -> item.source() == ReviewDraftItem.Source.MANUAL
                    && item.replyToCommentId().isEmpty() && item.commitId().isEmpty())
                    ? pullRequests.findHeadSha(repo, number) : Optional.empty();
            List<ReviewDraftItem> items = draft.items().stream().map(item -> {
                if (item.replyToCommentId().isPresent() || item.commitId().isPresent() || item.publication().isPresent()) {
                    return item;
                }
                Optional<String> commit = item.source() == ReviewDraftItem.Source.MANUAL ? head
                        : item.findingId().flatMap(reviews::findFinding)
                                .filter(finding -> item.findingRevision().equals(OptionalInt.of(finding.revision())))
                                .map(ReviewFinding::validatedHeadSha);
                return commit.map(item::withCommitId).orElse(item);
            }).collect(toImmutableList());
            return items.equals(draft.items()) ? draft : drafts.save(draft.withItems(items, now()));
        });
    }

    /** Adds a manual inline comment to the draft. */
    public ReviewDraft addManual(String repo, int number, long expectedVersion, ReviewDraftItem item)
    {
        requireNonNull(item, "item is null");
        if (item.source() != ReviewDraftItem.Source.MANUAL) {
            throw new ReviewInvalidRequestException("A manual comment cannot claim a finding source");
        }
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem manual = new ReviewDraftItem(
                    UUID.randomUUID().toString(),
                    ReviewDraftItem.Source.MANUAL,
                    Optional.empty(),
                    OptionalInt.empty(),
                    item.path(),
                    item.side(),
                    item.line(),
                    item.startLine(),
                    body(item.body()),
                    true,
                    Optional.empty(),
                    item.replyToCommentId().isPresent() ? Optional.empty() : item.commitId().or(() -> pullRequests.findHeadSha(repo, number)), item.replyToCommentId());
            return drafts.save(draft.withItems(append(draft.items(), manual), now()));
        });
    }

    /**
     * Snapshots a finding into the draft as an editable outgoing comment. The finding keeps its own
     * identity and body; edits and resolution on either side never change the other.
     */
    public ReviewDraft addFinding(String repo, int number, long expectedVersion, String reviewId, String findingId)
    {
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewFinding finding = requireFinding(reviewId, findingId);
            requirePromotable(finding);
            requireAcceptedReview(repo, number, finding);
            String text = body(finding.title().strip() + "\n\n" + finding.body().strip());
            if (finding.anchor().isEmpty()) {
                // A finding about the whole pull request has no line to comment on, so it joins the review's own text, which the user edits before submitting.
                if (draft.body().contains(body(finding.body()))) {
                    throw new ReviewConflictException("This finding is already in your review");
                }
                return drafts.save(draft.withReview(draft.body().isBlank() ? text : draft.body().stripTrailing() + "\n\n" + text, draft.event(), now()));
            }
            ReviewAnchor anchor = finding.anchor().orElseThrow();
            if (draft.items().stream().anyMatch(existing -> existing.findingId().equals(Optional.of(findingId)) && !existing.published())) {
                throw new ReviewConflictException("This finding is already in your review");
            }
            if (draft.items().stream().anyMatch(existing -> existing.findingId().equals(Optional.of(findingId)) && existing.published())) {
                throw new ReviewConflictException("This finding was already posted to GitHub");
            }
            ReviewDraftItem item = new ReviewDraftItem(
                    UUID.randomUUID().toString(),
                    ReviewDraftItem.Source.AGENT_FINDING,
                    Optional.of(finding.id()),
                    OptionalInt.of(finding.revision()),
                    anchor.path(),
                    anchor.side(),
                    anchor.endLine(),
                    anchor.startLine() == anchor.endLine() ? OptionalInt.empty() : OptionalInt.of(anchor.startLine()),
                    text,
                    true,
                    Optional.empty(),
                    Optional.of(finding.validatedHeadSha()), OptionalLong.empty());
            return drafts.save(draft.withItems(append(draft.items(), item), now()));
        });
    }

    public ReviewDraft editItem(String repo, int number, long expectedVersion, String itemId, String body, boolean included)
    {
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            if (item.publication().filter(value -> value.state() == ReviewPublication.State.RESERVED).isPresent()) {
                throw new ReviewConflictException("This comment is being sent; wait for the result before changing it");
            }
            if (item.published()) {
                throw new ReviewConflictException("This comment was already posted to GitHub");
            }
            requireKnownOutcome(item);
            ReviewDraftItem edited = item.withBody(body(body)).withIncluded(included);
            return drafts.save(draft.withItems(replace(draft.items(), edited), now()));
        });
    }

    public ReviewDraft removeItem(String repo, int number, long expectedVersion, String itemId)
    {
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            if (item.publication().filter(value -> value.state() == ReviewPublication.State.RESERVED).isPresent()) {
                throw new ReviewConflictException("This comment is being sent; wait for the result before changing it");
            }
            if (item.published()) {
                throw new ReviewConflictException("A posted comment cannot be removed from here; remote changes need a separate action on GitHub");
            }
            requireKnownOutcome(item);
            return drafts.save(draft.withItems(draft.items().stream().filter(candidate -> !candidate.id().equals(itemId)).collect(toImmutableList()), now()));
        });
    }

    /** The user checked GitHub and confirmed this uncertain comment is absent; preparing it again never sends it. */
    public ReviewDraft confirmNotPosted(String repo, int number, long expectedVersion, String itemId)
    {
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            if (item.publication().filter(value -> value.state() == ReviewPublication.State.UNCERTAIN).isEmpty()) {
                throw new ReviewConflictException("Only a comment with unconfirmed delivery can be returned to your pending review");
            }
            return drafts.save(draft.withItems(replace(draft.items(), item.withPublication(Optional.empty())), now()));
        });
    }

    /** Persists the review text and event the sheet holds; new drafts default to COMMENT. */
    public ReviewDraft saveReview(String repo, int number, long expectedVersion, String body, String event)
    {
        String chosen = requireEvent(event);
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> drafts.save(expect(repo, number,
                expectedVersion).withReview(body == null ? "" : body.strip(), chosen, now())));
    }

    /** Publishes exactly one previewed item, reserving it first so no other route can send it twice. */
    public Publication publishItem(String repo, int number, long expectedVersion, String itemId)
    {
        Reservation reservation = withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            requireSendable(ImmutableList.of(item));
            String attemptId = UUID.randomUUID().toString();
            ReviewDraft reserved = drafts.save(draft.withItems(replace(draft.items(), reserve(item, attemptId)), now()));
            inFlight.add(attemptId);
            return new Reservation(attemptId, reserved, ImmutableList.of(item));
        });
        try {
            PullRequestService.Posted posted;
            try {
                posted = pullRequests.createReviewComment(repo, number, comment(reservation.items().getFirst()));
            }
            catch (RuntimeException failure) {
                return releaseAfterFailure(repo, number, reservation, failure);
            }
            return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> settle(repo, number, reservation, posted, false));
        }
        finally {
            inFlight.remove(reservation.attemptId());
        }
    }

    /** Publishes the included, eligible items with the user's chosen review text and event. */
    public Publication submit(String repo, int number, long expectedVersion, String body, String event)
    {
        String chosen = requireEvent(event);
        String text = body == null ? "" : body.strip();
        // Before reserving, so a failed lookup leaves nothing to reconcile.
        long prId = prId(repo, number);
        Reservation reservation = withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            if (draft.items().stream().anyMatch(item -> item.publication().filter(value -> value.state() != ReviewPublication.State.PUBLISHED).isPresent())) {
                throw new ReviewConflictException("An earlier send is in flight or has an unknown outcome; check it on GitHub before submitting again");
            }
            List<ReviewDraftItem> sending = draft.items().stream().filter(item -> item.included() && !item.published()).collect(toImmutableList());
            requireSendable(sending);
            if (sending.isEmpty() && text.isEmpty() && !chosen.equals("APPROVE")) {
                throw new ReviewInvalidRequestException("Add a comment or review text before submitting");
            }
            String attemptId = UUID.randomUUID().toString();
            List<ReviewDraftItem> items = new ArrayList<>(draft.items());
            sending.forEach(item -> items.set(indexOf(items, item.id()), reserve(item, attemptId)));
            ReviewDraft reserved = drafts.save(draft.withReview(text, chosen, now()).withItems(items, now()));
            inFlight.add(attemptId);
            return new Reservation(attemptId, reserved, sending);
        });
        try {
            Map<String, List<ReviewDraftItem>> groups = new LinkedHashMap<>();
            for (ReviewDraftItem item : reservation.items()) {
                if (item.replyToCommentId().isPresent()) {
                    continue;
                }
                groups.computeIfAbsent(item.commitId().orElseThrow(), ignored -> new ArrayList<>()).add(item);
            }
            pullRequests.findHeadSha(repo, number).filter(groups::containsKey).ifPresent(head -> {
                List<ReviewDraftItem> current = groups.remove(head);
                groups.put(head, current);
            });
            List<List<ReviewDraftItem>> batches = new ArrayList<>(groups.values());
            if (batches.isEmpty()) {
                batches.add(ImmutableList.of());
            }
            List<ReviewDraftItem> lastBatch = new ArrayList<>(batches.getLast());
            reservation.items().stream().filter(item -> item.replyToCommentId().isPresent()).forEach(lastBatch::add);
            batches.set(batches.size() - 1, lastBatch);
            List<String> published = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            ReviewDraft settled = reservation.draft();
            for (int index = 0; index < batches.size(); index++) {
                List<ReviewDraftItem> batch = batches.get(index);
                boolean last = index == batches.size() - 1;
                Reservation group = new Reservation(reservation.attemptId(), reservation.draft(), batch);
                PullRequestService.Posted posted;
                try {
                    posted = pullRequests.submitReview(repo, number, prId, last ? chosen : "COMMENT", last ? text : "",
                            batch.stream().map(this::comment).collect(toImmutableList()));
                }
                catch (RuntimeException failure) {
                    releaseUnsent(repo, number, reservation, batches.subList(index + 1, batches.size()).stream().flatMap(List::stream).collect(toImmutableList()));
                    return releaseAfterFailure(repo, number, group, failure);
                }
                Publication result = withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> settle(repo, number, group, posted, last));
                published.addAll(result.publishedItemIds());
                result.uncertainReason().ifPresent(notices::add);
                settled = result.draft();
            }
            return new Publication(settled, ImmutableList.copyOf(published), notices.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", notices)));
        }
        finally {
            inFlight.remove(reservation.attemptId());
        }
    }

    private void releaseUnsent(String repo, int number, Reservation reservation, List<ReviewDraftItem> unsent)
    {
        if (unsent.isEmpty()) {
            return;
        }
        withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = stored(repo, number);
            List<ReviewDraftItem> items = new ArrayList<>(draft.items());
            for (ReviewDraftItem item : unsent) {
                int index = indexOf(items, item.id());
                if (index >= 0 && items.get(index).publication().filter(value -> value.attemptId().equals(reservation.attemptId())).isPresent()) {
                    items.set(index, items.get(index).withPublication(Optional.empty()));
                }
            }
            return drafts.save(draft.withItems(items, now()));
        });
    }

    /**
     * Records what the send established. A returned remote identity is stored; a send that
     * succeeded without an unambiguous per-comment mapping stays published without one rather than
     * being sent again. When the batch carried the review text, the same save clears it, so no
     * restart in between can leave it to be submitted twice.
     */
    private Publication settle(String repo, int number, Reservation reservation, PullRequestService.Posted posted, boolean carriedReview)
    {
        ReviewDraft draft = stored(repo, number);
        List<ReviewDraftItem> items = new ArrayList<>(draft.items());
        List<String> published = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (ReviewDraftItem sent : reservation.items()) {
            int index = indexOf(items, sent.id());
            if (index < 0) {
                continue;
            }
            ReviewDraftItem item = items.get(index);
            Optional<Remote> remote = match(posted, item);
            if (remote.isEmpty()) {
                ambiguous.add(item.id());
            }
            items.set(index, item.withPublication(Optional.of(new ReviewPublication(
                    reservation.attemptId(),
                    ReviewPublication.State.PUBLISHED,
                    posted.reviewId().isPresent() ? posted.reviewId() : remote.map(Remote::reviewId).orElse(OptionalLong.empty()),
                    remote.map(Remote::commentId).map(OptionalLong::of).orElse(OptionalLong.empty()),
                    remote.flatMap(Remote::url),
                    now()))));
            published.add(item.id());
        }
        Optional<String> uncertain = ambiguous.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        ambiguous.size()
                                + (ambiguous.size() == 1 ? " comment was" : " comments were")
                                + " sent, but GitHub's reply did not identify them; they are not sent again");
        ReviewDraft settled = draft.withItems(items, now());
        if (carriedReview && draft.body().equals(reservation.draft().body()) && draft.event().equals(reservation.draft().event())) {
            settled = settled.withReview("", ReviewDraft.DEFAULT_EVENT, now());
        }
        return new Publication(drafts.save(settled), ImmutableList.copyOf(published), uncertain);
    }

    /**
     * A send that threw. A refusal GitHub answers before acting sent nothing, so those items return
     * to the pending review. Any other failure may have landed: the items stay uncertain rather than
     * released, nothing may resend them, and the user sees why.
     */
    private Publication releaseAfterFailure(String repo, int number, Reservation reservation, RuntimeException failure)
    {
        log.warn("Review publication {} for {}#{} failed: {}", reservation.attemptId(), repo, number, failure.toString());
        boolean refused = failure instanceof ResponseStatusException response && REFUSED.contains(response.getStatusCode().value());
        Optional<ReviewPublication> outcome = refused ? Optional.empty() : Optional.of(uncertain(reservation.attemptId(), now()));
        return withLock(locks.get(AgentReviewService.normalize(repo) + "#" + number), () -> {
            ReviewDraft draft = stored(repo, number);
            List<ReviewDraftItem> items = new ArrayList<>(draft.items());
            for (ReviewDraftItem sent : reservation.items()) {
                int index = indexOf(items, sent.id());
                if (index >= 0) {
                    items.set(index, items.get(index).withPublication(outcome));
                }
            }
            drafts.save(draft.withItems(items, now()));
            throw failure;
        });
    }

    /**
     * The remote comment for one sent item, only when its file, or the thread it answers, and its text
     * identify it unambiguously among what the send returned. Lines are not compared: GitHub reports a
     * thread's start line inconsistently.
     */
    private static Optional<Remote> match(PullRequestService.Posted posted, ReviewDraftItem item)
    {
        List<Remote> matches = posted.threads().stream()
                .filter(thread -> item.replyToCommentId().isPresent() || item.path().equals(thread.payload().path()))
                .flatMap(thread -> thread.payload().comments().stream())
                .filter(comment -> comment.githubId() != null && item.body().equals(comment.body()))
                .filter(comment -> posted.reviewId().isEmpty() || Objects.equals(comment.reviewId(), posted.reviewId().getAsLong()))
                .filter(comment -> item.replyToCommentId().isEmpty() || Objects.equals(comment.inReplyToId(), item.replyToCommentId().getAsLong()))
                .map(comment -> new Remote(comment.githubId(), comment.reviewId() == null ? OptionalLong.empty() : OptionalLong.of(comment.reviewId()),
                        Optional.ofNullable(comment.htmlUrl())))
                .collect(toImmutableList());
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static void requireSendable(List<ReviewDraftItem> items)
    {
        for (ReviewDraftItem item : items) {
            reason(item).ifPresent(reason -> {
                throw new ReviewConflictException(reason);
            });
        }
        if (items.stream().anyMatch(ReviewDraftItem::published)) {
            throw new ReviewConflictException("A comment in this review was already posted to GitHub");
        }
        if (items.stream().map(ReviewDraftItem::publication).anyMatch(publication -> publication.filter(value -> value.state() == ReviewPublication.State.RESERVED).isPresent())) {
            throw new ReviewConflictException("Another send of this review is already in flight");
        }
    }

    private static void requireKnownOutcome(ReviewDraftItem item)
    {
        uncertainReason(item).ifPresent(reason -> {
            throw new ReviewConflictException(reason);
        });
    }

    private static Optional<String> uncertainReason(ReviewDraftItem item)
    {
        return item.publication().filter(value -> value.state() == ReviewPublication.State.UNCERTAIN)
                .map(ignored -> "An earlier send of this comment has an unknown outcome; check the pull request on GitHub before sending it again");
    }

    /** A saved comment belongs to the user; its source finding no longer controls publication. */
    private static Optional<String> reason(ReviewDraftItem item)
    {
        return uncertainReason(item).or(() -> item.replyToCommentId().isEmpty() && item.commitId().isEmpty()
                ? Optional.of("The comment's original commit is unknown; reopen the review preview before submitting")
                : Optional.empty());
    }

    private static void requirePromotable(ReviewFinding finding)
    {
        if (finding.state() == ReviewFinding.State.RESOLVED) {
            throw new ReviewConflictException("You resolved this finding locally; reopen it before adding it to your review");
        }
        if (finding.state() == ReviewFinding.State.WITHDRAWN) {
            throw new ReviewConflictException("The agent withdrew this finding");
        }
    }

    private InlineReviewComment comment(ReviewDraftItem item)
    {
        return new InlineReviewComment(
                item.path(),
                item.body(),
                item.line(),
                item.side(),
                item.startLine().isPresent() ? item.startLine().getAsInt() : null,
                item.startLine().isPresent() ? item.side() : null,
                item.commitId().orElse(null), item.replyToCommentId());
    }

    private long prId(String repo, int number)
    {
        return pullRequests.getPullRequestDetail(repo, number).id();
    }

    private ReviewFinding requireFinding(String reviewId, String findingId)
    {
        return reviews.findFinding(findingId)
                .filter(finding -> finding.reviewId().equals(reviewId))
                .orElseThrow(() -> new ReviewNotFoundException("Finding not found: " + findingId));
    }

    private void requireAcceptedReview(String repo, int number, ReviewFinding finding)
    {
        acceptedReviewProblem(repo, number, finding, finding.validatedHeadSha()).ifPresent(reason -> {
            throw new ReviewConflictException(reason);
        });
        boolean validated = reviews.listRuns(finding.reviewId()).stream()
                .filter(run -> !run.explanationOnly() && run.state() == ReviewRun.State.ACCEPTED && run.worktreeTreeSha().isEmpty()
                        && run.headSha().equals(finding.validatedHeadSha()))
                .anyMatch(run -> (run.id().equals(finding.runId()) && (finding.origin() == ReviewFinding.Origin.DISCUSSION
                        || run.currentStep().flatMap(ReviewStep::validation).filter(report -> report.selectedFindingIds().contains(finding.id())).isPresent()))
                        || run.currentStep().flatMap(ReviewStep::validation).stream()
                        .flatMap(validation -> validation.candidates().stream())
                        .anyMatch(candidate -> candidate.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED
                                && candidate.findingId().equals(Optional.of(finding.id())) && candidate.findingRevision().equals(Optional.of(finding.revision()))));
        if (!validated) {
            throw new ReviewConflictException("This finding needs an accepted review of its source revision before adding it");
        }
    }

    private Optional<String> acceptedReviewProblem(String repo, int number, ReviewFinding finding, String commit)
    {
        Optional<AgentReview> owner = reviews.findReview(finding.reviewId())
                .filter(review -> review.repo().equalsIgnoreCase(AgentReviewService.normalize(repo)) && review.number().stream().anyMatch(value -> value == number));
        if (owner.isEmpty()) {
            return Optional.of("This finding belongs to a different pull request");
        }
        List<ReviewRun> runs = reviews.listRuns(finding.reviewId());
        if (runs.stream().noneMatch(run -> !run.explanationOnly() && run.state() == ReviewRun.State.ACCEPTED && run.worktreeTreeSha().isEmpty() && run.headSha().equals(commit))) {
            return Optional.of("This finding needs an accepted review of its source commit before publication");
        }
        return Optional.empty();
    }

    private static ReviewDraftItem requireItem(ReviewDraft draft, String itemId)
    {
        return draft.items().stream().filter(item -> item.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new ReviewNotFoundException("Draft comment not found: " + itemId));
    }

    private static ReviewDraftItem reserve(ReviewDraftItem item, String attemptId)
    {
        return item.withPublication(
                Optional.of(new ReviewPublication(attemptId, ReviewPublication.State.RESERVED, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), Instant.EPOCH)));
    }

    private static ReviewPublication uncertain(String attemptId, Instant at)
    {
        return new ReviewPublication(attemptId, ReviewPublication.State.UNCERTAIN, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), at);
    }

    private static List<ReviewDraftItem> append(List<ReviewDraftItem> items, ReviewDraftItem item)
    {
        return ImmutableList.<ReviewDraftItem>builder().addAll(items).add(item).build();
    }

    private static List<ReviewDraftItem> replace(List<ReviewDraftItem> items, ReviewDraftItem item)
    {
        return items.stream().map(candidate -> candidate.id().equals(item.id()) ? item : candidate).collect(toImmutableList());
    }

    private static int indexOf(List<ReviewDraftItem> items, String itemId)
    {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(itemId)) {
                return index;
            }
        }
        return -1;
    }

    private static String requireEvent(String event)
    {
        String normalized = requireNonNull(event, "event is null").toUpperCase(Locale.ROOT);
        if (!EVENTS.contains(normalized)) {
            throw new ReviewInvalidRequestException("Unsupported review event: " + event);
        }
        return normalized;
    }

    private static String body(String body)
    {
        String text = requireNonNull(body, "body is null").strip();
        if (text.isEmpty()) {
            throw new ReviewInvalidRequestException("Comment text must not be blank");
        }
        if (text.length() > MAX_BODY) {
            throw new ReviewInvalidRequestException("Comment text is too long");
        }
        return text;
    }

    private ReviewDraft stored(String repo, int number)
    {
        String repository = AgentReviewService.normalize(repo);
        ReviewDraft draft = drafts.find(repository, number).orElseGet(() -> ReviewDraft.empty(repository, number, now()));
        return draft.withItems(draft.items().stream()
                .map(item -> item.publication()
                        .filter(value -> value.state() == ReviewPublication.State.RESERVED && !inFlight.contains(value.attemptId()))
                        .map(value -> item.withPublication(Optional.of(uncertain(value.attemptId(), draft.updatedAt()))))
                        .orElse(item))
                .collect(toImmutableList()), draft.updatedAt());
    }

    private ReviewDraft expect(String repo, int number, long expectedVersion)
    {
        ReviewDraft draft = stored(repo, number);
        if (draft.version() != expectedVersion) {
            throw new ReviewConflictException("Your pending review changed in another window; reload it");
        }
        return draft;
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private record Reservation(String attemptId, ReviewDraft draft, List<ReviewDraftItem> items) {}

    private record Remote(long commentId, OptionalLong reviewId, Optional<String> url) {}

    public record Publication(ReviewDraft draft, List<String> publishedItemIds, Optional<String> uncertainReason) {}
}
