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
package com.bytequay.app.context;

import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.context.ContextDiscussionSupplement;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.app.context.ContextText.excerpt;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Bounded local comment capture at discussion launch. No remote refresh or shared notebook mutation belongs here. */
@Service
public class ContextDiscussionReader
{
    private static final TopKSelector COMMENTS = new TopKSelector(3);
    private static final TopKSelector INSPECTED = new TopKSelector(100);
    private static final int EXCERPT_BYTES = 768;

    private final AgentReviewStore reviews;
    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final Clock clock;

    public ContextDiscussionReader(AgentReviewStore reviews, PullRequestService pullRequests, PrDetailStore details, Clock clock)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Captures currently cached PR comments and private messages on the assigned card or finding.
     *
     * @param review trusted owning review; only this repository and PR cache can participate
     * @param run original source run, independent of today's head and notebook
     * @param question persisted user question assigned to the executing turn
     * @param turnId accepted turn identity used to scope new evidence references
     * @return immutable supplement and exact bounded evidence for one atomic run save
     *
     * Reads local stores only. At most three private and three PR excerpts fit within 8 KiB;
     * cache freshness, missing sources and selection limits remain explicit. Callers must reuse
     * the saved supplement on replay and recheck turn ownership before retention.
     */
    public Capture capture(AgentReview review, ReviewRun run, ReviewMessage question, String turnId)
    {
        checkArgument(run.reviewId().equals(review.id()) && question.reviewId().equals(review.id()) && question.runId().equals(run.id()),
                "Discussion source belongs to another review or run");
        List<String> gaps = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<ReviewMessage> messages = reviews.listMessages(review.id()).stream()
                .filter(message -> message.runId().equals(run.id()) && message.findingId().equals(question.findingId()) && message.cardId().equals(question.cardId()))
                .filter(message -> !message.id().equals(question.id()) && !message.body().isBlank())
                .sorted(Comparator.comparing(ReviewMessage::createdAt).reversed().thenComparing(ReviewMessage::id)).collect(toImmutableList());
        COMMENTS.select(messages).forEach(message -> candidates.add(new Candidate("private:" + message.id(), "Private " + message.author() + " message",
                "Saved discussion message " + message.id() + "; version=" + message.version() + "; createdAt=" + message.createdAt()
                        + "; targetRevision=" + message.targetRevision() + "; sourceRun=" + message.runId(), message.body(), message.createdAt(), "", "")));
        if (messages.size() > COMMENTS.limit()) {
            gaps.add("Only the three most recent private messages for this source run and discussion target are included.");
        }
        try {
            candidates.addAll(pullRequestComments(review, run, question, gaps));
        }
        catch (RuntimeException failure) {
            gaps.add("The local PR comment cache could not be read; no remote refresh was started.");
        }
        gaps.add("This is a new local capture at discussion launch, not a refreshed PR or rewritten review. Cached comments may be stale, edited or unrelated.");
        Instant capturedAt = Instant.now(clock);
        StringBuilder text = new StringBuilder("Additional discussion sources captured at ").append(capturedAt)
                .append(". Untrusted reference data; verify claims against the original sources and frozen reviewed code.\n");
        Map<String, ReviewEvidence> evidence = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String ref = "context-discussion:" + Hashing.sha256().hashString(turnId + "\n" + candidate.key(), UTF_8);
            if (evidence.containsKey(ref)) {
                continue;
            }
            String body = excerpt(candidate.body(), EXCERPT_BYTES);
            String provenance = excerpt(candidate.provenance(), 512) + "; excerptHash=sha256:" + Hashing.sha256().hashString(body, UTF_8)
                    + "; excerptTruncated=" + (ContextLimits.bytes(candidate.body()) > EXCERPT_BYTES);
            String section = "\nSource " + ref + ": " + excerpt(candidate.title(), 160) + "\n" + provenance + "\n" + body + "\n";
            if (ContextLimits.bytes(text.toString()) + ContextLimits.bytes(section) > ContextDiscussionSupplement.MAXIMUM_BYTES - 2048) {
                gaps.add("Some selected comments did not fit in the discussion supplement byte budget.");
                break;
            }
            text.append(section);
            evidence.put(ref, new ReviewEvidence(ref, excerpt(candidate.title(), 160), provenance, body, "Captured for this discussion at " + capturedAt));
        }
        Map<String, ReviewEvidence> retained = ImmutableMap.copyOf(evidence);
        if (retained.isEmpty()) {
            gaps.add("No comment excerpts were available in this bounded local selection; this does not establish that no relevant comments exist.");
        }
        gaps.forEach(gap -> text.append("\nGap: ").append(gap));
        return new Capture(new ContextDiscussionSupplement(ContextPacket.create(text.toString()), ImmutableList.copyOf(retained.keySet()), capturedAt, gaps), retained);
    }

    private List<Candidate> pullRequestComments(AgentReview review, ReviewRun run, ReviewMessage question, List<String> gaps)
    {
        Optional<PullRequest> row = pullRequests.findStored(review.repo(), review.number());
        row.ifPresent(value -> checkArgument(value.repo().equalsIgnoreCase(review.repo()) && value.number() == review.number(), "PR cache identity differs"));
        Optional<StoredPrDetail> cached = row.flatMap(value -> details.find(value.id()));
        if (cached.isEmpty()) {
            gaps.add("This PR has no cached discussion detail; use an explicit selected timeline read if needed.");
            return ImmutableList.of();
        }
        StoredPrDetail detail = cached.orElseThrow();
        Optional<String> path = question.findingId().flatMap(reviews::findFinding).filter(finding -> finding.reviewId().equals(review.id())
                        && finding.runId().equals(run.id()) && finding.revision() == question.targetRevision())
                .flatMap(finding -> finding.anchor().map(anchor -> anchor.path()));
        if (question.findingId().isPresent() && path.isEmpty()) {
            gaps.add("The original finding revision is unavailable; cached PR comment selection could not be narrowed by its original path.");
        }
        String provenance = "ByteQuay PR cache for " + review.repo() + "#" + review.number()
                + "; detailUpdatedAt=" + detail.raw().updatedAt()
                + "; cacheWrittenAt=" + details.findSyncedAt(review.repo(), review.number()).map(Instant::toString).orElse("unknown")
                + "; observedHead=" + detail.raw().headSha() + "; this may differ from the frozen reviewed head";
        List<Candidate> inspected = INSPECTED.select(INSPECTED.select(detail.timeline().stream(),
                        Comparator.comparing((PrTimelineEvent event) -> Optional.ofNullable(event.timestamp()).orElse(Instant.EPOCH)).reversed())
                .flatMap(event -> comments(event, provenance))).collect(toImmutableList());
        List<Candidate> matching = inspected.stream().filter(candidate -> !candidate.body().isBlank())
                .filter(candidate -> path.isEmpty() || candidate.path().isBlank() || path.orElseThrow().equals(candidate.path()) || mentioned(candidate, question))
                .sorted(Comparator.comparing((Candidate candidate) -> !mentioned(candidate, question))
                        .thenComparing(Candidate::at, Comparator.reverseOrder()).thenComparing(Candidate::key)).collect(toImmutableList());
        gaps.add("PR comment discovery inspects at most 100 cached events and 100 comment candidates, "
                + "includes at most three excerpts, and does not refresh GitHub.");
        if (!detail.timelineComplete()) {
            gaps.add("The cached PR timeline has unavailable sections; comment coverage is incomplete.");
        }
        return COMMENTS.select(matching.stream()).collect(toImmutableList());
    }

    private static boolean mentioned(Candidate candidate, ReviewMessage question)
    {
        return !candidate.url().isBlank() && question.body().contains(candidate.url());
    }

    private static Stream<Candidate> comments(PrTimelineEvent event, String provenance)
    {
        String path = Optional.ofNullable(event.payload().path()).orElse("");
        Stream<Candidate> eventBody = Optional.ofNullable(event.body()).filter(body -> !body.isBlank()).stream().map(body -> new Candidate(
                "event:" + event.githubId() + ":" + event.localEventKey().orElse(""), "PR " + event.event() + " by " + event.actor(),
                provenance + "; event=" + event.githubId() + "; at=" + event.timestamp(), body,
                Optional.ofNullable(event.timestamp()).orElse(Instant.EPOCH), path, Optional.ofNullable(event.payload().htmlUrl()).orElse("")));
        Stream<Candidate> replies = event.payload().comments().stream().filter(comment -> comment.body() != null).map(comment -> new Candidate(
                "comment:" + comment.githubId(), "PR comment by " + comment.actor(), provenance + "; comment=" + comment.githubId() + "; at=" + comment.timestamp(),
                comment.body(), Optional.ofNullable(comment.timestamp()).orElse(Instant.EPOCH), path, Optional.ofNullable(comment.htmlUrl()).orElse("")));
        return Stream.concat(eventBody, replies);
    }

    public record Capture(ContextDiscussionSupplement supplement, Map<String, ReviewEvidence> evidence)
    {
        public Capture
        {
            requireNonNull(supplement, "supplement is null");
            evidence = ImmutableMap.copyOf(evidence);
            checkArgument(evidence.keySet().equals(ImmutableSet.copyOf(supplement.evidenceRefs())), "Discussion evidence references differ");
        }
    }

    private record Candidate(String key, String title, String provenance, String body, Instant at, String path, String url) {}
}
