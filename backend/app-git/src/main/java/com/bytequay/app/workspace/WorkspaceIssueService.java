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

import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.GitHubIssuePreview;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.IssueSyncPage;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Owns cached workspace issues, their refresh, and explicit user writes. */
@Service
public class WorkspaceIssueService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIssueService.class);

    private static final int MAX_SYNC_PAGES = 50;

    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final RepositoryIssueDetailStore details;
    private final WorkspaceHostingService hosting;
    private final List<IssueClient> clients;
    private final RecentTouchRecorder recent;
    private final Clock clock;
    private final AppSettingsStore settings;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspaceIssueService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            RepositoryIssueDetailStore details,
            WorkspaceHostingService hosting,
            List<IssueClient> clients,
            RecentTouchRecorder recent,
            Clock clock,
            AppSettingsStore settings)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.clients = ImmutableList.copyOf(clients);
        this.recent = requireNonNull(recent, "recent is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    /**
     * Reads the latest local issue projection for a workspace's monitored repository.
     *
     * @param workspaceId workspace used to resolve the stable monitored repository ID
     * @return one cached page of open issues and its freshness timestamp
     */
    public RepositoryIssuePage list(String workspaceId, int page, int pageSize)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        return cachedPage(workspace, page, pageSize);
    }

    /** Returns one issue from the local projection without contacting its hosting server. */
    public Optional<RepositoryIssue> find(String workspaceId, int issueNumber)
    {
        requireWorkspace(workspaces, workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return workspaces.findHostingRepositoryId(workspaceId).flatMap(id -> issues.find(id, issueNumber));
    }

    /** Reads an open or closed issue through the workspace's selected hosting account. */
    public GitHubIssuePreview preview(String workspaceId, int issueNumber)
    {
        requireWorkspace(workspaces, workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        WorkspaceHostingRead route = hosting.captureRead(workspaceId);
        return client(route).preview(route.session().access(), route.repository(), issueNumber);
    }

    /** Returns locally persisted activity and type data without contacting its hosting server. */
    public RepositoryIssueDetail detail(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return cachedDetail(workspace, issueNumber);
    }

    /**
     * Posts a user-authored comment to the selected issue.
     *
     * @param workspaceId workspace used to resolve the monitored repository
     * @param issueNumber repository-scoped issue number
     * @param body non-blank Markdown comment body
     * @return the created comment as a timeline event
     */
    public PrTimelineEvent comment(String workspaceId, int issueNumber, String body)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            checkText(body, "comment body");
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            long repositoryId = route.repository().id();
            Optional<RepositoryIssue> previous = issues.find(repositoryId, issueNumber);
            PrTimelineEvent created = client(route).comment(route.session().access(), route.repository(), issueNumber, body.trim());
            if (created.githubId() == null) {
                // Command-only writes have no note to append; refresh the state they changed.
                try {
                    Optional<RepositoryIssue> issue = client(route).find(route.session().access(), route.repository(), issueNumber);
                    if (issue.isPresent()) {
                        issues.saveSyncPage(repositoryId, ImmutableList.of(issue.orElseThrow()));
                    }
                    else {
                        issues.delete(repositoryId, issueNumber);
                    }
                    details.save(client(route).detail(route.session().access(), route.repository(), issueNumber));
                    recent.issueAction(workspaceId, issueNumber, RecentReason.EDITED);
                }
                catch (RuntimeException failure) {
                    log.warn("Issue command succeeded but its local refresh failed: {}", failure.toString());
                }
                return created;
            }
            RepositoryIssueDetail current = cachedDetail(workspace, issueNumber);
            details.save(current.append(created));
            if (created.timestamp() != null) {
                previous.ifPresent(issue -> issues.updateActivity(repositoryId, issueNumber, issue.commentCount() + 1, created.timestamp()));
            }
            refreshActivity(route, issueNumber);
            recent.issueAction(workspaceId, issueNumber, RecentReason.COMMENTED);
            recent.wroteOnIssue(
                    workspaceId,
                    issueNumber,
                    RecentTextKind.COMMENT,
                    "comment:" + (created.githubId() == null ? created.timestamp() : created.githubId()),
                    body.trim());
            return created;
        });
    }

    public void close(String workspaceId, int issueNumber)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            requireWorkspace(workspaces, workspaceId);
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            client(route).close(route.session().access(), route.repository(), issueNumber);
            recent.issueAction(workspaceId, issueNumber, RecentReason.EDITED);
            issues.delete(route.repository().id(), issueNumber);
        });
    }

    public IssueTypeOptions updateIssueType(String workspaceId, int issueNumber, String type)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            String normalized = type;
            if (normalized != null) {
                checkText(normalized, "issue type");
                normalized = normalized.trim();
            }
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            String current = client(route).updateType(route.session().access(), route.repository(), issueNumber, normalized);
            RepositoryIssueDetail detail = cachedDetail(workspace, issueNumber)
                    .withType(current);
            details.save(detail);
            refreshActivity(route, issueNumber);
            recent.issueAction(workspaceId, issueNumber, RecentReason.EDITED);
            return new IssueTypeOptions(current, detail.availableTypes());
        });
    }

    public IssueMetadata updateMetadata(
            String workspaceId,
            int issueNumber,
            List<String> assignees,
            List<String> labels)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            requireWorkspace(workspaces, workspaceId);
            List<String> normalizedAssignees = normalizeNames(assignees, "assignees");
            List<String> normalizedLabels = normalizeNames(labels, "labels");
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            var updated = client(route).updateMetadata(
                    route.session().access(), route.repository(), issueNumber,
                    normalizedAssignees,
                    normalizedLabels);
            issues.updateMetadata(
                    route.repository().id(),
                    issueNumber,
                    updated.assignees(),
                    updated.labels(),
                    updated.updatedAt() == null ? clock.instant() : updated.updatedAt());
            recent.issueAction(workspaceId, issueNumber, RecentReason.EDITED);
            return new IssueMetadata(updated.assignees(), updated.labels());
        });
    }

    public void refreshNotifiedIssue(RepoRef repository, int issueNumber)
    {
        requireNonNull(repository, "repository is null");
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        Set<Long> refreshed = new HashSet<>();
        for (Workspace candidate : workspaces.findAll()) {
            if (!candidate.isGitHub() || !candidate.monitoredRepository().fullName().equalsIgnoreCase(repository.fullName())
                    || !refreshed.add(candidate.monitoredRepositoryId())) {
                continue;
            }
            WorkspaceOperationLocks.withLock(candidate.id(), () -> {
                Workspace workspace = requireWorkspace(workspaces, candidate.id());
                WorkspaceHostingRead route = hosting.captureRead(workspace.id());
                Lock lock = locks.get(route.repository().id());
                withLock(lock, () -> {
                    Optional<RepositoryIssue> issue = client(route).find(route.session().access(), route.repository(), issueNumber);
                    if (issue.isPresent()) {
                        issues.saveSyncPage(route.repository().id(), ImmutableList.of(issue.orElseThrow()));
                    }
                    else {
                        issues.delete(route.repository().id(), issueNumber);
                    }
                    return null;
                });
            });
        }
    }

    /** Incremental open-card changes and closures; a completed cursor survives sleep and restarts. */
    public void refreshRecentIssues()
    {
        Set<String> refreshed = new HashSet<>();
        for (Workspace workspace : workspaces.findAll()) {
            try {
                WorkspaceOperationLocks.withLock(workspace.id(), () -> refreshRecentIssuesLocked(workspace.id(), refreshed));
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure) || failure instanceof ResponseStatusException response && response.getStatusCode().value() == 401) {
                    throw failure;
                }
                log.warn("Recent issue refresh failed for {}: {}", workspace.monitoredRepository(), failure.getMessage(), failure);
            }
        }
    }

    private void refreshRecentIssuesLocked(String workspaceId, Set<String> refreshed)
    {
        requireWorkspace(workspaces, workspaceId);
        WorkspaceHostingRead route = hosting.captureRead(workspaceId);
        if (!refreshed.add(route.session().cacheScope() + ":" + route.repository().id())) {
            return;
        }
        Lock lock = locks.get(route.repository().id());
        withLock(lock, () -> {
            String checkpointKey = "hosting.issues.checkpoint." + route.session().cacheScope() + "." + route.repository().id();
            Instant startedAt = clock.instant();
            Optional<Instant> checkpoint = settings.get(checkpointKey).flatMap(WorkspaceIssueService::parseInstant)
                    .filter(at -> !at.isAfter(startedAt));
            if (checkpoint.filter(at -> at.plus(Duration.ofMinutes(2)).isAfter(startedAt)).isPresent()) {
                return null;
            }
            Instant since = checkpoint.map(at -> at.minus(Duration.ofMinutes(5))).orElse(startedAt.minus(Duration.ofHours(1)));
            for (int page = 1; ; page++) {
                IssueSyncPage fetched = client(route).list(route.session().access(), route.repository(), Optional.of(since), page);
                issues.saveSyncPage(route.repository().id(), fetched.openIssues());
                fetched.closedNumbers().forEach(number -> issues.delete(route.repository().id(), number));
                if (!fetched.hasNext()) {
                    break;
                }
                checkState(page != MAX_SYNC_PAGES, "Recent issue refresh exceeded 5000 rows");
            }
            settings.put(checkpointKey, startedAt.toString());
            return null;
        });
    }

    private static Optional<Instant> parseInstant(String value)
    {
        try {
            return Optional.of(Instant.parse(value));
        }
        catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Runs the full open-issue sync only when the last one is older than {@code age}. The one-minute recent refresh keeps
     * changed and closed issues current in between; a full sync also catches issues that left the repository.
     */
    public void refreshIssuesOlderThan(String workspaceId, Duration age)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Optional<Instant> synced = cachedPage(workspace, 0, 1).syncedAt();
        if (synced.filter(at -> at.plus(age).isAfter(clock.instant())).isEmpty()) {
            refreshIssues(workspaceId);
        }
    }

    public RepositoryIssuePage refreshIssues(String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            return refreshLocked(workspaceId, route.repository().id(), () -> {
                refreshIssueRows(route);
                return issues.findPage(route.repository().id(), workspace.monitoredRepository(), 0, 50);
            });
        });
    }

    public RepositoryIssueDetail refreshDetail(String workspaceId, int issueNumber)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            requireWorkspace(workspaces, workspaceId);
            checkArgument(issueNumber > 0, "issueNumber must be positive");
            WorkspaceHostingRead route = hosting.captureRead(workspaceId);
            return refreshLocked(workspaceId, route.repository().id() + ":" + issueNumber, () -> {
                RepositoryIssueDetail fetched = client(route).detail(route.session().access(), route.repository(), issueNumber);
                RepositoryIssueDetail detail = new RepositoryIssueDetail(
                        route.repository().id(), issueNumber, fetched.timeline(), fetched.currentType(), fetched.availableTypes(),
                        Optional.of(clock.instant()));
                details.save(detail);
                return details.find(route.repository().id(), issueNumber)
                        .orElseThrow(() -> new IllegalStateException("Issue detail refresh was not persisted"));
            });
        });
    }

    private <T> T refreshLocked(String workspaceId, Object key, Supplier<T> refresh)
    {
        Lock lock = locks.get(key);
        return withLock(lock, () -> {
            try {
                return refresh.get();
            }
            catch (RuntimeException failure) {
                throw refreshFailure(workspaceId, failure);
            }
        });
    }

    private void refreshIssueRows(WorkspaceHostingRead route)
    {
        Set<Long> open = new HashSet<>();
        for (int page = 1; ; page++) {
            IssueSyncPage fetched = client(route).list(route.session().access(), route.repository(), Optional.empty(), page);
            issues.saveSyncPage(route.repository().id(), fetched.openIssues());
            fetched.openIssues().forEach(issue -> open.add(issue.id()));
            if (!fetched.hasNext()) {
                break;
            }
            checkState(page != MAX_SYNC_PAGES, "Issue refresh exceeded 5000 rows");
        }
        issues.completeSync(route.repository().id(), open, clock.instant());
    }

    private RuntimeException refreshFailure(String workspaceId, RuntimeException failure)
    {
        if (isRateLimit(failure) || failure instanceof HostingFailure) {
            return failure;
        }
        String reference = UUID.randomUUID().toString();
        log.warn("Workspace {} issue refresh failed [{}]: {}", workspaceId, reference, failure.getMessage());
        return WorkspaceException.issueSyncFailed(reference);
    }

    private RepositoryIssueDetail cachedDetail(Workspace workspace, int issueNumber)
    {
        return workspaces.findHostingRepositoryId(workspace.id()).flatMap(id -> details.find(id, issueNumber))
                .orElseGet(() -> RepositoryIssueDetail.empty(workspaces.findHostingRepositoryId(workspace.id()).orElse(workspace.monitoredRepositoryId()), issueNumber));
    }

    private RepositoryIssuePage cachedPage(Workspace workspace, int page, int pageSize)
    {
        return workspaces.findHostingRepositoryId(workspace.id()).map(id -> issues.findPage(id, workspace.monitoredRepository(), page, pageSize))
                .orElseGet(() -> new RepositoryIssuePage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(),
                        ImmutableList.of(), page, pageSize, 0, false, Optional.empty()));
    }

    public GitHubIssueSource source(String workspaceId, int number, boolean requireOpen)
    {
        WorkspaceHostingRead route = hosting.captureRead(workspaceId);
        return client(route).source(route.session().access(), route.repository(), number, requireOpen);
    }

    public GitHubIssueSource create(String workspaceId, String title, String body)
    {
        WorkspaceHostingRead route = hosting.captureRead(workspaceId);
        return client(route).create(route.session().access(), route.repository(), title, body);
    }

    private IssueClient client(WorkspaceHostingRead route)
    {
        return clients.stream().filter(client -> client.provider() == route.session().access().server().provider()).findFirst().orElseThrow();
    }

    private void refreshActivity(WorkspaceHostingRead route, int issueNumber)
    {
        try {
            var activity = client(route).activity(route.session().access(), route.repository(), issueNumber);
            issues.updateActivity(route.repository().id(), issueNumber, activity.commentCount(), activity.updatedAt());
        }
        catch (RuntimeException failure) {
            // The remote write already succeeded; failing it here would invite posting the same comment twice.
            log.warn("Issue {}/{} activity refresh failed after a successful write", route.repository().fullPath(), issueNumber, failure);
        }
    }

    public record IssueTypeOptions(String current, List<String> available)
    {
        public IssueTypeOptions
        {
            available = ImmutableList.copyOf(available);
        }
    }

    public record IssueMetadata(List<String> assignees, List<String> labels)
    {
        public IssueMetadata
        {
            assignees = ImmutableList.copyOf(assignees);
            labels = ImmutableList.copyOf(labels);
        }
    }
}
