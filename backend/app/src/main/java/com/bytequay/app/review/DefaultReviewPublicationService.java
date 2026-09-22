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
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewPublication;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultReviewPublicationService
        implements ReviewPublicationService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultReviewPublicationService.class);
    private static final List<String> EVENTS = ImmutableList.of("COMMENT", "APPROVE", "REQUEST_CHANGES");
    private static final int MAX_BODY = 60_000;

    private final ReviewDraftStore drafts;
    private final AgentReviewStore reviews;
    private final PullRequestService pullRequests;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(32);

    public DefaultReviewPublicationService(
            ReviewDraftStore drafts,
            AgentReviewStore reviews,
            PullRequestService pullRequests,
            Clock clock)
    {
        this.drafts = requireNonNull(drafts, "drafts is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public ReviewDraft draft(String repo, int number)
    {
        return stored(repo, number);
    }

    @Override
    public ReviewDraft addManual(String repo, int number, long expectedVersion, ReviewDraftItem item)
    {
        requireNonNull(item, "item is null");
        if (item.source() != ReviewDraftItem.Source.MANUAL) {
            throw new ReviewInvalidRequestException("A manual comment cannot claim a finding source");
        }
        return locked(repo, number, () -> {
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
                    Optional.empty());
            return drafts.save(draft.withItems(append(draft.items(), manual), now()));
        });
    }

    @Override
    public ReviewDraft addFinding(String repo, int number, long expectedVersion, String reviewId, String findingId)
    {
        return locked(repo, number, () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewFinding finding = requireFinding(reviewId, findingId);
            requirePromotable(finding);
            if (finding.anchor().isEmpty()) {
                // A finding about the whole pull request has no line to comment on, so it joins the review's own text, which the user edits before submitting.
                String text = body(finding.body());
                if (draft.body().contains(text)) {
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
                    body(finding.body()),
                    true,
                    Optional.empty());
            return drafts.save(draft.withItems(append(draft.items(), item), now()));
        });
    }

    @Override
    public ReviewDraft editItem(String repo, int number, long expectedVersion, String itemId, String body, boolean included)
    {
        return locked(repo, number, () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            if (item.published()) {
                throw new ReviewConflictException("This comment was already posted to GitHub");
            }
            ReviewDraftItem edited = item.withBody(body(body)).withIncluded(included);
            return drafts.save(draft.withItems(replace(draft.items(), edited), now()));
        });
    }

    @Override
    public ReviewDraft removeItem(String repo, int number, long expectedVersion, String itemId)
    {
        return locked(repo, number, () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            if (item.published()) {
                throw new ReviewConflictException("A posted comment cannot be removed from here; remote changes need a separate action on GitHub");
            }
            return drafts.save(draft.withItems(draft.items().stream().filter(candidate -> !candidate.id().equals(itemId)).collect(toImmutableList()), now()));
        });
    }

    @Override
    public ReviewDraft saveReview(String repo, int number, long expectedVersion, String body, String event)
    {
        String chosen = requireEvent(event);
        return locked(repo, number, () -> drafts.save(expect(repo, number, expectedVersion).withReview(body == null ? "" : body.strip(), chosen, now())));
    }

    @Override
    public Publication publishItem(String repo, int number, long expectedVersion, String itemId)
    {
        Reservation reservation = locked(repo, number, () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            ReviewDraftItem item = requireItem(draft, itemId);
            requireSendable(draft, ImmutableList.of(item));
            String attemptId = UUID.randomUUID().toString();
            ReviewDraft reserved = drafts.save(draft.withItems(replace(draft.items(), reserve(item, attemptId)), now()));
            return new Reservation(attemptId, reserved, ImmutableList.of(item));
        });
        PullRequestDetail detail;
        try {
            detail = pullRequests.createReviewComment(repo, number, comment(reservation.items().getFirst(), headSha(repo, number)));
        }
        catch (RuntimeException failure) {
            return releaseAfterFailure(repo, number, reservation, failure);
        }
        return locked(repo, number, () -> settle(repo, number, reservation, OptionalLong.empty(), detail));
    }

    @Override
    public Publication submit(String repo, int number, long expectedVersion, String body, String event)
    {
        String chosen = requireEvent(event);
        String text = body == null ? "" : body.strip();
        Reservation reservation = locked(repo, number, () -> {
            ReviewDraft draft = expect(repo, number, expectedVersion);
            List<ReviewDraftItem> sending = draft.items().stream().filter(item -> item.included() && !item.published()).collect(toImmutableList());
            requireSendable(draft, sending);
            if (sending.isEmpty() && text.isEmpty() && !chosen.equals("APPROVE")) {
                throw new ReviewInvalidRequestException("Add a comment or review text before submitting");
            }
            String attemptId = UUID.randomUUID().toString();
            List<ReviewDraftItem> items = new ArrayList<>(draft.items());
            sending.forEach(item -> items.set(indexOf(items, item.id()), reserve(item, attemptId)));
            ReviewDraft reserved = drafts.save(draft.withReview(text, chosen, now()).withItems(items, now()));
            return new Reservation(attemptId, reserved, sending);
        });
        String headSha = headSha(repo, number);
        List<InlineReviewComment> comments = reservation.items().stream().map(item -> comment(item, headSha)).collect(toImmutableList());
        PullRequestDetail detail;
        try {
            detail = pullRequests.submitReview(repo, number, prId(repo, number), chosen, text, comments);
        }
        catch (RuntimeException failure) {
            return releaseAfterFailure(repo, number, reservation, failure);
        }
        return locked(repo, number, () -> {
            Publication published = settle(repo, number, reservation, remoteReviewId(detail), detail);
            return new Publication(drafts.save(published.draft().withReview("", ReviewDraft.DEFAULT_EVENT, now())), published.publishedItemIds(), published.uncertainReason());
        });
    }

    @Override
    public List<Ineligible> ineligible(String repo, int number)
    {
        ReviewDraft draft = stored(repo, number);
        List<Ineligible> blocked = new ArrayList<>();
        for (ReviewDraftItem item : draft.items()) {
            if (item.published()) {
                continue;
            }
            reason(item).ifPresent(reason -> blocked.add(new Ineligible(item.id(), reason)));
        }
        return ImmutableList.copyOf(blocked);
    }

    /**
     * Records what the send established. A returned remote identity is stored; a send that
     * succeeded without an unambiguous per-comment mapping stays published without one rather than
     * being sent again.
     */
    private Publication settle(String repo, int number, Reservation reservation, OptionalLong remoteReviewId, PullRequestDetail detail)
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
            Optional<Remote> remote = match(detail, item);
            if (remote.isEmpty()) {
                ambiguous.add(item.id());
            }
            items.set(index, item.withPublication(Optional.of(new ReviewPublication(
                    reservation.attemptId(),
                    ReviewPublication.State.PUBLISHED,
                    remoteReviewId,
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
        return new Publication(drafts.save(draft.withItems(items, now())), ImmutableList.copyOf(published), uncertain);
    }

    /**
     * A send that threw. Delivery is unknown, so the reservation is kept as uncertain rather than
     * released: nothing may resend it, and the user sees why.
     */
    private Publication releaseAfterFailure(String repo, int number, Reservation reservation, RuntimeException failure)
    {
        log.warn("Review publication {} for {}#{} failed: {}", reservation.attemptId(), repo, number, failure.toString());
        return locked(repo, number, () -> {
            ReviewDraft draft = stored(repo, number);
            List<ReviewDraftItem> items = new ArrayList<>(draft.items());
            for (ReviewDraftItem sent : reservation.items()) {
                int index = indexOf(items, sent.id());
                if (index >= 0) {
                    items.set(index, items.get(index).withPublication(Optional.of(new ReviewPublication(
                            reservation.attemptId(), ReviewPublication.State.UNCERTAIN, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), now()))));
                }
            }
            drafts.save(draft.withItems(items, now()));
            throw failure;
        });
    }

    /** The remote comment for one sent item, only when path, line and body identify it unambiguously. */
    private Optional<Remote> match(PullRequestDetail detail, ReviewDraftItem item)
    {
        List<Remote> matches = detail.recentActivity().stream()
                .filter(activity -> "review_thread".equals(activity.eventType()) && activity.payload() != null)
                .filter(activity -> item.path().equals(activity.payload().path()))
                .filter(activity -> Objects.equals(activity.payload().line(), item.line()))
                .flatMap(activity -> activity.payload().comments().stream()
                        .filter(comment -> comment.githubId() != null && item.body().equals(comment.body()))
                        .map(comment -> new Remote(comment.githubId(), Optional.ofNullable(comment.htmlUrl()))))
                .collect(toImmutableList());
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static OptionalLong remoteReviewId(PullRequestDetail detail)
    {
        return detail.recentActivity().stream()
                .filter(activity -> "reviewed".equals(activity.eventType()) && activity.githubId() != null)
                .map(activity -> OptionalLong.of(activity.githubId()))
                .reduce((first, second) -> second)
                .orElse(OptionalLong.empty());
    }

    private void requireSendable(ReviewDraft draft, List<ReviewDraftItem> items)
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
        if (items.stream().map(ReviewDraftItem::publication).anyMatch(publication -> publication.filter(value -> value.state() == ReviewPublication.State.UNCERTAIN).isPresent())) {
            throw new ReviewConflictException("An earlier send of this comment has an unknown outcome; check the pull request on GitHub before sending it again");
        }
    }

    /** Why one item is not publishable, from the finding it was promoted from. */
    private Optional<String> reason(ReviewDraftItem item)
    {
        if (item.findingId().isEmpty()) {
            return Optional.empty();
        }
        Optional<ReviewFinding> finding = reviews.findFinding(item.findingId().orElseThrow());
        if (finding.isEmpty()) {
            return Optional.of("The finding behind this comment is gone");
        }
        ReviewFinding value = finding.orElseThrow();
        if (value.state() == ReviewFinding.State.RESOLVED) {
            return Optional.of("You resolved this finding locally, so it is excluded from publication");
        }
        if (value.state() == ReviewFinding.State.WITHDRAWN) {
            return Optional.of("The agent withdrew this finding");
        }
        if (value.outdated()) {
            return Optional.of("New commits moved these lines; update the review before publishing this comment");
        }
        if (item.findingRevision().isPresent() && item.findingRevision().getAsInt() != value.revision()) {
            return Optional.of("The agent revised this finding after you added it; recheck the comment text before sending");
        }
        return Optional.empty();
    }

    private static void requirePromotable(ReviewFinding finding)
    {
        if (finding.state() == ReviewFinding.State.RESOLVED) {
            throw new ReviewConflictException("You resolved this finding locally; reopen it before adding it to your review");
        }
        if (finding.state() == ReviewFinding.State.WITHDRAWN) {
            throw new ReviewConflictException("The agent withdrew this finding");
        }
        if (finding.outdated()) {
            throw new ReviewConflictException("New commits moved these lines; update the review before adding this finding");
        }
    }

    private InlineReviewComment comment(ReviewDraftItem item, String headSha)
    {
        return new InlineReviewComment(
                item.path(),
                item.body(),
                item.line(),
                item.side(),
                item.startLine().isPresent() ? item.startLine().getAsInt() : null,
                item.startLine().isPresent() ? item.side() : null,
                headSha);
    }

    private String headSha(String repo, int number)
    {
        return pullRequests.findHeadSha(repo, number)
                .orElseThrow(() -> new ReviewConflictException("The pull request head is unknown; refresh the pull request before publishing"));
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
        String repository = DefaultAgentReviewService.normalize(repo);
        return drafts.find(repository, number).orElseGet(() -> ReviewDraft.empty(repository, number, now()));
    }

    private ReviewDraft expect(String repo, int number, long expectedVersion)
    {
        ReviewDraft draft = stored(repo, number);
        if (draft.version() != expectedVersion) {
            throw new ReviewConflictException("Your pending review changed in another window; reload it");
        }
        return draft;
    }

    private <T> T locked(String repo, int number, Supplier<T> action)
    {
        Lock lock = locks.get(DefaultAgentReviewService.normalize(repo) + "#" + number);
        lock.lock();
        try {
            return action.get();
        }
        finally {
            lock.unlock();
        }
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private record Reservation(String attemptId, ReviewDraft draft, List<ReviewDraftItem> items) {}

    private record Remote(long commentId, Optional<String> url) {}
}
