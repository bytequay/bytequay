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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.web.PatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * Deterministic moderator for the review flow-type, Phase 1
 * (single-reviewer pass). Owns the lifecycle of a {@link ReviewPass}:
 * create the {@code flow='review'} thread, seat the panel
 * (moderator + one reviewer + a human row for the orchestrator),
 * dispatch the reviewer call through the existing {@link LlmReviewer}
 * pathway, persist the streamed kickoff/summary messages and the
 * per-line findings, and transition the pass through
 * {@code KICKOFF → INDEPENDENT → TERMINATE} with a suggested verdict
 * the user later confirms at the publish gate.
 *
 * <p>This is the single-reviewer slice — the multi-reviewer
 * {@code CROSS_REVIEW / CONSENSUS / DEBATE / ARBITRATE} phases land
 * in follow-up commits. The phase enum already covers them so the
 * row shape doesn't need to change.
 */
@Service
public class ReviewPassService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewPassService.class);

    private final ThreadStore threadStore;
    private final ReviewStore reviewStore;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final LlmReviewerRegistry reviewers;

    public ReviewPassService(
            ThreadStore threadStore,
            ReviewStore reviewStore,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            LlmReviewerRegistry reviewers)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
    }

    /**
     * Kick off a fresh review pass for the given PR. Synchronous in
     * Phase 1 — one model call, no parallel fan-out yet. Streams a
     * future async / SSE wrapper on top once the multi-reviewer phase
     * lands and the panel size justifies it.
     */
    @Transactional
    public ReviewPassDetail startReviewOnPr(String repoFullName, int prNumber)
    {
        requireNonNull(repoFullName, "repoFullName is null");
        if (prNumber <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "prNumber must be a positive integer");
        }

        LlmReviewer reviewer = reviewers.active();
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "The active LLM provider (" + reviewer.displayName() + ") has no API key "
                            + "configured. Add it in Settings → AI review.");
        }

        // 1. Pull the PR + diff via the existing GitHub bindings so
        //    the new flow reuses the cached raw-detail / diff paths
        //    rather than re-fetching from scratch.
        PullRequestRef ref = parseRef(repoFullName, prNumber);
        String pat = patResolver.resolve(repoFullName);
        PrRawDetail raw = pullRequests.fetchPrDetail(pat, ref);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Empty response from GitHub PR detail");
        }
        String diff = pullRequests.fetchPrDiff(pat, ref);

        Instant now = Instant.now();

        // 2. Materialise the review thread. flow=REVIEW + kind=
        //    LOGIC_LOOP — the read-only design row says review
        //    threads never take a worktree lease, so there's no
        //    CLI-agent kind to consider in Phase 1.
        Thread thread = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.LOGIC_LOOP,
                reviewer.providerId(),
                /* agentSessionId */ null,
                "Review " + repoFullName + "#" + prNumber,
                ThreadStatus.RUNNING,
                /* model */ null,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                now, now,
                /* endedAt */ null, /* errorMessage */ null,
                ThreadFlow.REVIEW,
                /* activeTask */ null);
        threadStore.saveThread(thread);

        // 3. Pass row at KICKOFF — a later step transitions it as the
        //    reviewer runs. round 0, round_cap 3, default cost cap.
        ReviewPass pass = new ReviewPass(
                UUID.randomUUID().toString(),
                thread.id(),
                repoFullName,
                prNumber,
                raw.headSha(),
                ReviewPhase.KICKOFF,
                /* round */ 0,
                /* roundCap */ 3,
                /* costCapMilli */ 500L,
                /* costUsdMilli */ 0L,
                /* verdict */ null,
                now,
                /* endedAt */ null);
        reviewStore.savePass(pass);

        // 4. Seat the panel. Phase 1 = moderator + one reviewer + the
        //    human row. The human row exists so a later commit can
        //    bind user-typed messages to it without a schema change.
        ReviewParticipant moderator = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.MODERATOR,
                /* credentialId */ null,
                "Moderator",
                /* model */ null,
                /* color */ null,
                now);
        ReviewParticipant reviewerParticipant = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.REVIEWER,
                /* credentialId */ reviewer.providerId(),
                reviewer.displayName(),
                /* model */ null,
                /* color */ null,
                now);
        ReviewParticipant human = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.HUMAN,
                /* credentialId */ null,
                "You",
                /* model */ null,
                /* color */ null,
                now);
        reviewStore.saveParticipant(moderator);
        reviewStore.saveParticipant(reviewerParticipant);
        reviewStore.saveParticipant(human);

        // 5. Kickoff message — broadcast announcement. Mentions the
        //    panel so a later UI can highlight who's about to speak.
        reviewStore.saveMessage(new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                moderator.id(),
                ReviewPhase.KICKOFF,
                /* round */ 0,
                "Reviewing " + repoFullName + "#" + prNumber + " with "
                        + reviewer.displayName() + ". Independent phase starting.",
                List.of(reviewerParticipant.id()),
                /* refs */ List.of(),
                /* costUsdMilli */ 0L,
                now));

        // 6. Transition to INDEPENDENT and dispatch the reviewer.
        pass = withPhase(pass, ReviewPhase.INDEPENDENT, /* endedAt */ null);
        reviewStore.savePass(pass);

        ReviewOutput output;
        try {
            ReviewRequest request = new ReviewRequest(
                    repoFullName, prNumber,
                    /* title */ null,
                    raw.body(),
                    raw.headSha(),
                    diff);
            output = reviewer.review(request);
        }
        catch (RuntimeException e) {
            // Mark the pass terminated-with-error so the UI shows a
            // clear failure state rather than a pass stuck at
            // INDEPENDENT forever. Re-throw as a 502 so the caller
            // sees the provider error verbatim.
            log.warn("Review pass {} failed during INDEPENDENT phase: {}", pass.id(), e.getMessage());
            ReviewPass terminated = withPhase(pass, ReviewPhase.TERMINATE, Instant.now());
            reviewStore.savePass(terminated);
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "LLM reviewer call failed: " + e.getMessage(), e);
        }

        // 7. Reviewer summary as one INDEPENDENT message.
        Instant afterReviewer = Instant.now();
        reviewStore.saveMessage(new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                reviewerParticipant.id(),
                ReviewPhase.INDEPENDENT,
                /* round */ 0,
                output.summary() == null ? "" : output.summary(),
                /* mentions */ List.of(),
                /* refs */ List.of(),
                /* costUsdMilli */ 0L,
                afterReviewer));

        // 8. Per-line findings. Phase 1 marks them AGREED — there's
        //    only one reviewer so nothing is disputed. The publish
        //    gate later transitions agreed → posted.
        List<ReviewOutput.LineComment> comments = output.comments() == null
                ? List.of() : output.comments();
        for (ReviewOutput.LineComment c : comments) {
            reviewStore.saveFinding(new ReviewFinding(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    c.file(),
                    c.line() > 0 ? c.line() : null,
                    severityFromComment(c.severity()),
                    ReviewFindingStatus.AGREED,
                    c.body() == null ? "" : c.body(),
                    /* resolution */ null,
                    /* postedCommentId */ null,
                    afterReviewer));
        }

        // 9. Terminate. Suggested verdict comes from the severity mix
        //    so the user has a one-click default; nothing posts until
        //    they confirm via the publish gate (a future commit wires
        //    that surface for review findings).
        ReviewVerdict suggested = suggestedVerdict(comments);
        ReviewPass terminated = new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(),
                ReviewPhase.TERMINATE,
                pass.round(),
                pass.roundCap(),
                pass.costCapMilli(),
                pass.costUsdMilli(),
                suggested,
                pass.createdAt(),
                /* endedAt */ afterReviewer);
        reviewStore.savePass(terminated);

        return buildDetail(terminated);
    }

    public Optional<ReviewPassDetail> findPassWithDetail(String passId)
    {
        return reviewStore.findPassById(passId).map(this::buildDetail);
    }

    public Optional<ReviewPassDetail> findLatestPassForThread(String threadId)
    {
        List<ReviewPass> passes = reviewStore.listPassesByThread(threadId);
        if (passes.isEmpty()) {
            return Optional.empty();
        }
        // listPassesByThread is oldest-first; the latest pass is what
        // the UI wants to render by default (older passes are history).
        return Optional.of(buildDetail(passes.get(passes.size() - 1)));
    }

    private ReviewPassDetail buildDetail(ReviewPass pass)
    {
        return new ReviewPassDetail(
                pass,
                reviewStore.listParticipantsForPass(pass.id()),
                reviewStore.listMessagesForPass(pass.id()),
                reviewStore.listFindingsForPass(pass.id()));
    }

    private static ReviewPass withPhase(ReviewPass pass, ReviewPhase phase, Instant endedAt)
    {
        return new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(),
                phase,
                pass.round(),
                pass.roundCap(),
                pass.costCapMilli(),
                pass.costUsdMilli(),
                pass.verdict(),
                pass.createdAt(),
                endedAt);
    }

    /** Translate a free-form severity string from the LLM into the
     *  enum. Unknown / blank inputs fall back to MAJOR — that's a
     *  finding worth surfacing rather than silently dropping. */
    private static ReviewFindingSeverity severityFromComment(String raw)
    {
        return ReviewFindingSeverity.fromDbValue(raw);
    }

    /** Pick a default verdict from the severity mix. Any BLOCKER →
     *  REQUEST_CHANGES; any other finding → COMMENT; no findings at
     *  all → APPROVE. The user confirms at the publish gate — this
     *  is just the one-click default. */
    private static ReviewVerdict suggestedVerdict(List<ReviewOutput.LineComment> comments)
    {
        if (comments.isEmpty()) {
            return ReviewVerdict.APPROVE;
        }
        for (ReviewOutput.LineComment c : comments) {
            if (severityFromComment(c.severity()) == ReviewFindingSeverity.BLOCKER) {
                return ReviewVerdict.REQUEST_CHANGES;
            }
        }
        return ReviewVerdict.COMMENT;
    }
}
