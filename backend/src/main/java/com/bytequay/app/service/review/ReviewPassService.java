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
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final PatResolver patResolver;
    private final LlmReviewerRegistry reviewers;
    private final AppSettingsStore appSettings;

    public ReviewPassService(
            ThreadStore threadStore,
            ReviewStore reviewStore,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            LlmReviewerRegistry reviewers,
            AppSettingsStore appSettings)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    /**
     * Kick off a fresh review pass for the given PR. Synchronous —
     * even with multi-reviewer fan-out the call blocks until the
     * panel terminates so the controller can hand back a populated
     * detail. A future async / SSE wrapper can layer on top when the
     * round count starts to matter.
     */
    @Transactional
    public ReviewPassDetail startReviewOnPr(String repoFullName, int prNumber)
    {
        return startReviewOnPr(repoFullName, prNumber, StartOptions.DEFAULT);
    }

    /**
     * Variant of {@link #startReviewOnPr(String, int)} that honours
     * caller-specified panel selection + caps. The mockup-facing
     * "Assign review task" dialog calls this; the scheduled / one-
     * click paths keep the 2-arg overload with the registry defaults.
     */
    @Transactional
    public ReviewPassDetail startReviewOnPr(String repoFullName, int prNumber, StartOptions opts)
    {
        requireNonNull(repoFullName, "repoFullName is null");
        requireNonNull(opts, "opts is null");
        if (prNumber <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "prNumber must be a positive integer");
        }

        List<LlmReviewer> panel = resolvePanel(opts.panelProviderIds());

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
                .orElseGet(() -> panel.get(0).providerId());
        Thread thread = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.LOGIC_LOOP,
                panel.get(0).providerId(),
                /* agentSessionId */ null,
                "Review " + repoFullName + "#" + prNumber,
                ThreadStatus.RUNNING,
                reviewModel,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                now, now,
                /* endedAt */ null, /* errorMessage */ null,
                ThreadFlow.REVIEW,
                // Review threads stay in the default workspace today —
                // they're addressed by PR, not by workspace. When the
                // assign-review dialog grows a workspace picker this
                // becomes the chosen workspace.
                "ws-default",
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
        for (LlmReviewer r : panel) {
            ReviewParticipant seat = new ReviewParticipant(
                    UUID.randomUUID().toString(), pass.id(),
                    ReviewParticipantKind.REVIEWER,
                    /* credentialId */ r.providerId(),
                    r.displayName(),
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
                ? panel.get(0).displayName()
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

        // 6. Transition to INDEPENDENT and dispatch the panel.
        pass = withPhase(pass, ReviewPhase.INDEPENDENT, /* endedAt */ null);
        reviewStore.savePass(pass);

        ReviewRequest request = new ReviewRequest(
                repoFullName, prNumber,
                /* title */ null,
                raw.body(),
                raw.headSha(),
                diff,
                composeSkillContext(/* baseSkill */ null));
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
            ConsensusResult consensus = extractConsensus(reviewerSeats, outputs);
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
                        afterReviewer));
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
                        afterReviewer));
            }
            // CROSS_REVIEW phase + moderator announcement message.
            pass = withPhase(pass, ReviewPhase.CROSS_REVIEW, /* endedAt */ null);
            reviewStore.savePass(pass);
            reviewStore.saveMessage(new ReviewMessage(
                    UUID.randomUUID().toString(),
                    pass.id(),
                    moderator.id(),
                    ReviewPhase.CROSS_REVIEW,
                    /* round */ 0,
                    "Heuristic consensus extracted from the panel: "
                            + consensus.agreed().size() + " agreed, "
                            + consensus.disputed().size() + " disputed.",
                    /* mentions */ List.of(),
                    /* refs */ List.of(),
                    /* costUsdMilli */ 0L,
                    Instant.now()));

            // Bounded LLM debate over the disputed items, capped at
            // pass.roundCap(). Each round each reviewer responds to
            // the others' positions; the loop early-stops when no
            // reviewer changes which anchors they flag. Findings
            // aren't mutated — the debate enriches the transcript so
            // the user has more context at the ballot.
            if (!consensus.disputed().isEmpty()) {
                runDebateLoop(pass, panel, reviewerSeats, request, outputs, consensus.disputed());
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
                finding.createdAt()));

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
    private List<LlmReviewer> resolvePanel(List<String> explicitIds)
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
            List<LlmReviewer> panel,
            ReviewRequest request)
    {
        ExecutorService executor = Executors.newFixedThreadPool(panel.size());
        try {
            List<CompletableFuture<ReviewOutput>> futures = new ArrayList<>();
            for (LlmReviewer r : panel) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> runOneReviewerMaybeFanOut(r, request), executor));
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

    /** Bounded debate loop: up to {@code pass.roundCap()} rounds of
     *  reviewers responding to the others' disputed findings.
     *  Convergence early-stop fires when no reviewer's anchor set
     *  changes round-over-round. Persists one DEBATE message per
     *  reviewer per round so the transcript carries the back-and-
     *  forth into the arbitration UI. Doesn't mutate finding rows —
     *  the ballot still drives final resolution.
     *
     *  <p>Cost tracking is best-effort: the existing LlmReviewer API
     *  doesn't expose per-call token counts so the loop only enforces
     *  the round cap. A future commit can thread token usage through
     *  ReviewOutput and add cost-cap honouring here. */
    private void runDebateLoop(
            ReviewPass pass,
            List<LlmReviewer> panel,
            List<ReviewParticipant> seats,
            ReviewRequest baseRequest,
            Map<ReviewParticipant, ReviewOutput> independentOutputs,
            List<ConsensusFinding> initialDisputed)
    {
        ReviewPass debatePass = withPhase(pass, ReviewPhase.DEBATE, /* endedAt */ null);
        reviewStore.savePass(debatePass);

        // Per-reviewer anchor set from the latest round, seeded from
        // INDEPENDENT. Convergence early-stop compares the new round's
        // sets to this and breaks when every reviewer stays put.
        Map<ReviewParticipant, Set<AnchorKey>> previousAnchors = new LinkedHashMap<>();
        for (ReviewParticipant seat : seats) {
            previousAnchors.put(seat, anchorsOf(independentOutputs.get(seat)));
        }

        String debateContext = buildDebateContext(initialDisputed);
        ReviewRequest debateRequest = new ReviewRequest(
                baseRequest.repo(),
                baseRequest.number(),
                baseRequest.title(),
                baseRequest.body(),
                baseRequest.headSha(),
                baseRequest.diff(),
                composeSkillContext(debateContext));

        int roundCap = pass.roundCap();
        for (int round = 1; round <= roundCap; round++) {
            Map<ReviewParticipant, ReviewOutput> debateOutputs;
            try {
                debateOutputs = runIndependentInParallel(seats, panel, debateRequest);
            }
            catch (RuntimeException e) {
                // A provider blip mid-debate shouldn't tear down the
                // whole pass — log, break, fall through to ARBITRATE
                // on the disputed set from INDEPENDENT. The transcript
                // captures the rounds that did complete.
                log.warn("Review pass {} DEBATE round {} failed: {}",
                        pass.id(), round, e.getMessage());
                break;
            }

            Instant roundAt = Instant.now();
            for (ReviewParticipant seat : seats) {
                ReviewOutput out = debateOutputs.get(seat);
                reviewStore.saveMessage(new ReviewMessage(
                        UUID.randomUUID().toString(),
                        pass.id(),
                        seat.id(),
                        ReviewPhase.DEBATE,
                        round,
                        out.summary() == null ? "" : out.summary(),
                        /* mentions */ List.of(),
                        /* refs */ List.of(),
                        /* costUsdMilli */ 0L,
                        roundAt));
            }

            // Convergence check: every reviewer holds the same anchor
            // set as the previous round. The first stable round ends
            // the loop — further LLM calls only burn tokens.
            Map<ReviewParticipant, Set<AnchorKey>> currentAnchors = new LinkedHashMap<>();
            for (ReviewParticipant seat : seats) {
                currentAnchors.put(seat, anchorsOf(debateOutputs.get(seat)));
            }
            boolean converged = currentAnchors.equals(previousAnchors);
            if (converged) {
                log.info("Review pass {} DEBATE converged after round {}", pass.id(), round);
                break;
            }
            previousAnchors = currentAnchors;
        }
    }

    private static Set<AnchorKey> anchorsOf(ReviewOutput out)
    {
        if (out == null || out.comments() == null) {
            return Set.of();
        }
        Set<AnchorKey> set = new LinkedHashSet<>();
        for (ReviewOutput.LineComment c : out.comments()) {
            Integer line = c.line() > 0 ? c.line() : null;
            set.add(new AnchorKey(c.file(), line));
        }
        return set;
    }

    /** Build the augmented skillContext for a debate round. Lists
     *  the disputed findings + which reviewer flagged each so the
     *  model knows what to weigh in on. Stuffed into the existing
     *  {@code ReviewRequest.skillContext} slot — the prompt builder
     *  surfaces it under the "Repository-specific review context"
     *  header, which is a bit of an overload but means no new
     *  LlmReviewer API surface to land in this commit. */
    private static String buildDebateContext(List<ConsensusFinding> disputed)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("PANEL DEBATE ROUND. You are part of a review panel; ");
        sb.append("the panel did not reach consensus on the findings below. ");
        sb.append("For each item, reaffirm if you still believe it should be ");
        sb.append("raised on this PR, or omit it from your output. Findings ");
        sb.append("you don't mention will be treated as withdrawn. Don't add ");
        sb.append("new findings — focus only on the disputed list.\n\n");
        for (ConsensusFinding f : disputed) {
            sb.append("- ").append(f.path() == null ? "(whole PR)" : f.path());
            if (f.line() != null) {
                sb.append(":").append(f.line());
            }
            sb.append(" — [").append(f.reporterPersona()).append("] ")
                    .append(f.body()).append('\n');
        }
        return sb.toString();
    }

    /** Heuristic consensus dedup. Groups raw findings by
     *  {@code (path, line)} — exact match. Each group reported by all
     *  panel reviewers collapses to one AGREED row using the most
     *  severe reading; groups missing a reviewer fan out into one
     *  DISPUTED row per reporter so the publish UI can show who said
     *  what. The Haiku-driven semantic check is a Phase 3 follow-up. */
    private ConsensusResult extractConsensus(
            List<ReviewParticipant> seats,
            Map<ReviewParticipant, ReviewOutput> outputs)
    {
        int panelSize = seats.size();
        Map<AnchorKey, List<ReportedFinding>> byAnchor = new LinkedHashMap<>();
        for (ReviewParticipant seat : seats) {
            ReviewOutput out = outputs.get(seat);
            List<ReviewOutput.LineComment> comments = out.comments() == null
                    ? List.of() : out.comments();
            for (ReviewOutput.LineComment c : comments) {
                Integer line = c.line() > 0 ? c.line() : null;
                AnchorKey key = new AnchorKey(c.file(), line);
                byAnchor.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new ReportedFinding(
                                seat,
                                severityFromComment(c.severity()),
                                c.body() == null ? "" : c.body()));
            }
        }

        List<ConsensusFinding> agreed = new ArrayList<>();
        List<ConsensusFinding> disputed = new ArrayList<>();
        for (Map.Entry<AnchorKey, List<ReportedFinding>> entry : byAnchor.entrySet()) {
            AnchorKey anchor = entry.getKey();
            List<ReportedFinding> reports = entry.getValue();
            if (reports.size() >= panelSize) {
                ReportedFinding representative = reports.stream()
                        .max(Comparator.comparingInt(r -> severityWeight(r.severity())))
                        .orElseThrow();
                agreed.add(new ConsensusFinding(
                        anchor.path(), anchor.line(),
                        representative.severity(),
                        representative.body(),
                        /* reporterPersona */ representative.participant().personaLabel()));
            }
            else {
                for (ReportedFinding r : reports) {
                    disputed.add(new ConsensusFinding(
                            anchor.path(), anchor.line(),
                            r.severity(),
                            r.body(),
                            r.participant().personaLabel()));
                }
            }
        }
        return new ConsensusResult(List.copyOf(agreed), List.copyOf(disputed));
    }

    private static String panelDisplayNames(List<LlmReviewer> panel)
    {
        return panel.stream().map(LlmReviewer::displayName).reduce(
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
                    f.createdAt()));
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

    /** Severity weight so the consensus extractor picks the most-
     *  serious reading as the representative for an AGREED row. Higher
     *  = more severe. Explicit weights avoid {@code Enum.ordinal()},
     *  which Error Prone flags as fragile against enum-reordering. */
    private static int severityWeight(ReviewFindingSeverity s)
    {
        return switch (s) {
            case BLOCKER -> 4;
            case MAJOR -> 3;
            case NIT -> 2;
            case QUESTION -> 1;
        };
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

    private record AnchorKey(String path, Integer line) {}
    private record ReportedFinding(
            ReviewParticipant participant,
            ReviewFindingSeverity severity,
            String body) {}
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
            boolean independentFirst)
    {
        public static final StartOptions DEFAULT = new StartOptions(List.of(), 3, 500L, true);
    }

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
