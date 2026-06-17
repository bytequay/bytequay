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

import com.bytequay.app.domain.AgendaPhase;
import com.bytequay.app.domain.AgendaPhaseStatus;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPassKind;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.ReviewVerdict;
import com.bytequay.app.domain.Skill;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.threads.AgentScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
    private final SkillStore skills;
    private final Executor reviewExecutor;
    private final LeadOrchestrator leadOrchestrator;
    private final ReviewerSeat reviewerSeat;
    private final LeadToolset leadToolset;
    private final ReviewBudgetMeter budgetMeter;
    private final ReviewDiffCache diffCache;
    private final AgentScheduler scheduler;

    public ReviewPassService(
            ThreadStore threadStore,
            ReviewStore reviewStore,
            PullRequestRepository pullRequests,
            PullRequestStore pullRequestStore,
            PatResolver patResolver,
            LlmReviewerRegistry reviewers,
            AppSettingsStore appSettings,
            @Qualifier(REVIEW_EXECUTOR) Executor reviewExecutor,
            LeadOrchestrator leadOrchestrator,
            ReviewerSeat reviewerSeat,
            LeadToolset leadToolset,
            ReviewBudgetMeter budgetMeter,
            ReviewDiffCache diffCache,
            AgentScheduler scheduler,
            SkillStore skills)
    {
        this.reviewExecutor = requireNonNull(reviewExecutor, "reviewExecutor is null");
        this.leadOrchestrator = requireNonNull(leadOrchestrator, "leadOrchestrator is null");
        this.reviewerSeat = requireNonNull(reviewerSeat, "reviewerSeat is null");
        this.leadToolset = requireNonNull(leadToolset, "leadToolset is null");
        this.budgetMeter = requireNonNull(budgetMeter, "budgetMeter is null");
        this.diffCache = requireNonNull(diffCache, "diffCache is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.skills = requireNonNull(skills, "skills is null");
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
        // Standalone "Assign review": THREAD-hosted (its own review thread
        // is the host) and FRESH.
        Seat seat = seatReviewPass(repoFullName, prNumber, opts);
        reviewStore.setPassHost(seat.pass().id(),
                ReviewPassHostKind.THREAD, seat.pass().threadId(), ReviewPassKind.FRESH);
        launchReviewBody(seat);
        return buildDetail(seat.pass());
    }

    /**
     * Start a TASK_PHASE-hosted pass — the internal review (FRESH) or
     * re-review (RE_REVIEW, Loop D) the dev task lifecycle runs. Same
     * Lead + seats + agenda machinery as the standalone flow; only the
     * host stamp differs, so a TASK_PHASE pass never carries the
     * spawn-build affordance (the dev task IS the build).
     */
    public ReviewPassDetail startTaskPhaseReview(
            String taskId, String repoFullName, int prNumber, ReviewPassKind kind, StartOptions opts)
    {
        Seat seat = seatReviewPass(repoFullName, prNumber, opts);
        reviewStore.setPassHost(seat.pass().id(), ReviewPassHostKind.TASK_PHASE, taskId, kind);
        launchReviewBody(seat);
        return buildDetail(reviewStore.findPassById(seat.pass().id()).orElse(seat.pass()));
    }

    /** Run the heavy LLM review body off the request thread. Shared by
     *  the THREAD and TASK_PHASE entry points. */
    private void launchReviewBody(Seat seat)
    {
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

        // 4. Seat the panel: the Lead + N reviewers + the human row.
        //    The Lead runs on the panel's lead member (the dialog's
        //    pick, falling back to the LEAD-role persona then the
        //    first member); the human row exists so user-typed
        //    messages bind without a schema change.
        PanelMember leadMember = panel.stream()
                .filter(PanelMember::lead)
                .findFirst()
                .orElse(panel.get(0));
        ReviewParticipant moderator = new ReviewParticipant(
                UUID.randomUUID().toString(), pass.id(),
                ReviewParticipantKind.LEAD,
                /* credentialId */ leadMember.reviewer().providerId(),
                "Lead",
                /* model */ null, /* color */ null, now);
        reviewStore.saveParticipant(moderator);
        // The lead is seated as the moderator above — it coordinates and
        // dispatches rather than reviewing, so it is NOT also a reviewer
        // seat (that double-seated the lead's model, e.g. a phantom extra
        // "DeepSeek" reviewer). The sole exception is a panel-of-1, where
        // the single member both leads and reviews.
        List<PanelMember> reviewerMembers = panel.stream()
                .filter(m -> !m.lead())
                .toList();
        if (reviewerMembers.isEmpty()) {
            reviewerMembers = panel;
        }
        List<ReviewParticipant> reviewerSeats = new ArrayList<>();
        for (PanelMember m : reviewerMembers) {
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

        // 5. Kickoff message — broadcast announcement. Counts the actual
        //    reviewer seats (the lead coordinates, it isn't a reviewer).
        String kickoffMention = reviewerMembers.size() == 1
                ? reviewerMembers.get(0).displayLabel()
                : "a panel of " + reviewerMembers.size() + " reviewers ("
                        + panelDisplayNames(reviewerMembers) + ")";
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

        // The in-memory roster the lead + seat compositions thread
        // through the run: persona prompts are configuration, not
        // transcript, so they ride here rather than on rows. The PR
        // summary is rendered into each seat's prompt now (the body
        // carries a {{pr_summary}} placeholder the user authored), so a
        // dispatched reviewer reads a complete, PR-specific prompt.
        String prTitle = pullRequestStore
                .findIdByRepoAndNumber(repoFullName, prNumber)
                .flatMap(pullRequestStore::findById)
                .map(PullRequest::title)
                .orElse(null);
        String prSummary = prSummary(prTitle, raw.body());
        List<PanelSeatConfig.Seat> seatConfigs = new ArrayList<>();
        // The lead seat carries no persona prompt — its job is fixed in
        // code (summarize the PR, dispatch reviewers, drive consensus),
        // so it runs the orchestrator's built-in prompt, not a voice.
        seatConfigs.add(new PanelSeatConfig.Seat(
                moderator.id(), leadMember.reviewer().providerId(),
                /* personaPrompt */ null, "Lead", /* lead */ true));
        // reviewerSeats was built 1:1 from reviewerMembers (lead excluded),
        // so pair against that — not the full panel — or the indices skew.
        for (int i = 0; i < reviewerMembers.size(); i++) {
            seatConfigs.add(new PanelSeatConfig.Seat(
                    reviewerSeats.get(i).id(),
                    reviewerMembers.get(i).reviewer().providerId(),
                    renderSeatTemplate(reviewerMembers.get(i).personaPrompt(), prSummary),
                    reviewerMembers.get(i).displayLabel(),
                    /* lead */ false));
        }
        return new Seat(pass, new PanelSeatConfig(seatConfigs), moderator, reviewerSeats, request);
    }

    /** Replace the authored {@code {{pr_summary}}} placeholder in a
     *  seat's prompt with the PR summary, so the reviewer reads a
     *  complete prompt. Null/blank prompts pass through unchanged. */
    private static String renderSeatTemplate(String prompt, String prSummary)
    {
        return prompt == null ? null : prompt.replace("{{pr_summary}}", prSummary);
    }

    /** The PR summary injected for {@code {{pr_summary}}} — the title and
     *  description the author wrote, the natural human summary of the
     *  change. */
    private static String prSummary(String title, String body)
    {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.strip());
        }
        if (body != null && !body.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(body.strip());
        }
        return sb.length() == 0 ? "(no PR description provided)" : sb.toString();
    }

    /** Hand-off from the synchronous seat phase to the (sync or async)
     *  panel body. */
    private record Seat(
            ReviewPass pass,
            PanelSeatConfig roster,
            ReviewParticipant lead,
            List<ReviewParticipant> reviewerSeats,
            ReviewRequest request) {}

    /** Canonical agenda ids the deterministic spine tracks. The Lead
     *  sets the agenda (and may extend it), but these four ids must
     *  exist so phase completion never depends on model output. */
    static final String AGENDA_INDEPENDENT = "p_independent";
    static final String AGENDA_CROSS_REVIEW = "p_crossreview";
    static final String AGENDA_CONSENSUS = "p_consensus";
    static final String AGENDA_DEBATE = "p_debate";

    /** Watchdog on the Lead-driven loop: a phase never runs more than
     *  this many Lead rounds, no matter what the model does. */
    static final int MAX_LEAD_TURNS_PER_PHASE = 50;

    /**
     * Drive a seated pass to its final phase. The OUTER spine stays
     * deterministic — KICKOFF → INDEPENDENT → CROSS_REVIEW → CONSENSUS
     * → DEBATE → TERMINATE/ARBITRATE, each transition made by this
     * method — while the CONTENT of each phase is produced by the Lead
     * orchestrator and the reviewer seats:
     *
     * <ul>
     *   <li>KICKOFF — one Lead round that sets the agenda (the spine
     *       installs the canonical default if the Lead doesn't).</li>
     *   <li>INDEPENDENT — the spine fans every reviewer seat out in
     *       parallel through the scheduler's API lane; seats run
     *       agentic turns with the read-only tools and report
     *       findings. A failed seat abstains; the pass only fails if
     *       every seat failed.</li>
     *   <li>CROSS_REVIEW / CONSENSUS / DEBATE — Lead-driven rounds
     *       until the Lead marks the agenda phase done, the pass cost
     *       cap fires, or the turn-count watchdog trips.</li>
     * </ul>
     *
     * No method-level transaction — every persistence call
     * short-transacts so the model fan-out never pins the single
     * pooled connection. On a fatal failure the pass parks at
     * TERMINATE and rethrows as a 502.
     */
    private ReviewPassDetail runReviewBody(Seat seated)
    {
        ReviewPass pass = seated.pass();
        PanelSeatConfig roster = seated.roster();
        diffCache.seed(pass.id(), seated.request().diff() == null ? "" : seated.request().diff());
        LeadToolset.Session session = leadToolset.sessionFor(pass.id(), roster, seated.lead().id());
        try {
            budgetMeter.initSeatSlices(pass, reviewStore.listParticipantsForPass(pass.id()));

            leadOrchestrator.runRound(reload(pass.id()), session, roster,
                    ReviewPhase.KICKOFF, 0, KICKOFF_DIRECTIVE);
            ensureAgenda(pass.id());
            // The Lead's kickoff brief (its PR summary) seeds the
            // reviewers so they start from a shared read of the change.
            String leadBrief = latestLeadBrief(pass.id(), seated.lead().id());

            markAgenda(pass.id(), AGENDA_INDEPENDENT, AgendaPhaseStatus.IN_PROGRESS);
            runIndependentParallel(pass.id(), roster, leadBrief);
            markAgenda(pass.id(), AGENDA_INDEPENDENT, AgendaPhaseStatus.DONE);

            transition(pass.id(), ReviewPhase.CROSS_REVIEW);
            runLeadDriven(pass.id(), session, roster, ReviewPhase.CROSS_REVIEW,
                    AGENDA_CROSS_REVIEW, CROSS_REVIEW_GUIDANCE);

            transition(pass.id(), ReviewPhase.CONSENSUS);
            runLeadDriven(pass.id(), session, roster, ReviewPhase.CONSENSUS,
                    AGENDA_CONSENSUS, CONSENSUS_GUIDANCE);

            transition(pass.id(), ReviewPhase.DEBATE);
            runLeadDriven(pass.id(), session, roster, ReviewPhase.DEBATE,
                    AGENDA_DEBATE, DEBATE_GUIDANCE);

            // One closing Lead round: the consolidated result for the
            // human (recommended verdict, agreed findings, open disputes).
            runWrapUp(pass.id(), session, roster);
        }
        catch (RuntimeException e) {
            // Park the pass terminated-with-error so the UI shows a
            // clear failure state rather than a pass stuck mid-phase.
            log.warn("Review pass {} failed during {}: {}",
                    pass.id(), reload(pass.id()).phase(), e.getMessage());
            reviewStore.savePass(withPhase(reload(pass.id()), ReviewPhase.TERMINATE, Instant.now()));
            diffCache.drop(pass.id());
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Review panel run failed: " + e.getMessage(), e);
        }
        diffCache.drop(pass.id());

        // Final phase. Findings the panel didn't settle — REPORTED
        // (never classified) or DISPUTED (split) — park the pass at
        // ARBITRATE for the human ballot; otherwise it terminates and
        // the publish form unlocks.
        ReviewPass fresh = reload(pass.id());
        List<ReviewFinding> findings = reviewStore.listFindingsForPass(pass.id());
        boolean needsBallot = findings.stream().anyMatch(ReviewPassService::needsArbitration);
        ReviewPass finalPass = new ReviewPass(
                fresh.id(), fresh.threadId(), fresh.repoFullName(), fresh.prNumber(),
                fresh.headSha(),
                needsBallot ? ReviewPhase.ARBITRATE : ReviewPhase.TERMINATE,
                fresh.round(), fresh.roundCap(), fresh.costCapMilli(), fresh.costUsdMilli(),
                suggestedVerdictForFindings(findings),
                fresh.createdAt(),
                /* endedAt */ needsBallot ? null : Instant.now(),
                fresh.spawnedBuildThreadId(), fresh.agendaJson());
        reviewStore.savePass(finalPass);
        return buildDetail(finalPass);
    }

    private static final String KICKOFF_DIRECTIVE = """
            Kick off this review pass. FIRST, in 2-4 sentences, summarise THIS PR for \
            the panel: what it changes, why, and the riskiest areas worth the closest \
            scrutiny — use the read-only code tools (get_pr_diff, get_file_content, \
            search_code) to ground the summary in the actual change. THEN call \
            set_agenda ONCE with the ordered phases you will drive. The agenda MUST \
            include these ids (you may refine the titles and append extra phases): \
            p_independent, p_crossreview, p_consensus, p_debate. Do not dispatch \
            anyone yet — the independent fan-out runs automatically after kickoff, and \
            your summary is handed to every reviewer as their brief.""";

    private static final String WRAP_UP_DIRECTIVE = """
            The panel's work is done. Write the CLOSING RESULT for the human in a few \
            sentences: your recommended verdict (approve / comment / request changes) \
            and why, the agreed findings that should be addressed, and any disputes \
            left for the human to arbitrate. This is your final summary — do not \
            dispatch reviewers or open new lines of inquiry.""";

    private static final String WRAP_UP_BUDGET_DIRECTIVE = """
            The review budget has been reached before the panel fully converged. Do \
            NOT dispatch reviewers or open new lines of inquiry — finalize now with \
            what the panel has. In ONE message, open by noting the budget is reached \
            (e.g. "The review budget is reached — summarizing and finalizing with the \
            current findings."), then give your recommended verdict (approve / comment \
            / request changes) and why, the agreed findings to address, and any \
            unresolved or disputed items left for the human to arbitrate.""";

    private static final String CROSS_REVIEW_GUIDANCE = """
            Cross-examine the independent findings: for each substantive finding, \
            dispatch the OTHER reviewers to react — quote the specific claim in \
            your dispatch body (reviewers cannot see each other). Several \
            dispatches in one turn run in parallel.""";

    private static final String CONSENSUS_GUIDANCE = """
            Weigh the panel and classify EVERY reported finding with \
            mark_consensus: agreed (panel stands behind it), disputed (split — \
            goes to the human ballot), or dropped (withdrawn / wrong). Record \
            notable minority positions with record_dissent.""";

    private static final String DEBATE_GUIDANCE = """
            Debate the disputed findings, spiciest first. Dispatch focused \
            rounds per finding (pass finding_id so its debate budget is \
            metered). If the panel converges, re-classify with mark_consensus; \
            findings still disputed when you finish go to the human ballot.""";

    private ReviewPass reload(String passId)
    {
        return reviewStore.findPassById(passId)
                .orElseThrow(() -> new IllegalStateException("no review pass: " + passId));
    }

    private void transition(String passId, ReviewPhase phase)
    {
        reviewStore.savePass(withPhase(reload(passId), phase, /* endedAt */ null));
    }

    /** Install the canonical agenda when the Lead's kickoff round
     *  didn't set one (or set one missing the canonical ids) — the
     *  spine's phase tracking must never depend on model output. */
    private void ensureAgenda(String passId)
    {
        ReviewPass pass = reload(passId);
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(pass.agendaJson());
        Set<String> ids = agenda.stream().map(AgendaPhase::id).collect(Collectors.toSet());
        if (ids.containsAll(Set.of(AGENDA_INDEPENDENT, AGENDA_CROSS_REVIEW,
                AGENDA_CONSENSUS, AGENDA_DEBATE))) {
            return;
        }
        log.info("Review pass {} kickoff produced no usable agenda; installing the default.",
                passId);
        reviewStore.savePass(pass.withAgendaJson(AgendaJsonCodec.write(List.of(
                new AgendaPhase(AGENDA_INDEPENDENT, "Independent reviews (parallel)",
                        AgendaPhaseStatus.OPEN),
                new AgendaPhase(AGENDA_CROSS_REVIEW, "Cross-review the findings",
                        AgendaPhaseStatus.OPEN),
                new AgendaPhase(AGENDA_CONSENSUS, "Classify consensus per finding",
                        AgendaPhaseStatus.OPEN),
                new AgendaPhase(AGENDA_DEBATE, "Debate the disputed findings",
                        AgendaPhaseStatus.OPEN)))));
    }

    private void markAgenda(String passId, String agendaId, AgendaPhaseStatus status)
    {
        ReviewPass pass = reload(passId);
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(pass.agendaJson());
        reviewStore.savePass(pass.withAgendaJson(
                AgendaJsonCodec.write(AgendaJsonCodec.withStatus(agenda, agendaId, status))));
    }

    private AgendaPhaseStatus agendaStatus(String passId, String agendaId)
    {
        return AgendaJsonCodec.parse(reload(passId).agendaJson()).stream()
                .filter(p -> p.id().equals(agendaId))
                .map(AgendaPhase::status)
                .findFirst()
                .orElse(AgendaPhaseStatus.OPEN);
    }

    /** Fan every reviewer seat out concurrently through the
     *  scheduler's API lane. A failed seat logs and abstains; the
     *  pass only fails when no seat answered at all. */
    private void runIndependentParallel(String passId, PanelSeatConfig roster, String leadBrief)
    {
        ReviewPass pass = reload(passId);
        String directive = leadBrief == null || leadBrief.isBlank()
                ? INDEPENDENT_DIRECTIVE
                : "The lead's brief on this PR:\n" + leadBrief.strip() + "\n\n" + INDEPENDENT_DIRECTIVE;
        List<Callable<ReviewMessage>> work = new ArrayList<>();
        for (PanelSeatConfig.Seat seat : roster.reviewerSeats()) {
            work.add(() -> {
                try {
                    return reviewerSeat.runDispatchedTurn(
                            pass, roster, seat.participantId(),
                            directive, ReviewPhase.INDEPENDENT,
                            /* round */ 0, /* excludeMessageId */ null);
                }
                catch (RuntimeException e) {
                    log.warn("Independent turn failed for seat {} ({}): {} — abstaining.",
                            seat.participantId(), seat.displayLabel(), e.getMessage());
                    return null;
                }
            });
        }
        List<ReviewMessage> results = scheduler.invokeAll(work);
        if (!results.isEmpty() && results.stream().allMatch(Objects::isNull)) {
            throw new IllegalStateException("every reviewer seat failed its independent turn");
        }
    }

    private static final String INDEPENDENT_DIRECTIVE = """
            Give your independent review of this PR. Use the read-only tools to \
            check the actual code before making claims, record every concrete \
            issue with report_finding, and close with a short summary of what you \
            checked and where you stand. You are reviewing alone — no other \
            reviewer's output exists for you.""";

    /** Lead-driven phase content: rounds until the Lead marks the
     *  agenda phase done, the pass budget is spent, or the watchdog
     *  trips. The spine forces the agenda phase DONE on exit so the
     *  artifact freezes consistent however the loop ended. */
    private void runLeadDriven(
            String passId,
            LeadToolset.Session session,
            PanelSeatConfig roster,
            ReviewPhase phase,
            String agendaId,
            String guidance)
    {
        markAgenda(passId, agendaId, AgendaPhaseStatus.IN_PROGRESS);
        for (int turn = 1; turn <= MAX_LEAD_TURNS_PER_PHASE; turn++) {
            if (budgetMeter.passExhausted(passId)) {
                log.warn("Review pass {} hit its cost cap during {}; ending the phase.",
                        passId, phase);
                break;
            }
            String directive = "Agenda phase '" + agendaId + "' is in progress. " + guidance
                    + " When the phase's work is complete, call mark_phase_done(\""
                    + agendaId + "\").";
            leadOrchestrator.runRound(reload(passId), session, roster, phase, turn, directive);
            if (agendaStatus(passId, agendaId) == AgendaPhaseStatus.DONE) {
                return;
            }
        }
        markAgenda(passId, agendaId, AgendaPhaseStatus.DONE);
    }

    /** The Lead's kickoff brief — the latest KICKOFF-phase message it
     *  authored (its PR summary). Null when the Lead wrote nothing. */
    private String latestLeadBrief(String passId, String leadParticipantId)
    {
        ReviewMessage last = null;
        for (ReviewMessage m : reviewStore.listMessagesForPass(passId)) {
            if (m.phase() == ReviewPhase.KICKOFF && leadParticipantId.equals(m.participantId())) {
                last = m;
            }
        }
        return last == null ? null : last.body();
    }

    /** One closing Lead round that records the consolidated result for
     *  the human (verdict + agreed findings + open disputes). Always runs
     *  — when the pass has already spent its cost cap the turn runs
     *  UNBUDGETED with a budget-aware directive, so a pass that hit the
     *  cap still gets a finalized summary instead of stalling mid-stream
     *  with no result. */
    private void runWrapUp(String passId, LeadToolset.Session session, PanelSeatConfig roster)
    {
        if (budgetMeter.passExhausted(passId)) {
            // Cost cap hit before convergence: run ONE unbudgeted closing
            // turn so the lead still finalizes with what the panel has,
            // rather than stalling mid-stream with no result.
            leadOrchestrator.runRound(reload(passId), session, roster,
                    ReviewPhase.TERMINATE, 0, WRAP_UP_BUDGET_DIRECTIVE, /* enforceBudget */ false);
            return;
        }
        leadOrchestrator.runRound(reload(passId), session, roster,
                ReviewPhase.TERMINATE, 0, WRAP_UP_DIRECTIVE);
    }

    private static boolean needsArbitration(ReviewFinding f)
    {
        return f.status() == ReviewFindingStatus.REPORTED
                || f.status() == ReviewFindingStatus.DISPUTED;
    }

    /** Verdict suggestion from the findings table: an AGREED blocker
     *  → REQUEST_CHANGES; any live finding → COMMENT; a clean panel
     *  → APPROVE. Open findings never escalate past COMMENT on their
     *  own — the human arbitrates disagreements. */
    private static ReviewVerdict suggestedVerdictForFindings(List<ReviewFinding> findings)
    {
        boolean any = false;
        for (ReviewFinding f : findings) {
            if (f.status() == ReviewFindingStatus.DROPPED) {
                continue;
            }
            any = true;
            if (f.status() == ReviewFindingStatus.AGREED
                    && f.severity() == ReviewFindingSeverity.BLOCKER) {
                return ReviewVerdict.REQUEST_CHANGES;
            }
        }
        return any ? ReviewVerdict.COMMENT : ReviewVerdict.APPROVE;
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
        if (finding.status() != ReviewFindingStatus.DISPUTED
                && finding.status() != ReviewFindingStatus.REPORTED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "finding " + findingId + " is not open for arbitration — already "
                            + finding.status());
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

        // Once no open (DISPUTED / REPORTED) findings remain, the pass
        // falls through to TERMINATE so the publish form unlocks.
        boolean stillDisputed = reviewStore.listFindingsForPass(passId).stream()
                .anyMatch(ReviewPassService::needsArbitration);
        if (!stillDisputed) {
            ReviewPass terminated = new ReviewPass(
                    pass.id(), pass.threadId(), pass.repoFullName(), pass.prNumber(),
                    pass.headSha(),
                    ReviewPhase.TERMINATE,
                    pass.round(), pass.roundCap(),
                    pass.costCapMilli(), pass.costUsdMilli(),
                    pass.verdict(),
                    pass.createdAt(),
                    /* endedAt */ Instant.now(),
                    pass.spawnedBuildThreadId(), pass.agendaJson());
            reviewStore.savePass(terminated);
            log.info("Review pass {} arbitration complete; transitioned to TERMINATE", passId);
        }
        return findPassWithDetail(passId).orElseThrow();
    }

    /**
     * Inject a human-authored steer message into a pass and run the
     * addressed seat's reply UNBUDGETED — the review-page composer's
     * "@mention a reviewer/lead and send" action. The roster is rebuilt
     * from the persisted participants (model-only: a steered reviewer
     * answers in its base voice, not its original persona). The reply's
     * spend is still metered, so any overage surfaces in the budget meter.
     */
    @Transactional
    public ReviewPassDetail steerPass(String passId, String targetParticipantId, String message)
    {
        requireNonNull(passId, "passId is null");
        requireNonNull(targetParticipantId, "targetParticipantId is null");
        requireNonNull(message, "message is null");
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Steer message is empty.");
        }
        ReviewPass pass = reload(passId);
        List<ReviewParticipant> participants = reviewStore.listParticipantsForPass(passId);
        ReviewParticipant target = participants.stream()
                .filter(p -> p.id().equals(targetParticipantId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "No such participant on this pass."));
        if (target.kind() == ReviewParticipantKind.HUMAN) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Address a reviewer or the lead, not the human seat.");
        }
        ReviewParticipant human = participants.stream()
                .filter(p -> p.kind() == ReviewParticipantKind.HUMAN)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(500), "Pass has no human seat."));

        // Persist the human's message, addressed to the target.
        reviewStore.saveMessage(new ReviewMessage(
                UUID.randomUUID().toString(), passId, human.id(),
                pass.phase(), /* round */ 0, message.strip(),
                List.of(target.id()), List.of(), /* costUsdMilli */ 0L, Instant.now()));

        PanelSeatConfig roster = reconstructRoster(participants);
        // Seed an empty diff so a seat tool that probes it returns empty
        // rather than erroring — the original diff isn't cached post-run.
        diffCache.seed(passId, "");
        try {
            if (target.kind() == ReviewParticipantKind.LEAD) {
                LeadToolset.Session session = leadToolset.sessionFor(passId, roster, target.id());
                leadOrchestrator.runRound(reload(passId), session, roster,
                        pass.phase(), /* round */ 0, steerDirective(message),
                        /* enforceBudget */ false);
            }
            else {
                reviewerSeat.runDispatchedTurn(reload(passId), roster, target.id(),
                        steerDirective(message), pass.phase(), /* round */ 0,
                        /* excludeMessageId */ null, /* enforceBudget */ false);
            }
        }
        finally {
            diffCache.drop(passId);
        }
        return findPassWithDetail(passId).orElseThrow();
    }

    private static String steerDirective(String message)
    {
        return "A human reviewer sent this message into the panel:\n\n"
                + message.strip()
                + "\n\nRespond to it directly and concisely.";
    }

    /** Rebuild the in-memory roster from the persisted participants — the
     *  lead + each reviewer as a model-only seat. Persona prompts aren't
     *  persisted, so a reconstructed seat carries none. */
    private PanelSeatConfig reconstructRoster(List<ReviewParticipant> participants)
    {
        List<PanelSeatConfig.Seat> seats = new ArrayList<>();
        for (ReviewParticipant p : participants) {
            if (p.kind() == ReviewParticipantKind.LEAD) {
                seats.add(new PanelSeatConfig.Seat(
                        p.id(), p.credentialId(), /* personaPrompt */ null,
                        p.personaLabel(), /* lead */ true));
            }
            else if (p.kind() == ReviewParticipantKind.REVIEWER) {
                seats.add(new PanelSeatConfig.Seat(
                        p.id(), p.credentialId(), /* personaPrompt */ null,
                        p.personaLabel(), /* lead */ false));
            }
        }
        return new PanelSeatConfig(seats);
    }

    /** Configured reviewers form the panel. No hard size cap — the
     *  Lead-driven agenda and the per-seat budget slices keep large
     *  panels bounded (cost scales with the pass cap, not the panel
     *  size), so 5+ reviewers is a supported configuration. 2–3 is
     *  still the cost sweet spot for routine reviews.
     *
     *  <p>When {@code explicitIds} is non-empty, only reviewers whose
     *  {@code providerId()} appears in that list are seated (still
     *  still only configured ones). An empty/null
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
     *    <li>Seat path — opts.seats is set (the dialog composed the
     *        panel as model × review-skill / typed prompt). Seat one
     *        member per entry.</li>
     *    <li>Legacy path — fall back to the per-provider configured
     *        reviewers, optionally filtered by opts.panelProviderIds.</li>
     *  </ol>
     *  Either way every selected member is seated — the budget
     *  slices, not a roster cap, bound the cost. */
    private List<PanelMember> resolvePanelMembers(StartOptions opts)
    {
        if (opts.hasSeats()) {
            return resolveSeatPanel(opts.seats());
        }
        String leadId = opts.leadId();
        return resolveLegacyPanel(opts.panelProviderIds()).stream()
                .map(r -> new PanelMember(r, null, r.displayName(),
                        leadId != null && r.providerId().equalsIgnoreCase(leadId)))
                .toList();
    }

    /**
     * Build the panel from an explicit seat list (the composition flow):
     * each seat is a model paired with a review-skill voice, a typed
     * prompt, or neither. If no seat is flagged lead, the first seat
     * leads.
     */
    private List<PanelMember> resolveSeatPanel(List<PanelSeat> seats)
    {
        List<PanelMember> members = new ArrayList<>();
        for (PanelSeat seat : seats) {
            LlmReviewer reviewer = requireConfiguredReviewer(seat.providerId());
            String prompt;
            String label;
            if (seat.lead()) {
                // The lead is a fixed, code-driven coordinator — it
                // summarizes the PR, dispatches reviewers, and drives
                // consensus, all from the orchestrator prompt baked into
                // the code. It never carries a persona/skill voice, so a
                // lead seat is model-only and skips role resolution
                // entirely (a skill/prompt attached to it is ignored,
                // not validated).
                prompt = null;
                label = reviewer.displayName();
            }
            else if (seat.roleSkillId() != null) {
                Skill skill = skills.byId(seat.roleSkillId())
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatusCode.valueOf(412),
                                "Role skill #" + seat.roleSkillId()
                                        + " not found or has been deleted."));
                if (!"review".equals(skill.usage())) {
                    throw new ResponseStatusException(
                            HttpStatusCode.valueOf(412),
                            "Skill '" + skill.name() + "' is a build-surface skill — mark it "
                                    + "usage=review in Settings → Skills to use it as a "
                                    + "reviewer role.");
                }
                if (!skill.enabled()) {
                    continue;
                }
                prompt = skill.body();
                label = skill.name();
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
        }
        if (members.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(412),
                    "No reviewers selected. Add at least one in the Start Review dialog.");
        }
        // Exactly one lead: if the dialog flagged none (or every flagged
        // seat fell out as a disabled review skill), the first seat leads
        // — and as the lead it drops any persona it was carrying, since
        // the lead role takes no voice.
        if (members.stream().noneMatch(PanelMember::lead)) {
            PanelMember first = members.get(0);
            members.set(0, new PanelMember(
                    first.reviewer(), null, first.reviewer().displayName(), true));
        }
        return members;
    }

    /** Resolve a reviewer by provider id, 412-ing when it's unknown or
     *  has no API key. */
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
        return selected;
    }

    /** Threshold above which a single reviewer call gets split per
     *  file. The existing {@link com.bytequay.app.service.ai.ReviewPrompt}
     *  truncates over 200K to fit context windows; fanning out below
     *  that keeps each call comfortably under the cap and avoids
     *  losing the tail of a large PR to truncation. */
    static final int MAX_DIFF_CHARS_PER_CALL = 60_000;

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

    /** Light per-thread PR title + author, resolved from the latest review
     *  pass's PR cache row. Lets the thread lists (e.g. the home active
     *  threads) label a review thread with the reviewed PR without loading
     *  the whole transcript. Threads with no pass are skipped. */
    public List<ReviewThreadPrSummary> prSummariesForThreads(List<String> threadIds)
    {
        List<ReviewThreadPrSummary> out = new ArrayList<>();
        for (String threadId : threadIds) {
            List<ReviewPass> passes = reviewStore.listPassesByThread(threadId);
            if (passes.isEmpty()) {
                continue;
            }
            ReviewPass pass = passes.get(passes.size() - 1);
            Optional<PullRequest> pr = pullRequestStore
                    .findIdByRepoAndNumber(pass.repoFullName(), pass.prNumber())
                    .flatMap(pullRequestStore::findById);
            out.add(new ReviewThreadPrSummary(
                    threadId,
                    pass.repoFullName(),
                    pass.prNumber(),
                    pr.map(PullRequest::title).orElse(null),
                    pr.map(PullRequest::author).orElse(null)));
        }
        return out;
    }

    /** A review thread's reviewed-PR label: repo + number + (cached) title
     *  and author. Title/author are null when the PR isn't cached. */
    public record ReviewThreadPrSummary(
            String threadId,
            String repoFullName,
            int prNumber,
            String prTitle,
            String prAuthor)
    {
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
                publishedAt,
                pass.spawnedBuildThreadId(), pass.agendaJson());
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
                AgendaJsonCodec.parse(pass.agendaJson()),
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
                endedAt,
                pass.spawnedBuildThreadId(), pass.agendaJson());
    }

    /**
     * Caller-supplied options for {@link #startReviewOnPr(String, int, StartOptions)}.
     * All fields are optional via {@link #DEFAULT} — the scheduled
     * + one-click paths use the defaults and the dialog overrides
     * the ones the user touched.
     *
     * @param panelProviderIds explicit panel roster (provider ids).
     *                         Empty/null = use every configured
     *                         reviewer.
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
            /** Workspace the review thread is created in, so it shows in
             *  that workspace's thread list. Null falls back to
             *  ws-default (the scheduled / one-click paths). */
            String workspaceId,
            /** Per-run lead override picked in the dialog — a providerId.
             *  Null falls back to the first panel member. */
            String leadId,
            /** Explicit panel composition — one entry per reviewer seat,
             *  each pairing a model with a review-skill voice or a typed
             *  prompt (see {@link PanelSeat}). When non-empty this is the
             *  authoritative panel and wins over panelProviderIds; the
             *  scheduled / one-click paths leave it empty and fall back
             *  to the all-configured legacy panel. */
            List<PanelSeat> seats)
    {
        public static final StartOptions DEFAULT =
                new StartOptions(List.of(), 3, 500L, true, null, null, List.of());

        /** Backward-compat constructor for the legacy 4-arg call sites
         *  (scheduled review, one-click "Review again"). */
        public StartOptions(
                List<String> panelProviderIds,
                int roundCap,
                long costCapMilli,
                boolean independentFirst)
        {
            this(panelProviderIds, roundCap, costCapMilli, independentFirst, null, null, List.of());
        }

        /** True when the dialog sent an explicit per-seat panel — the
         *  composition path that pairs each model with its own review
         *  skill or typed prompt. */
        public boolean hasSeats()
        {
            return seats != null && !seats.isEmpty();
        }
    }

    /**
     * One reviewer seat in an explicitly-composed panel: a model
     * ({@code providerId}, required) paired with EITHER a review-skill
     * voice ({@code roleSkillId}) OR a free-typed instruction
     * ({@code customPrompt}). Both null = a raw model with the default
     * review prompt. {@code lead} marks the seat that runs consensus +
     * moderates debate (exactly one per panel; the resolver defaults to
     * the first seat when none is flagged).
     */
    public record PanelSeat(
            String providerId,
            String customPrompt,
            /** A review-usage skill row used as the seat's reviewing
             *  voice — its name is the @mention identity. */
            Long roleSkillId,
            boolean lead)
    {
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
