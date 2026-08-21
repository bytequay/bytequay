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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static java.util.Objects.requireNonNull;

/**
 * Background job that periodically refreshes the global pull-request dashboard and each
 * monitored workspace repository.
 *
 * <p>{@link #requestImmediateSync()} submits the sync to the shared application executor
 * right away rather than waiting for the next tick. Startup, watch, and manual workspace sync
 * use this path so every refresh shares one overlap guard.</p>
 */
@Component
public class PullRequestSyncJob
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestSyncJob.class);

    private final PullRequestService pullRequestService;
    private final CredentialService credentialService;
    private final WorkspaceStore workspaceStore;
    private final Executor executor;

    /** Guards against overlapping syncs whether triggered by tick or immediate request. */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public PullRequestSyncJob(
            PullRequestService pullRequestService,
            CredentialService credentialService,
            WorkspaceStore workspaceStore,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * Fires a sync immediately on the shared application executor, bypassing the interval check.
     * Returns as soon as the thread is submitted and does not block.
     */
    public void requestImmediateSync()
    {
        executor.execute(this::doSync);
    }

    /** Synchronizes one monitored repository under the same overlap guard as the periodic job. */
    public void synchronizeRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        runSync("repository " + repository.fullName(), () -> pullRequestService.syncRepository(repository));
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick()
    {
        doSync();
    }

    private void doSync()
    {
        runSync("global and workspace pull requests", this::syncAll);
    }

    private void syncAll()
    {
        synchronizeScope("global dashboard", pullRequestService::syncFromGitHub);
        ImmutableList<RepoRef> repositories = workspaceStore.findAll().stream()
                .map(Workspace::monitoredRepository)
                .distinct()
                .collect(ImmutableList.toImmutableList());
        for (RepoRef repository : repositories) {
            synchronizeScope(repository.fullName(), () -> pullRequestService.syncRepository(repository));
        }
    }

    private void synchronizeScope(String scope, Runnable synchronization)
    {
        try {
            synchronization.run();
        }
        catch (RuntimeException failure) {
            log.warn("Pull request sync failed for {}: {}", scope, failure.getMessage());
        }
    }

    private void runSync(String scope, Runnable synchronization)
    {
        // Sync uses the account-level GitHub credential.
        if (!credentialService.isGitHubAccountConfigured()) {
            log.debug("Sync skipped: no GitHub PAT registered");
            return;
        }

        if (!syncing.compareAndSet(false, true)) {
            log.debug("Sync skipped: already in progress");
            return;
        }

        try {
            log.info("Syncing {} from GitHub", scope);
            synchronization.run();
            log.info("Sync complete for {}", scope);
        }
        catch (Exception e) {
            log.warn("Pull request sync failed for {}: {}", scope, e.getMessage());
        }
        finally {
            syncing.set(false);
        }
    }
}
