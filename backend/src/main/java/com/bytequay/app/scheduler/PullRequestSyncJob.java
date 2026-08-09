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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.SyncSettingsService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Background job that periodically fetches pull requests from GitHub and writes
 * them to the local database.
 *
 * <p>The scheduler ticks every 10 seconds. Inside each tick it checks whether
 * the configured sync interval has elapsed before actually hitting the GitHub API.
 * This allows the interval to be changed at runtime without restarting — the new
 * value takes effect within one tick (≤ 10 seconds).
 *
 * <p>{@link #requestImmediateSync()} submits the sync to the shared application executor
 * right away rather than waiting for the next tick, which is used at startup so the PR list
 * is populated as soon as the PAT is registered.
 */
@Component
public class PullRequestSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestSyncJob.class);

    private final PullRequestService pullRequestService;
    private final PullRequestStore store;
    private final SyncSettingsService syncSettings;
    private final CredentialService credentialService;
    private final Executor executor;
    private final QuietHoursPolicy quietHours;
    private final PRSyncService prSyncService;

    /** Guards against overlapping syncs whether triggered by tick or immediate request. */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public PullRequestSyncJob(
            PullRequestService pullRequestService,
            PullRequestStore store,
            SyncSettingsService syncSettings,
            CredentialService credentialService,
            QuietHoursPolicy quietHours,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor,
            PRSyncService prSyncService)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.store = requireNonNull(store, "store is null");
        this.syncSettings = requireNonNull(syncSettings, "syncSettings is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.quietHours = requireNonNull(quietHours, "quietHours is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.prSyncService = requireNonNull(prSyncService, "prSyncService is null");
    }

    /**
     * Fires a sync immediately on the shared application executor, bypassing the interval check.
     * Returns as soon as the thread is submitted and does not block.
     */
    public void requestImmediateSync()
    {
        executor.execute(this::doSync);
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick()
    {
        // Pause scheduled syncing overnight to conserve rate limit. Only
        // the timer is gated — requestImmediateSync() and every other
        // user-initiated GitHub call run regardless of the hour.
        if (quietHours.isQuietNow()) {
            return;
        }
        if (!isIntervalElapsed()) {
            return;
        }
        doSync();
    }

    private void doSync()
    {
        // Sync uses the account-level credential. Per-PR detail fetches inside
        // PullRequestService consult the per-repo override on top of this.
        Optional<String> pat = accountPat();
        if (pat.isEmpty()) {
            log.debug("Sync skipped: no GitHub PAT registered");
            return;
        }

        if (!syncing.compareAndSet(false, true)) {
            log.debug("Sync skipped: already in progress");
            return;
        }

        try {
            log.info("Syncing pull requests from GitHub");
            pullRequestService.syncFromGitHub();
            log.info("Sync complete");
        }
        catch (Exception e) {
            log.warn("Pull request sync failed: {}", e.getMessage());
        }
        finally {
            syncing.set(false);
        }

        // Unified-dashboard sweep, running alongside the legacy sync above
        // during its soak period (unified-pr-view.md's dashboard migration)
        // — writes to the disjoint `pr`/`pr_triage` tables, so this never
        // conflicts with the legacy pass. Own try/catch: a failure here
        // must never block the legacy sync that just ran.
        try {
            prSyncService.syncList();
        }
        catch (Exception e) {
            log.warn("Unified dashboard sync failed: {}", e.getMessage());
        }
    }

    private boolean isIntervalElapsed()
    {
        int intervalSeconds = syncSettings.getSettings().intervalSeconds();
        return store.lastSyncedAt()
                .map(lastSync -> Duration.between(lastSync, Instant.now()).toSeconds() >= intervalSeconds)
                .orElse(true);
    }

    private Optional<String> accountPat()
    {
        return credentialService.getSecret(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .filter(secret -> !secret.isBlank());
    }
}
