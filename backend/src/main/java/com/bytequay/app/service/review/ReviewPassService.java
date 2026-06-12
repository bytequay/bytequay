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

import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
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
import com.bytequay.app.domain.ReviewerPersona;
import com.bytequay.app.domain.ReviewerPersonaRole;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ReviewerPersonaStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmCompletion;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.ai.ModelPricing;
import com.bytequay.app.service.credentials.PatResolver;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.bytequay.app.config.AsyncConfig.REVIEW_EXECUTOR;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * Deterministic moderator for the review flow-type. Owns the
 * lifecycle of a {@link ReviewPass}: create the {@code flow='review'}
 * thread, seat the panel (moderator + N reviewers + a human row for
 * the orchestrator), dispatch the reviewer call(s) through the
 * existing {@link LlmReviewer} pathway, persist the streamed
 * messages + findings, and transition the pass through the right
 * phase machine for the panel size.
 *
 * <p>Panel-of-1: {@code KICKOFF → INDEPENDENT → TERMINATE} — the
 * single reviewer's findings persist as {@code AGREED} straight away
 * (no consensus to extract).
 *
 * <p>Panel-of-2+: {@code KICKOFF → INDEPENDENT (parallel)
 * → CROSS_REVIEW → TERMINATE}. INDEPENDENT runs the reviewers
 * concurrently against the same diff so no reviewer anchors on
 * another. CROSS_REVIEW transitions through a heuristic consensus
 * pass (no LLM round yet — Phase 3 follow-up adds the LLM-driven
 * cross-review + debate loop). Findings reported at the same
 * {@code (path, line)} by every reviewer land as a single AGREED row
 * carrying the highest-severity reading; the rest land as one
 * DISPUTED row per reporter so the publish UI can show what each
 * reviewer said.
 */
@Service
public class ReviewPassService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewPassService.class);

    private final ThreadStore threadStore;
    private final ReviewStore reviewStore;
    private final PullRequestRepository pullRequests;
    private final PullRequestStore pullRequestStore;
    private final PatResolver patResolver;
    private final LlmReviewerRegistry reviewers;
    private final AppSettingsStore appSettings;
    private final ReviewerPersonaStore personas;
    private final Executor reviewExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewPassService(
            ThreadStore threadStore,
            ReviewStore reviewStore,
            PullRequestRepository pullRequests,
            PullRequestStore pullRequestStore,
            PatResolver patResolver,
            LlmReviewerRegistry reviewers,
            AppSettingsStore appSettings,
            ReviewerPersonaStore personas,
            @Qualifier(REVIEW_EXECUTOR) Executor reviewExecutor)
    {
        this.reviewExecutor = requireNonNull(reviewExecutor, "reviewExecutor is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.personas = requireNonNull(personas, "personas is null");
    }

    /**
     * Kick off a fresh review pass for the given PR and run it to
     * completion synchronously. Used by the scheduled / one-click
     * paths, which already run on a background thread and read the
     * finished detail (verdict + finding counts) straight away.
     *
     * <p>Deliberately NOT wrapped in a single transaction: each
     * persistence call short-transacts on its own, so the multi-minute
     * model fan-out never pins the single pooled SQLite connection (a
     * method-level {@code @Transactional} here would hold that one
     * connection for the whole pass and starve the rest of the app).
     */
    public ReviewPassDetail startReviewOnPr(String repoFullName, int prNumber)
    {
        return runReviewBody(seatReviewPass(repoFullName, prNumber, StartOptions.DEFAULT));
    }

    /**
     * Variant of {@link #startReviewOnPr(String, int)} that honours
     * caller-specified panel selection + caps and returns as soon as
     * the pass is seated. The LLM panel body runs on
     * {@link AsyncConfig#REVIEW_EXECUTOR} so the interactive
     * {@code POST /api/reviews/start} request doesn't block on the
     * (multi-minute) model fan-out — and never holds the single SQLite
     * connection across it. The mockup-facing "Assign review task"
     * dialog calls this; the returned detail is the freshly-seated
     * pass (phase INDEPENDENT, thread id populated), which the review-
     * thread page then polls to live-fill.
     */
    public ReviewPassDetail startReviewOnPr(String repoFullName, int prNumber, StartOptions opts)
    {
        Seat seat = seatReviewPass(repoFullName, prNumber, opts);
        reviewExecutor.execute(() -> {
            try {
                runReviewBody(seat);
            }
            catch (RuntimeException e) {
                // runReviewBody already parks the pass at TERMINATE on
                // failure; there's no HTTP caller left to receive the
                // error, so log it and let the polling UI surface the
                // parked pass.
                log.warn("Async review body for pass {} failed: {}",
                        seat.pass().id(), e.getMessage());
            }
        });
        return buildDetail(seat.pass());
    }

    /**
     * Seat a new review pass: resolve the panel, pull the PR detail +
     * diff, materialise the thread + pass + participants + kickoff
     * message, and transition to INDEPENDENT. Synchronous and quick —
     * no model calls — so an interactive caller can return right after
     * it; the heavy LLM work happens in {@link #runReviewBody}.
     */
    private Seat seatReviewPass(String repoFullName, int prNumber, StartOptions opts)
    {
        requireNonNull(repoFullName, "repoFullName is null");
        requireNonNull(opts, "opts is null");
        if (prNumber <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "prNumber must be a positive integer");
        }

        List<PanelMember> panel = resolvePanelMembers(opts);

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
        //    CLI-agent kind to consider here.
        // ThreadEntity.model is NOT NULL — review threads don't have a
        // single canonical model (each panel reviewer runs its own), but
        // we still need to stamp something on the row. Use the same
        // LLM_MODEL setting the reviewers read, falling back to the
        // panel's primary providerId so a missing setting doesn't
        // bounce a 500 back to the user.
        String reviewModel = appSettings.get(Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElseGet(() -> panel.get(0).reviewer().providerId());
        Thread thread = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.LOGIC_LOOP,
                panel.get(0).reviewer().providerId(),
                /* agentSessionId */ null,
                "Review " + repoFullName + "#" + prNumber,
                ThreadStatus.RUNNING,
                reviewModel,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                now, now,
                /* endedAt */ null, /* errorMessage */ null,
                ThreadFlow.REVIEW,
                // The review thread lives in the workspace the dialog was
                // opened from, so it shows in that workspace's thread
                // list. Scheduled / one-click paths pass no workspace and
                // fall back to ws-default.
                opts.workspaceId() == null || opts.workspaceId().isBlank()
                        ? "ws-default" : opts.workspaceId(),
                /* workModel */ null,
                /* activeTask */ null);
        threadStore.saveThread(thread);

        // 3. Pass row at KICKOFF — a later step transitions it as the
        //    panel runs. round 0; caps honour the request when set.
        ReviewPass pass = new ReviewPass(
                UUID.randomUUID().toString(),
                thread.id(),
                repoFullName,
                prNumber,
                raw.headSha(),
                ReviewPhase.KICKOFF,
                /* round */ 0,
                opts.roundCap(),
                opts.costCapMilli(),
                /* costUsdMilli */ 0L,
                /* verdict */ null,
                now,
                /* endedAt */ null);
        reviewStore.savePass(pass);

        // 4. Seat the panel: moderator + N reviewers + the human row.
        //    The human row exists so a later commit can bind user-
        //    typed messages to it without a schema change.
        ReviewParticipant moderator = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.MODERATOR,
                /* credentialId */ null,
                "Moderator",
                /* model */ null, /* color */ null, now);
        reviewStore.saveParticipant(moderator);
        List<ReviewParticipant> reviewerSeats = new ArrayList<>();
        for (PanelMember m : panel) {
            ReviewParticipant seat = new ReviewParticipant(
                    UUID.randomUUID().toString(), pass.id(),
                    ReviewParticipantKind.REVIEWER,
                    /* credentialId */ m.reviewer().providerId(),
                    m.displayLabel(),
                    /* model */ null, /* color */ null, now);
            reviewStore.saveParticipant(seat);
            reviewerSeats.add(seat);
        }
        ReviewParticipant human = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.HUMAN,
                /* credentialId */ null,
                "You",
                /* model */ null, /* color */ null, now);
        reviewStore.saveParticipant(human);

        // 5. Kickoff message — broadcast announcement.
        String kickoffMention = panel.size() == 1
                ? panel.get(0).displayLabel()
                : "a panel of " + panel.size() + " reviewers (" + panelDisplayNames(panel) + ")";
        reviewStore.saveMessage(new ReviewMessage(
                UUID.randomUUID().toString(),
                pass.id(),
                moderator.id(),
                ReviewPhase.KICKOFF,
                /* round */ 0,
                "Reviewing " + repoFullName + "#" + prNumber + " with "
                        + kickoffMention + ". Independent phase starting.",
                reviewerSeats.stream().map(ReviewParticipant::id).toList(),
                /* refs */ List.of(),
                /* costUsdMilli */ 0L,
                now));

        // 6. Transition to INDEPENDENT and build the reviewer request.
        //    The seat phase ends here; the model fan-out runs in
        //    runReviewBody so it never holds the single pooled
        //    connection (each store write there short-transacts).
        pass = withPhase(pass, ReviewPhase.INDEPENDENT, /* endedAt */ null);
        reviewStore.savePass(pass);

        ReviewRequest request = new ReviewRequest(
                repoFullName, prNumber,
                /* title */ null,
                raw.body(),
                raw.headSha(),
                diff,
                composeSkillContext(/* baseSkill */ null));
        return new Seat(pass, panel, reviewerSeats, moderator, request);
    }

    /** Hand-off from the synchronous seat phase to the (sync or async)
     *  LLM body. */
    private record Seat(
            ReviewPass pass,
            List<PanelMember> panel,
            List<ReviewParticipant> reviewerSeats,
            ReviewParticipant moderator,
            ReviewRequest request) {}

    /**
     * Run the LLM panel for a seated pass: independent reviews in
     * parallel, persist each verbatim, extract consensus, run the
     * bounded debate over disputed items, and transition the pass to
     * its final phase. No method-level transaction — every persistence
     * call short-transacts so the model fan-out never pins the single
     * pooled connection. On a reviewer failure it parks the pass at
     * TERMINATE and rethrows, so the synchronous (scheduled) caller
     * sees the 502 and the async caller can log it.
     */
    private ReviewPassDetail runReviewBody(Seat seated)
    {
        ReviewPass pass = seated.pass();
        List<PanelMember> panel = seated.panel();
        List<ReviewParticipant> reviewerSeats = seated.reviewerSeats();
        ReviewParticipant moderator = seated.moderator();
        ReviewRequest request = seated.request();

        Map<ReviewParticipant, ReviewOutput> outputs;
        try {
            outputs = runIndependentInParallel(reviewerSeats, panel, request);
        }
        catch (RuntimeException e) {
            // Mark the pass terminated-with-error so the UI shows a
            // clear failure state rather than a pass stuck at
            // INDEPENDENT forever. Re-throw as a 502 so the caller
            // sees the provider error verbatim.
            log.warn("Review pass {} failed during INDEPENDENT phase: {}",
                    pass.id(), e.getMessage());
            ReviewPass terminated = withPhase(pass, ReviewPhase.TERMINATE, Instant.now());
            reviewStore.savePass(terminated);
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "LLM reviewer call failed: " + e.getMessage(), e);
        }

        // 7. Persist each reviewer's INDEPENDENT message verbatim.
        Instant afterReviewer = Instant.now();
        for (ReviewParticipant seat : reviewerSeats) {
            ReviewOutput out = outputs.get(seat);
            reviewStore.saveMessage(new ReviewMessage(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    seat.id(),
                    ReviewPhase.INDEPENDENT,
                    /* round */ 0,
                    out.summary() == null ? "" : out.summary(),
                    /* mentions */ List.of(),
                    /* refs */ List.of(),
                    /* costUsdMilli */ 0L,
                    afterReviewer));
        }

        // 8. Branch on panel size for findings persistence + phase
        //    transitions:
        //      - 1 reviewer: AGREED straight through, no CROSS_REVIEW.
        //      - 2+ reviewers: heuristic CONSENSUS over the panel's
        //        raw outputs; CROSS_REVIEW phase carries a moderator
        //        announcement so the transcript reflects the dedup
        //        result even without an LLM round.
        ReviewVerdict suggested;
        if (panel.size() == 1) {
            List<ReviewOutput.LineComment> comments = outputs.values().iterator().next().comments();
            comments = comments == null ? List.of() : comments;
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
            suggested = suggestedVerdictForComments(comments);
        }
        else {
            // CROSS_REVIEW (LLM-driven, per reviewer, deterministic
            // seat order so reruns reproduce): each reviewer reacts to
            // the whole panel's INDEPENDENT findings. A failed or
            // unparseable call means that reviewer abstains — the pass
            // continues on whoever answered.
            long spentMilli = pass.costUsdMilli();
            long capMilli = pass.costCapMilli();
            boolean budgetHit = false;
            pass = withCost(withPhase(pass, ReviewPhase.CROSS_REVIEW, /* endedAt */ null), spentMilli);
            reviewStore.savePass(pass);
            List<String> envelopes = new ArrayList<>();
            for (int i = 0; i < reviewerSeats.size(); i++) {
                ReviewParticipant seat = reviewerSeats.get(i);
                PanelMember member = panel.get(i);
                if (spentMilli >= capMilli) {
                    budgetHit = true;
                    log.warn("Review pass {} reached its cost cap ({} milli-USD) mid cross-review; "
                            + "skipping the remaining reviewers.", pass.id(), capMilli);
                    break;
                }
                LlmCompletion c = safeComplete(
                        member.reviewer(),
                        crossReviewSystemPrompt(),
                        crossReviewUserPrompt(member, reviewerSeats, panel, outputs, request),
                        "cross-review");
                if (c == null) {
                    continue;
                }
                String envelopeJson = validJsonObjectOrNull(c.text());
                if (envelopeJson == null) {
                    log.warn("Cross-review from {} was not parseable JSON — treating as abstain.",
                            member.displayLabel());
                    continue;
                }
                long callCost = ModelPricing.estimateCostMilli(c.modelName(), c.tokensIn(), c.tokensOut());
                spentMilli += callCost;
                envelopes.add("[" + member.displayLabel() + "]\n" + envelopeJson);
                reviewStore.saveMessage(new ReviewMessage(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        seat.id(),
                        ReviewPhase.CROSS_REVIEW,
                        /* round */ 0,
                        c.text().strip(),
                        /* mentions */ List.of(),
                        /* refs */ List.of(),
                        "cross_review",
                        envelopeJson,
                        callCost,
                        Instant.now()));
            }

            // CONSENSUS (one LLM call, run by the lead): fold the
            // cross-review envelopes + the independent findings into the
            // resolved set. The lead is the LEAD-role persona member, or
            // the first member when the panel carries no role info.
            PanelMember leadMember = panel.stream()
                    .filter(PanelMember::lead)
                    .findFirst()
                    .orElse(panel.get(0));
            ReviewParticipant leadSeat = reviewerSeats.get(panel.indexOf(leadMember));
            pass = withCost(withPhase(pass, ReviewPhase.CONSENSUS, /* endedAt */ null), spentMilli);
            reviewStore.savePass(pass);
            ConsensusOutcome outcome;
            if (budgetHit || spentMilli >= capMilli) {
                // No budget left for the consensus call — escalate every
                // finding to arbitration rather than spending past the cap.
                budgetHit = true;
                log.warn("Review pass {} reached its cost cap ({} milli-USD); skipping the "
                        + "consensus call and escalating findings to arbitration.", pass.id(), capMilli);
                outcome = new ConsensusOutcome(allDisputed(reviewerSeats, panel, outputs), null, 0L);
            }
            else {
                outcome = runConsensus(leadMember, reviewerSeats, panel, outputs, envelopes, request);
            }
            spentMilli += outcome.costMilli();
            ConsensusResult consensus = outcome.result();
            reviewStore.saveMessage(new ReviewMessage(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    leadSeat.id(),
                    ReviewPhase.CONSENSUS,
                    /* round */ 0,
                    "Consensus over the panel: " + consensus.agreed().size() + " agreed, "
                            + consensus.disputed().size() + " disputed.",
                    /* mentions */ List.of(),
                    /* refs */ List.of(),
                    "consensus",
                    outcome.json(),
                    outcome.costMilli(),
                    Instant.now()));

            Instant afterConsensus = Instant.now();
            for (ConsensusFinding f : consensus.agreed()) {
                reviewStore.saveFinding(new ReviewFinding(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        f.path(), f.line(),
                        f.severity(),
                        ReviewFindingStatus.AGREED,
                        f.body(),
                        /* resolution */ null,
                        /* postedCommentId */ null,
                        afterConsensus));
            }
            for (ConsensusFinding f : consensus.disputed()) {
                // Reviewer attribution lands as a prefix on the body
                // so the publish UI can show "@Claude said: ..." for
                // disputed picks without a new column on the row.
                String body = "[" + f.reporterPersona() + "] " + f.body();
                reviewStore.saveFinding(new ReviewFinding(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        f.path(), f.line(),
                        f.severity(),
                        ReviewFindingStatus.DISPUTED,
                        body,
                        /* resolution */ null,
                        /* postedCommentId */ null,
                        afterConsensus));
            }
            pass = withCost(pass, spentMilli);
            reviewStore.savePass(pass);

            if (budgetHit) {
                reviewStore.saveMessage(new ReviewMessage(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        moderator.id(),
                        ReviewPhase.CONSENSUS,
                        /* round */ 0,
                        "Budget cap reached. " + consensus.agreed().size() + " findings agreed; "
                                + consensus.disputed().size()
                                + " escalated to arbitration without full cross-review.",
                        /* mentions */ List.of(),
                        /* refs */ List.of(),
                        /* costUsdMilli */ 0L,
                        Instant.now()));
            }

            // Bounded debate over the DISPUTED findings — capped on
            // rounds (pass.roundCap()) and cost per finding. A finding the
            // panel reaffirms collapses to AGREED; the rest stay DISPUTED
            // for the ballot. Skipped when the budget is already spent,
            // since the debate is itself LLM calls.
            if (!budgetHit) {
                spentMilli = runDebates(pass, panel, reviewerSeats, spentMilli);
                pass = withCost(pass, spentMilli);
            }
            suggested = suggestedVerdictForConsensus(consensus);
        }

        // 9. Final phase. Panels that produced disputed findings park
        //    at ARBITRATE so the human picks each contested item via
        //    the ballot; otherwise the pass terminates straight away
        //    and the publish form unlocks.
        boolean hasDisputed = reviewStore.listFindingsForPass(pass.id()).stream()
                .anyMatch(f -> f.status() == ReviewFindingStatus.DISPUTED);
        ReviewPhase finalPhase = hasDisputed ? ReviewPhase.ARBITRATE : ReviewPhase.TERMINATE;
        ReviewPass finalPass = new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(),
                finalPhase,
                pass.round(),
                pass.roundCap(),
                pass.costCapMilli(),
                pass.costUsdMilli(),
                suggested,
                pass.createdAt(),
                /* endedAt */ hasDisputed ? null : Instant.now());
        reviewStore.savePass(finalPass);

        return buildDetail(finalPass);
    }

    /**
     * Resolve one disputed finding via the arbitration ballot. The
     * human picks {@code include} to keep the call (status →
     * ARBITRATED) or {@code drop} to discard it (status → DROPPED).
     * Once every DISPUTED finding on the pass is resolved the pass
     * transitions out of ARBITRATE into TERMINATE so the publish
     * form unlocks.
     */
    @Transactional
    public ReviewPassDetail arbitrateFinding(String passId, String findingId, String resolution)
    {
        requireNonNull(passId, "passId is null");
        requireNonNull(findingId, "findingId is null");
        requireNonNull(resolution, "resolution is null");

        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no review pass: " + passId));
        if (pass.phase() != ReviewPhase.ARBITRATE) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "pass " + passId + " is not in ARBITRATE — current phase is " + pass.phase());
        }
        ReviewFinding finding = reviewStore.findFindingById(findingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no finding: " + findingId));
        if (!finding.reviewPassId().equals(passId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "finding " + findingId + " does not belong to pass " + passId);
        }
        if (finding.status() != ReviewFindingStatus.DISPUTED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "finding " + findingId + " is not DISPUTED — already " + finding.status());
        }
        ReviewFindingStatus next;
        switch (resolution.toLowerCase(Locale.ROOT)) {
            case "include" -> next = ReviewFindingStatus.ARBITRATED;
            case "drop" -> next = ReviewFindingStatus.DROPPED;
            default -> throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "resolution must be 'include' or 'drop'");
        }
        reviewStore.saveFinding(new ReviewFinding(
                finding.id(), finding.reviewPassId(),
                finding.path(), finding.line(),
                finding.severity(),
                next,
                finding.body(),
                /* resolution */ resolution.toLowerCase(Locale.ROOT),
                finding.postedCommentId(),
                finding.createdAt(),
                finding.debateStatus(), finding.debateRounds()));

        // Once no DISPUTED findings remain, the pass falls through to
        // TERMINATE so the publish form unlocks.
        boolean stillDisputed = reviewStore.listFindingsForPass(passId).stream()
                .anyMatch(f -> f.status() == ReviewFindingStatus.DISPUTED);
        if (!stillDisputed) {
            ReviewPass terminated = new ReviewPass(
                    pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                    pass.headSha(),
                    ReviewPhase.TERMINATE,
                    pass.round(), pass.roundCap(),
                    pass.costCapMilli(), pass.costUsdMilli(),
                    pass.verdict(),
                    pass.createdAt(),
                    /* endedAt */ Instant.now());
            reviewStore.savePass(terminated);
            log.info("Review pass {} arbitration complete; transitioned to TERMINATE", passId);
        }
        return findPassWithDetail(passId).orElseThrow();
    }

    /** Configured reviewers form the panel. Capped at 3 — design
     *  open-decision lands at "2 sweet spot, 3 high-stakes, more
     *  rarely worth it". Panel-of-1 falls back to the Phase 1 single-
     *  reviewer path through the same {@code startReviewOnPr}.
     *
     *  <p>When {@code explicitIds} is non-empty, only reviewers whose
     *  {@code providerId()} appears in that list are seated (still
     *  capped at 3 and still only configured ones). An empty/null
     *  list reverts to "all configured reviewers" — the scheduled +
     *  one-click paths use that default.
     */
    /** One seat on the panel. Wraps the underlying {@link LlmReviewer}
     *  with an optional persona prompt (the reviewing "voice" the
     *  Start Review dialog picked) and a display label that shows on
     *  the participant chip + kickoff message. */
    record PanelMember(LlmReviewer reviewer, String personaPrompt, String displayLabel, boolean lead) {}

    /** Resolves the panel for a review pass. Two paths:
     *  <ol>
     *    <li>Persona path (new) — opts.personaIds + opts.providerForPersonas
     *        are set. Look up each persona, attach it to the chosen
     *        provider's reviewer, and seat one member per persona.</li>
     *    <li>Legacy path — fall back to the per-provider configured
     *        reviewers, optionally filtered by opts.panelProviderIds.</li>
     *  </ol>
     *  Either way the result is capped at 3 to match the design doc's
     *  panel-size invariant. */
    private List<PanelMember> resolvePanelMembers(StartOptions opts)
    {
        if (opts.hasSeats()) {
            return resolveSeatPanel(opts.seats());
        }
        if (opts.hasPersonas()) {
            return resolvePersonaPanel(opts);
        }
        String leadId = opts.leadId();
        return resolveLegacyPanel(opts.panelProviderIds()).stream()
                .map(r -> new PanelMember(r, null, r.displayName(),
                        leadId != null && r.providerId().equalsIgnoreCase(leadId)))
                .toList();
    }

    /**
     * Build the panel from an explicit seat list (the composition flow):
     * each seat is a model paired with a persona, a typed prompt, or
     * neither. Capped at 3. If no seat is flagged lead, the first seat
     * leads.
     */
    private List<PanelMember> resolveSeatPanel(List<PanelSeat> seats)
    {
        List<PanelMember> members = new ArrayList<>();
        for (PanelSeat seat : seats) {
            LlmReviewer reviewer = requireConfiguredReviewer(seat.providerId());
            String prompt;
            String label;
            if (seat.personaId() != null && !seat.personaId().isBlank()) {
                ReviewerPersona p = personas.findById(seat.personaId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatusCode.valueOf(412),
                                "Persona '" + seat.personaId() + "' not found or has been deleted."));
                if (!p.active()) {
                    continue;
                }
                prompt = p.systemPrompt();
                label = p.name();
            }
            else if (seat.customPrompt() != null && !seat.customPrompt().isBlank()) {
                prompt = seat.customPrompt();
                label = "Custom · " + reviewer.displayName();
            }
            else {
                prompt = null;
                label = reviewer.displayName();
            }
            members.add(new PanelMember(reviewer, prompt, label, seat.lead()));
            if (members.size() == 3) {
                break;
            }
        }
        if (members.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "No reviewers selected. Add at least one in the Start Review dialog.");
        }
        // Exactly one lead: if the dialog flagged none (or every flagged
        // seat fell out as an inactive persona), the first seat leads.
        if (members.stream().noneMatch(PanelMember::lead)) {
            PanelMember first = members.get(0);
            members.set(0, new PanelMember(
                    first.reviewer(), first.personaPrompt(), first.displayLabel(), true));
        }
        return members;
    }

    /** Resolve a reviewer by provider id, 412-ing when it's unknown or
     *  has no API key — shared by the seat + persona panel paths. */
    private LlmReviewer requireConfiguredReviewer(String providerId)
    {
        LlmReviewer reviewer = reviewers.all().stream()
                .filter(r -> r.providerId().equalsIgnoreCase(providerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(412),
                        "Provider '" + providerId + "' is not registered. "
                                + "Pick a different model in the Start Review dialog."));
        if (!reviewer.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "Provider '" + reviewer.displayName() + "' has no API key configured. "
                            + "Add one in Settings → AI review.");
        }
        return reviewer;
    }

    private List<PanelMember> resolvePersonaPanel(StartOptions opts)
    {
        LlmReviewer reviewer = requireConfiguredReviewer(opts.providerForPersonas());
        List<PanelMember> members = new ArrayList<>();
        for (String pid : opts.personaIds()) {
            ReviewerPersona p = personas.findById(pid)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(412),
                            "Persona '" + pid + "' not found or has been deleted."));
            if (!p.active()) {
                continue;
            }
            // Per-run lead override wins when the dialog marked one;
            // otherwise fall back to the persona's configured LEAD role.
            boolean lead = opts.leadId() != null
                    ? pid.equals(opts.leadId())
                    : p.role() == ReviewerPersonaRole.LEAD;
            members.add(new PanelMember(reviewer, p.systemPrompt(), p.name(), lead));
            if (members.size() == 3) {
                break;
            }
        }
        if (members.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "No active personas were selected. Pick at least one in the Start Review dialog.");
        }
        return members;
    }

    private List<LlmReviewer> resolveLegacyPanel(List<String> explicitIds)
    {
        List<LlmReviewer> configured = reviewers.all().stream()
                .filter(LlmReviewer::isConfigured)
                .toList();
        if (configured.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "No LLM provider has an API key configured. "
                            + "Add one in Settings → AI review.");
        }
        List<LlmReviewer> selected;
        if (explicitIds == null || explicitIds.isEmpty()) {
            selected = configured;
        }
        else {
            Set<String> wanted = new LinkedHashSet<>(explicitIds);
            selected = configured.stream()
                    .filter(r -> wanted.contains(r.providerId()))
                    .toList();
            if (selected.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(412),
                        "None of the requested reviewers are configured. "
                                + "Pick configured ones in the dialog or add a key in Settings → AI review.");
            }
        }
        return selected.size() > 3 ? selected.subList(0, 3) : selected;
    }

    /** Threshold above which a single reviewer call gets split per
     *  file. The existing {@link com.bytequay.app.service.ai.ReviewPrompt}
     *  truncates over 200K to fit context windows; fanning out below
     *  that keeps each call comfortably under the cap and avoids
     *  losing the tail of a large PR to truncation. */
    static final int MAX_DIFF_CHARS_PER_CALL = 60_000;

    /** Dispatches every panel reviewer against the same request on
     *  its own thread so no reviewer sees another's draft before
     *  finishing. Returns outputs in the same iteration order as the
     *  seats list so the persistence side keeps a stable transcript
     *  ordering.
     *
     *  <p>For diffs over {@link #MAX_DIFF_CHARS_PER_CALL}, each
     *  reviewer's call fans out per file inside the worker thread —
     *  parallelism stays at the panel level (so we don't hammer
     *  one provider with N concurrent calls) but the LLM context
     *  per call stays bounded, which is the actual failure mode on
     *  large PRs. */
    private Map<ReviewParticipant, ReviewOutput> runIndependentInParallel(
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            ReviewRequest request)
    {
        ExecutorService executor = Executors.newFixedThreadPool(panel.size());
        try {
            List<CompletableFuture<ReviewOutput>> futures = new ArrayList<>();
            for (PanelMember m : panel) {
                // Each panel member gets its own request shape so its
                // persona prompt rides into the system message — same
                // base diff / metadata as everyone else, just a
                // different reviewing voice.
                ReviewRequest perMember = m.personaPrompt() == null
                        ? request
                        : request.withPersonaPrompt(m.personaPrompt());
                futures.add(CompletableFuture.supplyAsync(
                        () -> runOneReviewerMaybeFanOut(m.reviewer(), perMember), executor));
            }
            Map<ReviewParticipant, ReviewOutput> result = new LinkedHashMap<>();
            for (int i = 0; i < panel.size(); i++) {
                // join() rethrows the worker's RuntimeException
                // wrapped in CompletionException — unwrap so the
                // outer catch surfaces the provider's actual message.
                try {
                    result.put(seats.get(i), futures.get(i).join());
                }
                catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw e;
                }
            }
            return result;
        }
        finally {
            executor.shutdown();
        }
    }

    /** One reviewer's call against a request — fanning out per file
     *  when the diff is large enough that a single call would blow
     *  the LLM's context window. Falls through to the existing one-
     *  shot path for small diffs and for single-huge-file diffs that
     *  can't be sliced further. Per-file errors are logged and
     *  skipped so one bad chunk doesn't tank the whole review. */
    private ReviewOutput runOneReviewerMaybeFanOut(LlmReviewer reviewer, ReviewRequest base)
    {
        String diff = base.diff() == null ? "" : base.diff();
        if (diff.length() <= MAX_DIFF_CHARS_PER_CALL) {
            return reviewer.review(base);
        }
        List<String> chunks = splitDiffByFile(diff);
        if (chunks.size() <= 1) {
            // Single mega-file — ReviewPrompt's own truncation will
            // catch it. Fanning out wouldn't help here.
            return reviewer.review(base);
        }
        log.info("Diff is {} chars across {} files — fanning out per file for {}",
                diff.length(), chunks.size(), reviewer.providerId());
        List<ReviewOutput.LineComment> mergedComments = new ArrayList<>();
        StringBuilder mergedSummary = new StringBuilder();
        String modelName = "";
        for (String chunk : chunks) {
            ReviewRequest perFile = new ReviewRequest(
                    base.repo(), base.number(),
                    base.title(), base.body(),
                    base.headSha(), chunk,
                    base.skillContext());
            try {
                ReviewOutput out = reviewer.review(perFile);
                if (out.summary() != null && !out.summary().isBlank()) {
                    if (mergedSummary.length() > 0) {
                        mergedSummary.append("\n\n");
                    }
                    mergedSummary.append(out.summary().trim());
                }
                if (out.comments() != null) {
                    mergedComments.addAll(out.comments());
                }
                if (out.modelName() != null && !out.modelName().isBlank()) {
                    modelName = out.modelName();
                }
            }
            catch (RuntimeException e) {
                // One file failing shouldn't drop the entire review.
                log.warn("Per-file reviewer call failed (reviewer={}, chunk len={}): {}",
                        reviewer.providerId(), chunk.length(), e.getMessage());
            }
        }
        return new ReviewOutput(
                mergedSummary.toString(),
                mergedComments,
                reviewer.providerId(),
                modelName);
    }

    /** Split a unified diff on {@code diff --git} boundaries, keeping
     *  the boundary line as the head of each chunk. Empty or blank
     *  chunks are dropped. */
    /** Prepend the user-editable review persona (when set) to the
     *  supplied skill-context body. Empty persona / empty base both
     *  no-op cleanly so callers don't have to special-case either.
     *  The persona lands above the base body under its own labelled
     *  header so the LLM treats it as orchestrator-level guidance
     *  rather than mixing it with repo-specific facts. */
    String composeSkillContext(String baseSkill)
    {
        String persona = appSettings.get(Key.REVIEW_PERSONA).orElse("").strip();
        String base = baseSkill == null ? "" : baseSkill.strip();
        if (persona.isEmpty()) {
            return base.isEmpty() ? null : base;
        }
        if (base.isEmpty()) {
            return "Reviewer persona:\n" + persona;
        }
        return "Reviewer persona:\n" + persona + "\n\n" + base;
    }

    static List<String> splitDiffByFile(String diff)
    {
        String[] parts = diff.split("(?m)^(?=diff --git )");
        List<String> chunks = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                chunks.add(p);
            }
        }
        return chunks;
    }

    /** Per-finding debate cost ceiling in milli-USD ($0.10). A single
     *  finding's round-robin never spends past this, nor past the pass's
     *  remaining budget. */
    private static final long DEBATE_COST_CAP_MILLI = 100L;

    /** Bounded debate over the pass's DISPUTED findings. Each finding
     *  (severity desc, then a stable path/line order) gets a round-robin
     *  of reviewer turns capped at {@code pass.roundCap()} rounds and the
     *  per-finding cost ceiling. A finding the panel unanimously reaffirms
     *  collapses to AGREED ({@code debate_status = converged}); one that
     *  stalls on rounds or cost stays DISPUTED ({@code stalled_rounds} /
     *  {@code stalled_cost}) for the human ballot. Returns the running
     *  pass spend. */
    private long runDebates(
            ReviewPass pass,
            List<PanelMember> panel,
            List<ReviewParticipant> seats,
            long spentMilli)
    {
        long capMilli = pass.costCapMilli();
        int roundCap = pass.roundCap();
        List<ReviewFinding> disputed = reviewStore.listFindingsForPass(pass.id()).stream()
                .filter(f -> f.status() == ReviewFindingStatus.DISPUTED)
                .sorted(Comparator
                        .comparingInt((ReviewFinding f) -> severityWeight(f.severity())).reversed()
                        .thenComparing(f -> f.path() == null ? "" : f.path())
                        .thenComparingInt(f -> f.line() == null ? 0 : f.line()))
                .toList();
        if (disputed.isEmpty() || roundCap <= 0) {
            return spentMilli;
        }
        // The lead seat also runs the Phase-D convergence-judge call.
        PanelMember leadMember = panel.stream()
                .filter(PanelMember::lead)
                .findFirst()
                .orElse(panel.get(0));
        reviewStore.savePass(withCost(withPhase(pass, ReviewPhase.DEBATE, /* endedAt */ null), spentMilli));
        for (ReviewFinding finding : disputed) {
            long perDebateBudget = Math.min(DEBATE_COST_CAP_MILLI, Math.max(0L, capMilli - spentMilli));
            if (perDebateBudget <= 0L) {
                // Pass budget already spent — leave the finding disputed.
                reviewStore.saveFinding(withDebate(finding, "stalled_cost", finding.debateRounds()));
                continue;
            }
            DebateOutcome outcome = debateFinding(pass, panel, seats, leadMember, finding, roundCap, perDebateBudget);
            spentMilli += outcome.costMilli();
            reviewStore.saveFinding(outcome.converged()
                    ? resolveConverged(finding, outcome.rounds())
                    : withDebate(finding, outcome.status(), outcome.rounds()));
        }
        return spentMilli;
    }

    /** Round-robin debate over one DISPUTED finding. Each reviewer sees
     *  only the finding under debate plus this debate's prior turns —
     *  never the full panel history — so the context stays bounded. Ends
     *  on unanimous "agree" (converged), the round cap, or the per-finding
     *  cost cap. */
    private DebateOutcome debateFinding(
            ReviewPass pass,
            List<PanelMember> panel,
            List<ReviewParticipant> seats,
            PanelMember lead,
            ReviewFinding finding,
            int roundCap,
            long budgetMilli)
    {
        long spent = 0L;
        int roundsRun = 0;
        boolean budgetOut = false;
        boolean converged = false;
        Map<String, String> latestStance = new LinkedHashMap<>();
        List<String> priorTurns = new ArrayList<>();
        for (int round = 1; round <= roundCap; round++) {
            roundsRun = round;
            for (int i = 0; i < seats.size(); i++) {
                if (spent >= budgetMilli) {
                    budgetOut = true;
                    break;
                }
                ReviewParticipant seat = seats.get(i);
                PanelMember member = panel.get(i);
                LlmCompletion c = safeComplete(
                        member.reviewer(),
                        debateSystemPrompt(),
                        debateUserPrompt(finding, member, priorTurns),
                        "debate");
                if (c == null) {
                    continue;
                }
                long cost = ModelPricing.estimateCostMilli(c.modelName(), c.tokensIn(), c.tokensOut());
                spent += cost;
                DebateTurn turn = parseDebateTurn(validJsonObjectOrNull(c.text()));
                latestStance.put(seat.id(), turn.stance());
                boolean noComment = turn.comment() == null || turn.comment().isBlank();
                priorTurns.add("[" + member.displayLabel() + "] " + turn.stance()
                        + (noComment ? "" : ": " + turn.comment()));
                // Parse any @mention / #ref the reviewer addressed in
                // its comment so the transcript and a future moderator
                // turn can resolve them without re-scanning prose.
                MentionRefParser.Parsed addressed =
                        MentionRefParser.parse(noComment ? "" : turn.comment());
                List<String> mentionIds = MentionRefParser.resolveMentions(addressed.mentionLabels(), seats);
                List<String> refTargets = addressed.refs().stream()
                        .map(MentionRefParser::encodeRef)
                        .toList();
                reviewStore.saveMessage(new ReviewMessage(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        seat.id(),
                        ReviewPhase.DEBATE,
                        round,
                        noComment ? turn.stance() : turn.comment(),
                        mentionIds,
                        refTargets,
                        "debate_turn",
                        debateTurnPayload(finding.id(), turn.stance()),
                        cost,
                        Instant.now()));
            }
            if (budgetOut) {
                break;
            }
            // Phase D — the lead moderator judges convergence. A failed
            // or out-of-bounds verdict falls back to the deterministic
            // unanimous-"agree" check, so the moderator is a strict
            // upgrade that can never wedge the debate.
            ModeratorResult mod = judgeConvergence(lead, finding, priorTurns);
            spent += mod.costMilli();
            if (mod.verdict() == ModeratorVerdict.CONVERGED) {
                converged = true;
                break;
            }
            if (mod.verdict() == ModeratorVerdict.STALLED) {
                break;
            }
            if (mod.verdict() == null && everyoneAgrees(latestStance, seats)) {
                converged = true;
                break;
            }
            // CONTINUE, or fallback-not-yet-converged → another round.
        }
        String status = converged ? null : (budgetOut ? "stalled_cost" : "stalled_rounds");
        return new DebateOutcome(converged, status, roundsRun, spent);
    }

    private static boolean everyoneAgrees(Map<String, String> stance, List<ReviewParticipant> seats)
    {
        for (ReviewParticipant s : seats) {
            if (!"agree".equalsIgnoreCase(stance.get(s.id()))) {
                return false;
            }
        }
        return true;
    }

    /** Phase D — the lead moderator judges whether this finding's debate
     *  has resolved. Returns the verdict (or null to signal fallback to
     *  the deterministic unanimous-"agree" check) plus the call cost. */
    private ModeratorResult judgeConvergence(PanelMember lead, ReviewFinding finding, List<String> priorTurns)
    {
        LlmCompletion c = safeComplete(
                lead.reviewer(),
                moderatorSystemPrompt(),
                moderatorUserPrompt(finding, priorTurns),
                "moderator");
        if (c == null) {
            return new ModeratorResult(null, 0L);
        }
        long cost = ModelPricing.estimateCostMilli(c.modelName(), c.tokensIn(), c.tokensOut());
        ModeratorVerdict verdict = null;
        String json = validJsonObjectOrNull(c.text());
        if (json != null) {
            try {
                verdict = parseVerdict(objectMapper.readValue(json, ModeratorTurn.class).verdict());
            }
            catch (IOException e) {
                verdict = null;
            }
        }
        return new ModeratorResult(verdict, cost);
    }

    private static ModeratorVerdict parseVerdict(String raw)
    {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "converged" -> ModeratorVerdict.CONVERGED;
            case "continue" -> ModeratorVerdict.CONTINUE;
            case "stalled" -> ModeratorVerdict.STALLED;
            default -> null;
        };
    }

    private static String moderatorSystemPrompt()
    {
        return """
                You are the lead moderator of a code-review panel, judging whether a debate over \
                one disputed finding has resolved. Read the finding and the debate turns so far, \
                then output STRICT JSON only: {"verdict":"converged"|"continue"|"stalled",\
                "reason":string}. 'converged' = the panel now agrees the finding is valid and \
                should be raised; 'continue' = another round could still move them; 'stalled' = \
                they are entrenched and more rounds won't help. Output ONLY the JSON object — no \
                prose, no markdown fences.""";
    }

    private static String moderatorUserPrompt(ReviewFinding finding, List<String> priorTurns)
    {
        String anchor = (finding.path() == null ? "(whole PR)" : finding.path())
                + (finding.line() == null ? "" : ":" + finding.line());
        return "Disputed finding #finding-" + finding.id() + " — " + anchor
                + " [" + finding.severity().dbValue() + "]:\n"
                + finding.body() + "\n\n"
                + "Debate so far:\n" + String.join("\n", priorTurns)
                + "\n\nHas this debate converged? Return the JSON verdict.";
    }

    private DebateTurn parseDebateTurn(String json)
    {
        if (json == null) {
            return new DebateTurn("hold", null);
        }
        try {
            DebateTurn t = objectMapper.readValue(json, DebateTurn.class);
            String stance = t.stance() == null ? "hold" : t.stance().toLowerCase(Locale.ROOT);
            if (!"agree".equals(stance) && !"partial".equals(stance) && !"hold".equals(stance)) {
                stance = "hold";
            }
            return new DebateTurn(stance, t.comment());
        }
        catch (IOException e) {
            return new DebateTurn("hold", null);
        }
    }

    private String debateTurnPayload(String findingId, String stance)
    {
        try {
            return objectMapper.writeValueAsString(Map.of("findingId", findingId, "stance", stance));
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * Inline only the bodies a message addressed via {@code #refs} —
     * the bounded context the moderator hands a reviewer for its next
     * turn instead of the full panel scrollback. Refs are the
     * {@code kind:id} strings stored on {@link ReviewMessage#refs()};
     * unknown or dangling refs are skipped.
     */
    String assembleReferencedContext(List<String> refTargets)
    {
        if (refTargets == null || refTargets.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String encoded : refTargets) {
            int sep = encoded.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String kind = encoded.substring(0, sep);
            String id = encoded.substring(sep + 1);
            if ("msg".equals(kind)) {
                reviewStore.findMessageById(id).ifPresent(m ->
                        sb.append("#msg-").append(id).append(": ").append(m.body()).append('\n'));
            }
            else if ("finding".equals(kind)) {
                reviewStore.findFindingById(id).ifPresent(f ->
                        sb.append("#finding-").append(id).append(": ").append(f.body()).append('\n'));
            }
        }
        return sb.toString();
    }

    private static String debateSystemPrompt()
    {
        return """
                You are one reviewer on a code-review panel debating a single DISPUTED finding — \
                the panel split on whether it should be raised. Read the finding and the debate so \
                far, then respond with STRICT JSON only: \
                {"stance":"agree"|"partial"|"hold","comment":string}. 'agree' = the finding is valid \
                and should be raised; 'partial' = valid but with the caveat in your comment; 'hold' = \
                you are not convinced. Keep 'comment' under ~60 words. Output ONLY the JSON object — \
                no prose, no markdown fences.""";
    }

    private static String debateUserPrompt(ReviewFinding finding, PanelMember member, List<String> priorTurns)
    {
        String anchor = (finding.path() == null ? "(whole PR)" : finding.path())
                + (finding.line() == null ? "" : ":" + finding.line());
        return "You are reviewer \"" + member.displayLabel() + "\".\n\n"
                + "Disputed finding #finding-" + finding.id() + " — " + anchor
                + " [" + finding.severity().dbValue() + "]:\n"
                + finding.body() + "\n\n"
                + (priorTurns.isEmpty()
                        ? "No turns yet — open the debate."
                        : "Debate so far:\n" + String.join("\n", priorTurns))
                + "\n\nRespond directly: agree, partial agree (with the caveat), or hold. "
                + "Return the JSON object.";
    }

    private static ReviewFinding withDebate(ReviewFinding f, String debateStatus, int debateRounds)
    {
        return new ReviewFinding(
                f.id(), f.reviewPassId(), f.path(), f.line(), f.severity(),
                f.status(), f.body(), f.resolution(), f.postedCommentId(), f.createdAt(),
                debateStatus, debateRounds);
    }

    private static ReviewFinding resolveConverged(ReviewFinding f, int debateRounds)
    {
        return new ReviewFinding(
                f.id(), f.reviewPassId(), f.path(), f.line(), f.severity(),
                ReviewFindingStatus.AGREED, f.body(), f.resolution(), f.postedCommentId(), f.createdAt(),
                "converged", debateRounds);
    }

    /** Severity weight (higher = more severe) so the debate visits the
     *  spiciest disputed findings first. Explicit weights avoid
     *  {@code Enum.ordinal()}, which Error Prone flags as fragile. */
    private static int severityWeight(ReviewFindingSeverity s)
    {
        return switch (s) {
            case BLOCKER -> 4;
            case MAJOR -> 3;
            case NIT -> 2;
            case QUESTION -> 1;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DebateTurn(String stance, String comment) {}

    private record DebateOutcome(boolean converged, String status, int rounds, long costMilli) {}

    private enum ModeratorVerdict { CONVERGED, CONTINUE, STALLED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModeratorTurn(String verdict, String reason) {}

    /** Moderator verdict (null = fall back to the deterministic check)
     *  plus the cost of the moderator call. */
    private record ModeratorResult(ModeratorVerdict verdict, long costMilli) {}

    // ── LLM-driven cross-review + consensus ──────────────────────────

    /** Run a structured orchestration call, swallowing provider errors
     *  into a null return so one flaky reviewer can't tank the pass —
     *  the caller treats null as "this reviewer abstained". */
    private LlmCompletion safeComplete(LlmReviewer reviewer, String system, String user, String stage)
    {
        try {
            return reviewer.complete(system, user);
        }
        catch (RuntimeException e) {
            log.warn("Review {} call failed for {}: {}", stage, reviewer.providerId(), e.getMessage());
            return null;
        }
    }

    private static String crossReviewSystemPrompt()
    {
        return """
                You are one reviewer on a multi-reviewer code-review panel. You have already \
                written your own independent review of a pull request; now you see every \
                reviewer's findings on the same diff. Respond with STRICT JSON only: \
                {"agree":[string],"dispute":[{"finding":string,"counter":string,"evidence":string}],\
                "open_questions":[string]}. 'agree' lists findings (yours or others') you endorse; \
                'dispute' lists findings you believe are wrong, each with a short counter-argument \
                and the concrete evidence (file/line/behaviour) for it; 'open_questions' lists \
                genuine uncertainties that need human judgement. Be concise. Output ONLY the JSON \
                object — no prose, no markdown fences.""";
    }

    private String crossReviewUserPrompt(
            PanelMember member,
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            Map<ReviewParticipant, ReviewOutput> outputs,
            ReviewRequest request)
    {
        return "Pull request: " + request.repo() + "#" + request.number() + "\n\n"
                + "You are reviewer \"" + member.displayLabel() + "\". Here are all panel "
                + "reviewers' independent findings on this diff:\n\n"
                + renderPanelFindings(seats, panel, outputs)
                + "\nWhich findings do you agree with? Which do you dispute, and why? "
                + "What genuine open questions remain? Respond with the JSON envelope.";
    }

    private static String renderPanelFindings(
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            Map<ReviewParticipant, ReviewOutput> outputs)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seats.size(); i++) {
            ReviewOutput out = outputs.get(seats.get(i));
            sb.append("### ").append(panel.get(i).displayLabel()).append('\n');
            if (out != null && out.summary() != null && !out.summary().isBlank()) {
                sb.append("Summary: ").append(out.summary().strip()).append('\n');
            }
            List<ReviewOutput.LineComment> comments = out == null || out.comments() == null
                    ? List.of() : out.comments();
            if (comments.isEmpty()) {
                sb.append("(no line findings)\n");
            }
            for (ReviewOutput.LineComment c : comments) {
                sb.append("- [").append(c.severity()).append("] ")
                        .append(c.file() == null ? "(whole PR)" : c.file());
                if (c.line() > 0) {
                    sb.append(':').append(c.line());
                }
                sb.append(" — ").append(c.body() == null ? "" : c.body().strip()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String consensusSystemPrompt()
    {
        return """
                You are the lead reviewer reconciling a multi-reviewer code-review panel into a \
                single resolved finding set. You see every reviewer's independent findings and \
                their cross-review reactions. Output STRICT JSON only: \
                {"findings":[{"path":string|null,"line":number|null,\
                "severity":"blocker"|"major"|"nit"|"question","body":string,\
                "status":"agreed"|"disputed","reporter":string}]}. Mark a finding 'agreed' when \
                the panel converges on it — collapse duplicates at the same location into one row \
                carrying the most severe reading. Mark it 'disputed' when reviewers genuinely \
                split. Drop findings no reviewer stands behind. 'reporter' names the reviewer(s) \
                behind the finding. Output ONLY the JSON object — no prose, no markdown fences.""";
    }

    private String consensusUserPrompt(
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            Map<ReviewParticipant, ReviewOutput> outputs,
            List<String> envelopes,
            ReviewRequest request)
    {
        String reactions = envelopes.isEmpty()
                ? "(no cross-review reactions were returned)"
                : String.join("\n\n", envelopes);
        return "Pull request: " + request.repo() + "#" + request.number() + "\n\n"
                + "Independent findings:\n\n"
                + renderPanelFindings(seats, panel, outputs)
                + "\nCross-review reactions:\n\n" + reactions
                + "\n\nProduce the resolved finding set as the JSON object.";
    }

    /** Run the lead's CONSENSUS call and fold its envelope into a
     *  {@link ConsensusResult}. On a failed or unparseable call every
     *  independent finding falls through as DISPUTED so the human
     *  arbitrates rather than anything being silently dropped. */
    private ConsensusOutcome runConsensus(
            PanelMember lead,
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            Map<ReviewParticipant, ReviewOutput> outputs,
            List<String> envelopes,
            ReviewRequest request)
    {
        LlmCompletion c = safeComplete(
                lead.reviewer(),
                consensusSystemPrompt(),
                consensusUserPrompt(seats, panel, outputs, envelopes, request),
                "consensus");
        if (c == null) {
            return new ConsensusOutcome(allDisputed(seats, panel, outputs), null, 0L);
        }
        long cost = ModelPricing.estimateCostMilli(c.modelName(), c.tokensIn(), c.tokensOut());
        String json = validJsonObjectOrNull(c.text());
        if (json == null) {
            log.warn("Consensus call returned no parseable JSON — escalating all findings to disputed.");
            return new ConsensusOutcome(allDisputed(seats, panel, outputs), null, cost);
        }
        try {
            ConsensusEnvelope env = objectMapper.readValue(json, ConsensusEnvelope.class);
            return new ConsensusOutcome(foldConsensus(env), json, cost);
        }
        catch (IOException e) {
            log.warn("Consensus JSON did not bind — escalating all findings to disputed: {}", e.getMessage());
            return new ConsensusOutcome(allDisputed(seats, panel, outputs), json, cost);
        }
    }

    private static ConsensusResult foldConsensus(ConsensusEnvelope env)
    {
        List<ConsensusFinding> agreed = new ArrayList<>();
        List<ConsensusFinding> disputed = new ArrayList<>();
        List<ConsensusItem> items = env == null || env.findings() == null ? List.of() : env.findings();
        for (ConsensusItem it : items) {
            Integer line = it.line() != null && it.line() > 0 ? it.line() : null;
            ConsensusFinding f = new ConsensusFinding(
                    it.path(), line,
                    severityFromComment(it.severity()),
                    it.body() == null ? "" : it.body(),
                    it.reporter() == null || it.reporter().isBlank() ? "panel" : it.reporter());
            if ("agreed".equalsIgnoreCase(it.status())) {
                agreed.add(f);
            }
            else {
                disputed.add(f);
            }
        }
        return new ConsensusResult(List.copyOf(agreed), List.copyOf(disputed));
    }

    /** Fallback when the consensus call is unavailable: every reported
     *  finding becomes DISPUTED so it reaches the human ballot rather
     *  than being dropped or auto-agreed. */
    private static ConsensusResult allDisputed(
            List<ReviewParticipant> seats,
            List<PanelMember> panel,
            Map<ReviewParticipant, ReviewOutput> outputs)
    {
        List<ConsensusFinding> disputed = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            ReviewOutput out = outputs.get(seats.get(i));
            List<ReviewOutput.LineComment> comments = out == null || out.comments() == null
                    ? List.of() : out.comments();
            for (ReviewOutput.LineComment c : comments) {
                Integer line = c.line() > 0 ? c.line() : null;
                disputed.add(new ConsensusFinding(
                        c.file(), line,
                        severityFromComment(c.severity()),
                        c.body() == null ? "" : c.body(),
                        panel.get(i).displayLabel()));
            }
        }
        return new ConsensusResult(List.of(), List.copyOf(disputed));
    }

    /** First top-level {...} block of a model response that also parses
     *  as JSON, or null when there's none (the caller treats null as a
     *  failed / abstaining call). */
    private String validJsonObjectOrNull(String text)
    {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = text.substring(start, end + 1);
        try {
            objectMapper.readTree(json);
            return json;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static ReviewPass withCost(ReviewPass pass, long costUsdMilli)
    {
        return new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(), pass.phase(), pass.round(), pass.roundCap(),
                pass.costCapMilli(), costUsdMilli, pass.verdict(),
                pass.createdAt(), pass.endedAt());
    }

    private record ConsensusOutcome(ConsensusResult result, String json, long costMilli) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConsensusEnvelope(List<ConsensusItem> findings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConsensusItem(
            String path, Integer line, String severity, String body, String status, String reporter) {}

    private static String panelDisplayNames(List<PanelMember> panel)
    {
        return panel.stream().map(PanelMember::displayLabel).reduce(
                (a, b) -> a + " + " + b).orElse("");
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

    /**
     * Publish a terminated review pass to the PR as a GitHub review.
     * The user picks the verdict + which findings to include via the
     * panel UI's publish form; this method validates, composes the
     * GitHub payload, posts it, marks the selected findings
     * {@link ReviewFindingStatus#POSTED}, and transitions the pass
     * into {@link ReviewPhase#PUBLISHED}.
     *
     * <p>Findings with both a {@code path} and a positive {@code line}
     * become inline review comments; whole-PR notes (path or line
     * missing) fold into the review body so nothing the user picked is
     * silently dropped.
     */
    @Transactional
    public ReviewPassDetail publishPass(
            String passId, ReviewVerdict verdict, List<String> includedFindingIds)
    {
        requireNonNull(passId, "passId is null");
        requireNonNull(verdict, "verdict is null");
        requireNonNull(includedFindingIds, "includedFindingIds is null");

        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no review pass: " + passId));
        if (pass.phase() == ReviewPhase.PUBLISHED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "review pass " + passId + " is already published");
        }
        if (pass.phase() == ReviewPhase.ARBITRATE) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "review pass " + passId + " is at ARBITRATE — resolve disputed "
                            + "findings via the ballot before publishing");
        }

        List<ReviewFinding> allFindings = reviewStore.listFindingsForPass(passId);
        Set<String> includedIds = new LinkedHashSet<>(includedFindingIds);
        List<ReviewFinding> selected = new ArrayList<>();
        for (ReviewFinding f : allFindings) {
            if (includedIds.contains(f.id())) {
                selected.add(f);
            }
        }

        // Compose the review body: reviewer's INDEPENDENT summary
        // followed by any whole-PR / file-no-line findings as a list
        // so the user's selection doesn't lose them.
        String summary = reviewSummaryBody(pass);
        List<ReviewFinding> inlineable = new ArrayList<>();
        List<ReviewFinding> wholePr = new ArrayList<>();
        for (ReviewFinding f : selected) {
            if (f.path() != null && f.line() != null && f.line() > 0) {
                inlineable.add(f);
            }
            else {
                wholePr.add(f);
            }
        }
        String body = composeBody(summary, wholePr);

        List<ReviewLineComment> inlineComments = inlineable.stream()
                .map(f -> new ReviewLineComment(
                        f.path(),
                        Optional.empty(),
                        Optional.of(f.line()),
                        "RIGHT",
                        renderFindingBody(f)))
                .toList();

        CreateReviewCommand command = new CreateReviewCommand(
                pass.headSha() == null ? Optional.empty() : Optional.of(pass.headSha()),
                body.isBlank() ? Optional.empty() : Optional.of(body),
                verdict.dbValue().toUpperCase(Locale.ROOT),
                inlineComments);

        String pat = patResolver.resolve(pass.repoFullName());
        PullRequestRef ref = parseRef(pass.repoFullName(), pass.prNumber());
        try {
            pullRequests.createReview(pat, ref, command);
        }
        catch (RuntimeException e) {
            log.warn("Review pass {} publish to GitHub failed: {}", passId, e.getMessage());
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "GitHub rejected the review: " + e.getMessage(), e);
        }

        // GitHub doesn't hand back per-comment ids from the bulk
        // createReview endpoint, so we mark the rows POSTED without a
        // posted_comment_id. Phase 2+ may switch to per-finding posts
        // and capture ids.
        Instant publishedAt = Instant.now();
        for (ReviewFinding f : selected) {
            reviewStore.saveFinding(new ReviewFinding(
                    f.id(), f.reviewPassId(),
                    f.path(), f.line(),
                    f.severity(),
                    ReviewFindingStatus.POSTED,
                    f.body(),
                    f.resolution(),
                    /* postedCommentId */ null,
                    f.createdAt(),
                    f.debateStatus(), f.debateRounds()));
        }

        ReviewPass published = new ReviewPass(
                pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                pass.headSha(),
                ReviewPhase.PUBLISHED,
                pass.round(), pass.roundCap(),
                pass.costCapMilli(), pass.costUsdMilli(),
                verdict,
                pass.createdAt(),
                publishedAt);
        reviewStore.savePass(published);

        return buildDetail(published);
    }

    /** The reviewer's most-recent INDEPENDENT message is the natural
     *  body for the GitHub review. Falls back to a generated line if
     *  the transcript is empty (defensive — shouldn't happen on a
     *  terminated pass). */
    private String reviewSummaryBody(ReviewPass pass)
    {
        List<ReviewMessage> messages = reviewStore.listMessagesForPass(pass.id());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ReviewMessage m = messages.get(i);
            if (m.phase() == ReviewPhase.INDEPENDENT && m.body() != null && !m.body().isBlank()) {
                return m.body().strip();
            }
        }
        return "Review by ByteQuay panel.";
    }

    private static String composeBody(String summary, List<ReviewFinding> wholePr)
    {
        if (wholePr.isEmpty()) {
            return summary;
        }
        StringBuilder out = new StringBuilder(summary);
        out.append("\n\n**Whole-PR notes**\n");
        for (ReviewFinding f : wholePr) {
            out.append("- ").append(renderFindingBody(f)).append('\n');
        }
        return out.toString();
    }

    private static String renderFindingBody(ReviewFinding f)
    {
        // Inline a severity tag so the GitHub-side reader knows the
        // panel's call without clicking through to the review thread.
        return "[" + f.severity().dbValue() + "] " + (f.body() == null ? "" : f.body());
    }

    private ReviewPassDetail buildDetail(ReviewPass pass)
    {
        // Resolve the PR title from the local cache (no GitHub round-trip
        // — the panel page polls). Null when the PR isn't cached, in
        // which case the header falls back to repo#number.
        String prTitle = pullRequestStore
                .findIdByRepoAndNumber(pass.repoFullName(), pass.prNumber())
                .flatMap(pullRequestStore::findById)
                .map(PullRequest::title)
                .orElse(null);
        return new ReviewPassDetail(
                pass,
                prTitle,
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

    /** Single-reviewer verdict suggestion: any BLOCKER →
     *  REQUEST_CHANGES; any other finding → COMMENT; empty → APPROVE.
     *  The user confirms at the publish gate — this is just the
     *  one-click default. */
    private static ReviewVerdict suggestedVerdictForComments(List<ReviewOutput.LineComment> comments)
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

    /** Multi-reviewer verdict suggestion: agreed blocker →
     *  REQUEST_CHANGES; any agreed or disputed finding → COMMENT;
     *  fully clean panel → APPROVE. Disputed findings don't escalate
     *  to REQUEST_CHANGES on their own — the design wants the human
     *  to arbitrate disagreements rather than have one reviewer's
     *  unconfirmed call block a merge. */
    private static ReviewVerdict suggestedVerdictForConsensus(ConsensusResult consensus)
    {
        for (ConsensusFinding f : consensus.agreed()) {
            if (f.severity() == ReviewFindingSeverity.BLOCKER) {
                return ReviewVerdict.REQUEST_CHANGES;
            }
        }
        if (consensus.agreed().isEmpty() && consensus.disputed().isEmpty()) {
            return ReviewVerdict.APPROVE;
        }
        return ReviewVerdict.COMMENT;
    }

    private record ConsensusFinding(
            String path,
            Integer line,
            ReviewFindingSeverity severity,
            String body,
            String reporterPersona) {}
    private record ConsensusResult(
            List<ConsensusFinding> agreed,
            List<ConsensusFinding> disputed) {}

    /**
     * Caller-supplied options for {@link #startReviewOnPr(String, int, StartOptions)}.
     * All fields are optional via {@link #DEFAULT} — the scheduled
     * + one-click paths use the defaults and the dialog overrides
     * the ones the user touched.
     *
     * @param panelProviderIds explicit panel roster (provider ids).
     *                         Empty/null = use every configured
     *                         reviewer (capped at 3).
     * @param roundCap         maximum debate rounds before forcing
     *                         arbitration. {@code 3} matches the prior
     *                         hard-coded value.
     * @param costCapMilli     halt + summarise once this milli-USD
     *                         spend is reached. {@code 500} matches
     *                         the prior hard-coded value.
     * @param independentFirst when true (the design default), each
     *                         reviewer drafts before seeing peers'
     *                         takes. False would anchor on a shared
     *                         draft — currently informational; the
     *                         INDEPENDENT phase always runs first.
     */
    public record StartOptions(
            List<String> panelProviderIds,
            int roundCap,
            long costCapMilli,
            boolean independentFirst,
            List<String> personaIds,
            String providerForPersonas,
            /** Workspace the review thread is created in, so it shows in
             *  that workspace's thread list. Null falls back to
             *  ws-default (the scheduled / one-click paths). */
            String workspaceId,
            /** Per-run lead override picked in the dialog — a personaId on
             *  the persona path, a providerId on the legacy path. Null
             *  falls back to the persona's LEAD role (or the first panel
             *  member when no role info exists). */
            String leadId,
            /** Explicit panel composition — one entry per reviewer seat,
             *  each pairing a model with an optional persona or a typed
             *  prompt (see {@link PanelSeat}). When non-empty this is the
             *  authoritative panel and wins over personaIds /
             *  panelProviderIds; the scheduled / one-click paths leave it
             *  empty and fall back to the all-configured legacy panel. */
            List<PanelSeat> seats)
    {
        public static final StartOptions DEFAULT =
                new StartOptions(List.of(), 3, 500L, true, List.of(), null, null, null, List.of());

        /** Backward-compat constructor for the legacy 4-arg call sites
         *  (scheduled review, one-click "Review again"). They don't
         *  pick personas, so the persona fields default to empty. */
        public StartOptions(
                List<String> panelProviderIds,
                int roundCap,
                long costCapMilli,
                boolean independentFirst)
        {
            this(panelProviderIds, roundCap, costCapMilli, independentFirst,
                    List.of(), null, null, null, List.of());
        }

        /** Persona call site without an explicit workspace. */
        public StartOptions(
                List<String> panelProviderIds,
                int roundCap,
                long costCapMilli,
                boolean independentFirst,
                List<String> personaIds,
                String providerForPersonas)
        {
            this(panelProviderIds, roundCap, costCapMilli, independentFirst,
                    personaIds, providerForPersonas, null, null, List.of());
        }

        /** Persona call site with a workspace but no explicit lead. */
        public StartOptions(
                List<String> panelProviderIds,
                int roundCap,
                long costCapMilli,
                boolean independentFirst,
                List<String> personaIds,
                String providerForPersonas,
                String workspaceId)
        {
            this(panelProviderIds, roundCap, costCapMilli, independentFirst,
                    personaIds, providerForPersonas, workspaceId, null, List.of());
        }

        /** True when the dialog picked personas + a provider — the new
         *  flow. False = legacy flow (panel = LlmReviewers filtered by
         *  panelProviderIds). */
        public boolean hasPersonas()
        {
            return personaIds != null && !personaIds.isEmpty()
                    && providerForPersonas != null && !providerForPersonas.isBlank();
        }

        /** True when the dialog sent an explicit per-seat panel — the
         *  composition path that pairs each model with its own persona
         *  or typed prompt. */
        public boolean hasSeats()
        {
            return seats != null && !seats.isEmpty();
        }
    }

    /**
     * One reviewer seat in an explicitly-composed panel: a model
     * ({@code providerId}, required) paired with EITHER a predefined
     * review role ({@code personaId}) OR a free-typed instruction
     * ({@code customPrompt}). Both null = a raw model with the default
     * review prompt. {@code lead} marks the seat that runs consensus +
     * moderates debate (exactly one per panel; the resolver defaults to
     * the first seat when none is flagged).
     */
    public record PanelSeat(String providerId, String personaId, String customPrompt, boolean lead) {}

    /**
     * Roster entry surfaced to the assign-review-task dialog so the
     * frontend can render the panel chips without leaking provider-
     * specific shapes. {@code configured} mirrors the API-key check
     * that gates a reviewer from running.
     */
    public record RosterEntry(String providerId, String displayName, boolean configured) {}

    /** List every reviewer the registry knows about (configured
     *  first, alphabetised within each group) so the dialog can show
     *  unconfigured ones disabled with a hint. */
    public List<RosterEntry> roster()
    {
        return reviewers.all().stream()
                .map(r -> new RosterEntry(r.providerId(), r.displayName(), r.isConfigured()))
                .sorted((a, b) -> {
                    int byConfig = Boolean.compare(!a.configured(), !b.configured());
                    if (byConfig != 0) {
                        return byConfig;
                    }
                    return a.displayName().compareToIgnoreCase(b.displayName());
                })
                .toList();
    }
}
