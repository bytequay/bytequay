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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Coordinates scheduled and user-requested pull-request refreshes. */
@Service
public class PullRequestRefreshService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestRefreshService.class);

    private final PullRequestService pullRequests;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final Executor executor;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final Object pendingLock = new Object();

    private boolean allRequested;
    private final Set<RepoRef> requestedRepositories = new LinkedHashSet<>();

    public PullRequestRefreshService(
            PullRequestService pullRequests,
            CredentialService credentials,
            WorkspaceStore workspaces,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** Refreshes one pull request and returns the newly persisted detail. */
    public PullRequestDetail refreshDetail(String repo, int number)
    {
        pullRequests.refreshPullRequestDetailFromGitHub(repo, number);
        return pullRequests.getPullRequestDetail(repo, number);
    }

    /** Refreshes one diff snapshot and returns the newly persisted files. */
    public List<DiffFile> refreshDiffFiles(
            String repo,
            int number,
            String baseSha,
            String headSha)
    {
        pullRequests.refreshPullRequestDiffFromGitHub(repo, number, baseSha, headSha);
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFiles(repo, number)
                : pullRequests.getPullRequestDiffFiles(repo, number, baseSha, headSha);
    }

    /** Refreshes one file body and returns the newly persisted content. */
    public DiffFileContent refreshDiffFileContent(
            String repo,
            int number,
            String path,
            String baseSha,
            String headSha)
    {
        pullRequests.refreshPullRequestDiffFileContentFromGitHub(
                repo, number, path, baseSha, headSha);
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFileContent(repo, number, path)
                : pullRequests.getPullRequestDiffFileContent(
                        repo, number, path, baseSha, headSha);
    }

    /** Queues a refresh of the global dashboard and monitored repositories. */
    public void requestRefresh()
    {
        synchronized (pendingLock) {
            allRequested = true;
            requestedRepositories.clear();
        }
        startDrain();
    }

    /** Queues a refresh of one repository. */
    public void requestRefresh(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        synchronized (pendingLock) {
            if (!allRequested) {
                requestedRepositories.add(repository);
            }
        }
        startDrain();
    }

    private void startDrain()
    {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        }
        catch (RuntimeException failure) {
            draining.set(false);
            throw failure;
        }
    }

    private void drain()
    {
        try {
            while (true) {
                Optional<Batch> next = takeBatch();
                if (next.isEmpty()) {
                    return;
                }
                refresh(next.orElseThrow());
            }
        }
        finally {
            draining.set(false);
            if (hasPending()) {
                startDrain();
            }
        }
    }

    private Optional<Batch> takeBatch()
    {
        synchronized (pendingLock) {
            if (!allRequested && requestedRepositories.isEmpty()) {
                return Optional.empty();
            }
            Batch batch = new Batch(allRequested, ImmutableList.copyOf(requestedRepositories));
            allRequested = false;
            requestedRepositories.clear();
            return Optional.of(batch);
        }
    }

    private boolean hasPending()
    {
        synchronized (pendingLock) {
            return allRequested || !requestedRepositories.isEmpty();
        }
    }

    private void refresh(Batch batch)
    {
        if (!credentials.isGitHubAccountConfigured()) {
            log.debug("Pull request refresh skipped: no GitHub account configured");
            return;
        }
        try {
            if (batch.allRepositories()) {
                refreshAllScopes();
                return;
            }
            for (RepoRef repository : batch.repositories()) {
                refreshScope(repository.fullName(), () -> pullRequests.syncRepository(repository));
            }
        }
        catch (RuntimeException failure) {
            log.warn("Pull request refresh stopped: {}", failure.getMessage());
        }
    }

    private void refreshAllScopes()
    {
        refreshScope("global dashboard", pullRequests::syncFromGitHub);
        for (RepoRef repository : monitoredRepositories()) {
            refreshScope(repository.fullName(), () -> pullRequests.syncRepository(repository));
        }
    }

    private List<RepoRef> monitoredRepositories()
    {
        return workspaces.findAll().stream()
                .map(Workspace::monitoredRepository)
                .distinct()
                .collect(toImmutableList());
    }

    private void refreshScope(String scope, Runnable refresh)
    {
        try {
            refresh.run();
        }
        catch (RuntimeException failure) {
            log.warn("Pull request refresh failed for {}: {}", scope, failure.getMessage());
        }
    }

    private record Batch(boolean allRepositories, List<RepoRef> repositories)
    {
        private Batch
        {
            repositories = ImmutableList.copyOf(requireNonNull(repositories, "repositories is null"));
        }
    }
}
