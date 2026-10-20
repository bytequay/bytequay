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
package com.bytequay.app.discussion;

import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.discussion.ReviewCommentAnchor.MAX_SNAPSHOT_CHARACTERS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Reads the comments a private thread can be about from a pull request's cached detail, and captures
 * each as the snapshot its thread keeps. It never calls GitHub, so a key can only name a comment
 * already cached for that pull request.
 */
@Component
public final class ReviewCommentReader
{
    private static final int BODY_CHARACTERS = 4_000;
    private static final int HUNK_LINES = 40;
    private static final int HUNK_CHARACTERS = 4_000;
    private static final int PATH_CHARACTERS = 1_000;
    private static final int THREAD_CHARACTERS = 1_000;
    private static final int THREAD_CONTEXT = 3;

    private final PullRequestStore pullRequests;
    private final PrDetailStore details;

    public ReviewCommentReader(PullRequestStore pullRequests, PrDetailStore details)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
    }

    /**
     * Every keyable comment in the cached detail, oldest first: reviewed events with a non-blank body,
     * review-thread comments and issue comments, each with a GitHub id. A comment whose snapshot is over
     * its budget is left out. Empty when the pull request or its detail is not cached.
     */
    List<ReviewCommentItem> read(String repo, int number)
    {
        return comments(repo, number).stream()
                .filter(comment -> comment.snapshot().length() <= MAX_SNAPSHOT_CHARACTERS)
                .map(comment -> new ReviewCommentItem(new ReviewCommentAnchor(repo, number, comment.key(), comment.snapshot()),
                        comment.author(), comment.bot(), comment.createdAt()))
                .sorted(comparing(item -> item.createdAt().orElse(Instant.MAX)))
                .collect(toImmutableList());
    }

    /**
     * The one comment {@code key} names in that pull request's cached detail.
     *
     * @throws IllegalArgumentException when its snapshot is over its budget
     */
    Optional<ReviewCommentAnchor> find(String repo, int number, String key)
    {
        Optional<Comment> match = comments(repo, number).stream().filter(comment -> comment.key().equals(key)).findFirst();
        checkArgument(match.filter(comment -> comment.snapshot().length() > MAX_SNAPSHOT_CHARACTERS).isEmpty(), "This comment is too long to ask about");
        return match.map(comment -> new ReviewCommentAnchor(repo, number, key, comment.snapshot()));
    }

    /**
     * The unresolved, current review threads in the cached detail, each with its latest comment captured like
     * {@link #read}'s. A thread whose latest comment has no id or is over its budget is left out.
     */
    List<ReviewThreadItem> openThreads(String repo, int number)
    {
        Optional<StoredPrDetail> detail = pullRequests.findIdByRepoAndNumber(repo, number).flatMap(details::find);
        if (detail.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Long, String> commits = commits(detail.get());
        ImmutableList.Builder<ReviewThreadItem> threads = ImmutableList.builder();
        for (PrTimelineEvent event : detail.get().timeline()) {
            PrTimelinePayload payload = event.payload();
            if (!"review_thread".equals(event.event()) || payload.resolved() || payload.outdated() || payload.comments().isEmpty()) {
                continue;
            }
            List<PrTimelineComment> thread = payload.comments();
            PrTimelineComment root = thread.stream().filter(comment -> comment.inReplyToId() == null).findFirst().orElse(thread.getFirst());
            PrTimelineComment latest = thread.getLast();
            if (root.githubId() == null || latest.githubId() == null) {
                continue;
            }
            int index = thread.size() - 1;
            String snapshot = reviewComment(payload, thread.subList(Math.max(0, index - THREAD_CONTEXT), index), latest, commits);
            if (snapshot.length() <= MAX_SNAPSHOT_CHARACTERS) {
                threads.add(new ReviewThreadItem(root.githubId(), new ReviewCommentItem(
                        new ReviewCommentAnchor(repo, number, "review_comment:" + latest.githubId(), snapshot),
                        requireNonNullElse(latest.actor(), ""), latest.actorBot(), Optional.ofNullable(latest.timestamp()))));
            }
        }
        return threads.build();
    }

    private List<Comment> comments(String repo, int number)
    {
        Optional<StoredPrDetail> detail = pullRequests.findIdByRepoAndNumber(repo, number).flatMap(details::find);
        if (detail.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Long, String> commits = commits(detail.get());
        ImmutableList.Builder<Comment> comments = ImmutableList.builder();
        for (PrTimelineEvent event : detail.get().timeline()) {
            PrTimelinePayload payload = event.payload();
            String kind = requireNonNullElse(event.event(), "");
            if (kind.equals("reviewed") && event.githubId() != null && event.body() != null && !event.body().isBlank()) {
                ObjectNode snapshot = snapshot("review", event.actor());
                put(snapshot, "state", payload.state());
                put(snapshot, "commit", commits.get(event.githubId()));
                comments.add(comment("review:" + event.githubId(), event.actor(), payload.actorBot(), event.timestamp(), withBody(snapshot, payload.htmlUrl(), event.body())));
            }
            else if (kind.equals("commented") && event.githubId() != null) {
                comments.add(comment("issue_comment:" + event.githubId(), event.actor(), payload.actorBot(), event.timestamp(),
                        withBody(snapshot("issue_comment", event.actor()), payload.htmlUrl(), event.body())));
            }
            else if (kind.equals("review_thread")) {
                List<PrTimelineComment> thread = payload.comments();
                for (int index = 0; index < thread.size(); index++) {
                    PrTimelineComment comment = thread.get(index);
                    if (comment.githubId() != null) {
                        comments.add(comment("review_comment:" + comment.githubId(), comment.actor(), comment.actorBot(), comment.timestamp(),
                                reviewComment(payload, thread.subList(Math.max(0, index - THREAD_CONTEXT), index), comment, commits)));
                    }
                }
            }
        }
        return comments.build();
    }

    /** A review's commit comes from the reviews section, which can be unavailable while the timeline is not. */
    private static Map<Long, String> commits(StoredPrDetail detail)
    {
        return detail.reviews().stream()
                .filter(review -> review.githubId() != null && review.commitId() != null)
                .collect(toImmutableMap(PrReviewState::githubId, PrReviewState::commitId, (first, second) -> first));
    }

    private static String reviewComment(PrTimelinePayload payload, List<PrTimelineComment> earlier, PrTimelineComment comment, Map<Long, String> commits)
    {
        ObjectNode snapshot = snapshot("review_comment", comment.actor());
        put(snapshot, "path", payload.path() == null ? null : head(payload.path(), PATH_CHARACTERS, ""));
        Optional.ofNullable(payload.line()).or(() -> Optional.ofNullable(payload.originalLine())).ifPresent(line -> snapshot.put("line", line));
        snapshot.put("outdated", payload.outdated());
        put(snapshot, "commit", comment.reviewId() == null ? null : commits.get(comment.reviewId()));
        put(snapshot, "hunk", payload.diffHunk() == null ? null : hunk(payload.diffHunk()));
        if (!earlier.isEmpty()) {
            ArrayNode thread = snapshot.putArray("thread");
            for (PrTimelineComment previous : earlier) {
                ObjectNode entry = thread.addObject();
                put(entry, "author", previous.actor());
                put(entry, "body", previous.body() == null ? null : head(previous.body(), THREAD_CHARACTERS, " [truncated]"));
            }
        }
        return withBody(snapshot, comment.htmlUrl(), comment.body());
    }

    private static ObjectNode snapshot(String kind, String author)
    {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode().put("kind", kind);
        put(snapshot, "author", author);
        return snapshot;
    }

    private static String withBody(ObjectNode snapshot, String url, String body)
    {
        put(snapshot, "url", url);
        put(snapshot, "body", body == null ? null : head(body, BODY_CHARACTERS, url == null ? " [truncated]" : " [truncated; full text: " + url + "]"));
        return snapshot.toString();
    }

    private static void put(ObjectNode node, String field, String value)
    {
        if (value != null) {
            node.put(field, value);
        }
    }

    /** The first {@code limit} characters, never splitting a surrogate pair, with {@code marker} when cut. */
    static String head(String text, int limit, String marker)
    {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit) + marker;
    }

    /** The commented line ends a diff hunk, so a long hunk keeps its tail. */
    private static String hunk(String hunk)
    {
        List<String> lines = Splitter.on('\n').splitToList(hunk);
        String tail = lines.size() <= HUNK_LINES ? hunk : String.join("\n", lines.subList(lines.size() - HUNK_LINES, lines.size()));
        if (tail.length() <= HUNK_CHARACTERS) {
            return tail;
        }
        int start = tail.length() - HUNK_CHARACTERS;
        return tail.substring(Character.isLowSurrogate(tail.charAt(start)) ? start + 1 : start);
    }

    private static Comment comment(String key, String author, boolean bot, Instant createdAt, String snapshot)
    {
        return new Comment(key, requireNonNullElse(author, ""), bot, Optional.ofNullable(createdAt), snapshot);
    }

    private record Comment(String key, String author, boolean bot, Optional<Instant> createdAt, String snapshot) {}
}
