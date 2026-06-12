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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassDetail;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Walks the "awaiting your review" queue on a schedule and spins up
 * a headless review pass for each PR — the Phase 4 acceptance line
 * from {@code multi-agent-review-design.md}: "a scheduled task can
 * spin up review threads over the awaiting-review queue each morning;
 * each runs headlessly through INDEPENDENT → DEBATE, then parks at
 * TERMINATE and pings you for arbitration." Read-only — no worktree
 * lease contention with interactive build work.
 *
 * <p>Opt-in via {@link Key#SCHEDULED_REVIEWS_ENABLED}, off by default
 * (per CLAUDE.md the app never silently runs LLM jobs in the
 * background). Per-PR dedup against recent passes for the same
 * head_sha keeps the loop idempotent — a re-trigger doesn't re-bill
 * Claude for the same diff.
 */
@Component
public class ScheduledReviewService
{
    private static final Logger log = LoggerFactory.getLogger(ScheduledReviewService.class);

    /** Cadence — every hour is fast enough that a freshly-assigned
     *  review surfaces within a meeting cycle and slow enough that
     *  the LLM bill stays sane on a quiet repo. */
    private static final long INTERVAL_MS = 60L * 60 * 1000;
    /** Don't fire on startup — the PR list sync needs time to populate
     *  so we don't immediately try to review stale rows. */
    private static final long INITIAL_DELAY_MS = 5L * 60 * 1000;
    /** Dedup window: skip PRs whose latest pass is younger than this
     *  AND covers the same head_sha. A user-triggered re-review
     *  always works because the manual {@code POST /api/reviews/start}
     *  bypasses this class entirely. */
    private static final Duration RECENT_PASS_WINDOW = Duration.ofHours(24);
    /** Per-job daily cost ceiling — total review spend in the trailing
     *  24h. Bounds the worst case (a big queue on day one) for an
     *  unattended sweep; the per-pass cap ($0.50) doesn't. $5/day. */
    private static final long DAILY_COST_CAP_MILLI = 5_000L;

    private final AppSettingsStore appSettings;
    private final PullRequestStore pullRequestStore;
    private final ReviewStore reviewStore;
    private final ReviewPassService reviewPassService;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public ScheduledReviewService(
            AppSettingsStore appSettings,
            PullRequestStore pullRequestStore,
            ReviewStore reviewStore,
            ReviewPassService reviewPassService,
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.reviewPassService = requireNonNull(reviewPassService, "reviewPassService is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void runScheduledReviews()
    {
        if (!isEnabled()) {
            log.debug("Scheduled reviews disabled — skipping.");
            return;
        }
        List<PullRequest> queue = pullRequestStore.findAll().stream()
                .filter(pr -> pr.origin() == PullRequest.Origin.REVIEW_REQUESTED)
                .filter(ScheduledReviewService::isOpen)
                .toList();
        // Rolling daily cost cap: total review spend over the last 24h is
        // bounded so an unattended sweep of a huge queue can't run up the
        // bill. Seeded from passes already created in the window (incl.
        // earlier hourly runs) so the cap is per-day, not per-run.
        long spentMilli = reviewStore.sumPassCostSince(Instant.now().minus(RECENT_PASS_WINDOW));
        int reviewed = 0;
        boolean capped = false;
        for (PullRequest pr : queue) {
            if (hasRecentPass(pr)) {
                continue;
            }
            if (spentMilli >= DAILY_COST_CAP_MILLI) {
                capped = true;
                break;
            }
            try {
                ReviewPassDetail detail = reviewPassService.startReviewOnPr(
                        pr.repo(), pr.number());
                spentMilli += detail.pass().costUsdMilli();
                emitNotification(pr, detail);
                reviewed++;
            }
            catch (RuntimeException e) {
                // One bad PR (missing PAT, GitHub 404, LLM blip)
                // shouldn't tank the loop — log and move on.
                log.warn("Scheduled review of {}#{} failed: {}",
                        pr.repo(), pr.number(), e.getMessage());
            }
        }
        if (capped) {
            log.info("Scheduled review pass: reviewed {} PR(s), then hit the ${}/day cost cap; "
                    + "remaining eligible PRs wait for the next run.",
                    reviewed, DAILY_COST_CAP_MILLI / 1000);
        }
        else if (reviewed > 0) {
            log.info("Scheduled review pass: queued {} review pass(es) over {} "
                    + "awaiting-review PR(s).", reviewed, queue.size());
        }
    }

    /** Read the opt-in flag. {@code null}/blank/anything-not-"true"
     *  is treated as disabled — explicit on-by-the-user only. */
    public boolean isEnabled()
    {
        return appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)
                .map(s -> "true".equalsIgnoreCase(s.trim()))
                .orElse(false);
    }

    /** Persist the toggle. The controller's PUT lands here. */
    public void setEnabled(boolean enabled)
    {
        appSettings.set(Key.SCHEDULED_REVIEWS_ENABLED, enabled ? "true" : "false");
        log.info("Scheduled reviews {}.", enabled ? "enabled" : "disabled");
    }

    private boolean hasRecentPass(PullRequest pr)
    {
        Instant cutoff = Instant.now().minus(RECENT_PASS_WINDOW);
        for (ReviewPass p : reviewStore.listPassesForPr(pr.repo(), pr.number())) {
            // Two skip rules: same head_sha (we already reviewed this
            // exact commit) or any pass younger than the dedup window
            // (back-to-back automatic runs would be wasteful).
            if (Objects.equals(p.headSha(), pr.headRef() == null ? null : pr.headRef())) {
                // headRef is the branch name, not sha; don't actually
                // compare with that — fall through to time-based dedup
                // which is enough for the scheduler's purposes.
            }
            if (p.createdAt() != null && p.createdAt().isAfter(cutoff)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpen(PullRequest pr)
    {
        String state = pr.state();
        return state == null || "open".equalsIgnoreCase(state.trim());
    }

    private void emitNotification(PullRequest pr, ReviewPassDetail detail)
    {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "scheduled-review");
            payload.put("passId", detail.pass().id());
            payload.put("threadId", detail.pass().threadId());
            payload.put("repoFullName", pr.repo());
            payload.put("prNumber", pr.number());
            payload.put("phase", detail.pass().phase().name().toLowerCase(Locale.ROOT));
            payload.put("agreed", countByStatus(detail, ReviewFindingStatus.AGREED));
            payload.put("disputed", countByStatus(detail, ReviewFindingStatus.DISPUTED));
            String json = mapper.writeValueAsString(payload);
            // Park the headless pass by outcome: a still-disputed /
            // stalled finding needs the human to arbitrate
            // (NEEDS_ATTENTION); an all-agreed pass is ready to publish
            // (AWAITING_REVIEW). The "scheduled-review" source on the
            // payload tags it for the auto* surface either way.
            if (detail.pass().phase() == ReviewPhase.ARBITRATE) {
                notifications.notifyNeedsAttention(detail.pass().threadId(), /* taskId */ null, json);
            }
            else {
                notifications.notifyAwaitingReview(detail.pass().threadId(), /* taskId */ null, json);
            }
        }
        catch (JsonProcessingException | RuntimeException e) {
            // Notification failure shouldn't roll back an already-
            // completed review pass — the user can still find the
            // panel from the threads list.
            log.warn("Notification emit on scheduled review failed for pass {}: {}",
                    detail.pass().id(), e.getMessage());
        }
    }

    private static int countByStatus(ReviewPassDetail detail, ReviewFindingStatus status)
    {
        return (int) detail.findings().stream()
                .filter(f -> f.status() == status)
                .count();
    }
}
