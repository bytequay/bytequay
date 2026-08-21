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
package com.bytequay.app.workspace;

import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueSnapshot;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceIssueService
        implements WorkspaceIssueService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceIssueService.class);

    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final GitHubRepoClient gitHub;
    private final CredentialService credentials;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public DefaultWorkspaceIssueService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            GitHubRepoClient gitHub,
            CredentialService credentials,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public RepositoryIssueSnapshot list(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        return issues.find(workspace.monitoredRepositoryId())
                .orElseGet(() -> RepositoryIssueSnapshot.empty(
                        workspace.monitoredRepositoryId(), workspace.monitoredRepository()));
    }

    @Override
    public RepositoryIssueSnapshot synchronize(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
            List<RepositoryIssue> fresh = gitHub.fetchOpenIssues(
                    credentials.resolveGitHubToken(), workspace.monitoredRepository());
            Instant now = clock.instant();
            issues.replace(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), fresh, now);
            return issues.find(workspace.monitoredRepositoryId()).orElseThrow();
        }
        catch (RuntimeException failure) {
            String reference = UUID.randomUUID().toString();
            log.warn("Workspace {} issue synchronization failed [{}]: {}",
                    workspaceId, reference, failure.getMessage());
            throw WorkspaceException.issueSyncFailed(reference);
        }
        finally {
            lock.unlock();
        }
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }
}
