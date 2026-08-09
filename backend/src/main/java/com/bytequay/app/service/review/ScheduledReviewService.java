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

import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.AppSettingsStore.Key;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.repository.sqlite.SqliteWorkspaceStore;
import com.bytequay.app.scheduler.QuietHoursPolicy;
import com.bytequay.app.service.localpr.PRSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Opt-in hourly AgentReview launcher for the review-requested queue.
 *
 * Reviews use the same durable aggregate and workspace owner as manual
 * launches. An unchanged PR is never reviewed twice; a changed head marks the
 * existing review stale and receives a delta round. No clone, publication, or
 * repository mutation is performed by this scheduler.
 */
@Component
public class ScheduledReviewService
{
    private static final Logger log = LoggerFactory.getLogger(ScheduledReviewService.class);
    private static final long INTERVAL_MS = 60L * 60 * 1000;
    private static final long INITIAL_DELAY_MS = 5L * 60 * 1000;
    private static final Duration COST_WINDOW = Duration.ofHours(24);
    private static final long DAILY_COST_CAP_MILLI = 5_000L;
    private static final String DEFAULT_WORKSPACE = "ws-default";

    private final AppSettingsStore appSettings;
    private final PullRequestStore pullRequests;
    private final PRSyncService prSync;
    private final InvestigationReviewService reviews;
    private final InvestigationReviewStore reviewStore;
    private final SqliteWorkspaceStore workspaces;
    private final QuietHoursPolicy quietHours;

    public ScheduledReviewService(
            AppSettingsStore appSettings,
            PullRequestStore pullRequests,
            PRSyncService prSync,
            InvestigationReviewService reviews,
            InvestigationReviewStore reviewStore,
            SqliteWorkspaceStore workspaces,
            QuietHoursPolicy quietHours)
    {
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.quietHours = requireNonNull(quietHours, "quietHours is null");
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void runScheduledReviews()
    {
        if (quietHours.isQuietNow() || !isEnabled()) {
            return;
        }

        long reservedMilli = reviewStore.sumRoundCostCentsSince(
                Instant.now().minus(COST_WINDOW)) * 10L;
        if (reservedMilli >= DAILY_COST_CAP_MILLI) {
            return;
        }
        int started = 0;
        for (PullRequest candidate : pullRequests.findAll()) {
            if (candidate.origin() != PullRequest.Origin.REVIEW_REQUESTED || !isOpen(candidate)) {
                continue;
            }
            String workspaceId = resolveWorkspace(candidate.repo());
            if (workspaceId == null) {
                continue;
            }
            try {
                PR pr = prSync.syncExternalPR(candidate.repo(), candidate.number())
                        .orElseThrow(() -> new IllegalStateException("PR could not be synchronized"));
                InvestigationReviewData existing = reviews.findByPr(pr.id()).orElse(null);
                if (existing != null && existing.rounds().stream()
                        .anyMatch(round -> "RUNNING".equals(round.status()))) {
                    continue;
                }
                if (existing != null && !"STALE".equals(existing.review().status())) {
                    continue;
                }

                int nextCapCents = existing == null || existing.rounds().isEmpty()
                        ? 150
                        : existing.rounds().get(existing.rounds().size() - 1).budgetJson().costCapCents();
                if (reservedMilli + nextCapCents * 10L > DAILY_COST_CAP_MILLI) {
                    break;
                }

                InvestigationReviewService.StartOptions options =
                        new InvestigationReviewService.StartOptions(null, null, workspaceId);
                if (existing == null) {
                    reviews.start(pr.id(), options);
                }
                else {
                    reviews.createRound(existing.review().id(), "re-review", List.of(), options);
                }
                reservedMilli += nextCapCents * 10L;
                started++;
            }
            catch (RuntimeException e) {
                log.warn("Scheduled AgentReview of {}#{} failed: {}",
                        candidate.repo(), candidate.number(), e.getMessage());
            }
        }
        if (started > 0) {
            log.info("Scheduled {} AgentReview thread(s)", started);
        }
    }

    public boolean isEnabled()
    {
        return appSettings.get(Key.SCHEDULED_REVIEWS_ENABLED)
                .map(value -> "true".equalsIgnoreCase(value.trim()))
                .orElse(false);
    }

    public void setEnabled(boolean enabled)
    {
        appSettings.set(Key.SCHEDULED_REVIEWS_ENABLED, enabled ? "true" : "false");
    }

    private String resolveWorkspace(String repo)
    {
        List<String> owners = workspaces.listWorkspaces().stream()
                .filter(workspace -> workspaces.listRepos(workspace.id()).stream()
                        .map(WorkspaceRepo::repoFullName)
                        .anyMatch(repo::equalsIgnoreCase))
                .map(Workspace::id)
                .toList();
        if (owners.size() == 1) {
            return owners.get(0);
        }
        if (owners.contains(DEFAULT_WORKSPACE)) {
            return DEFAULT_WORKSPACE;
        }
        if (owners.isEmpty()) {
            return DEFAULT_WORKSPACE;
        }
        log.warn("Skipping scheduled AgentReview for {}: attached to multiple workspaces {}",
                repo, owners);
        return null;
    }

    private static boolean isOpen(PullRequest pr)
    {
        return pr.state() == null || "open".equalsIgnoreCase(pr.state().trim());
    }
}
