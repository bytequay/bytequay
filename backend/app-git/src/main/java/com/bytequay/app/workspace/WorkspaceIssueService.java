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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
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
    private final GitHubPullRequestWriteClient issueWrites;
    private final GitHubRepoClient issueReads;
    private final CredentialService credentials;
    private final RecentTouchRecorder recent;
    private final Clock clock;
    private final GitHubPullRequestReadClient timelineReads;
    private final AppSettingsStore settings;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspaceIssueService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            RepositoryIssueDetailStore details,
            GitHubPullRequestWriteClient issueWrites,
            GitHubRepoClient issueReads,
            CredentialService credentials,
            RecentTouchRecorder recent,
            Clock clock,
            GitHubPullRequestReadClient timelineReads,
            AppSettingsStore settings)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
        this.issueWrites = requireNonNull(issueWrites, "issueWrites is null");
        this.issueReads = requireNonNull(issueReads, "issueReads is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.timelineReads = requireNonNull(timelineReads, "timelineReads is null");
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
        return issues.findPage(
                workspace.monitoredRepositoryId(),
                workspace.monitoredRepository(),
                page,
                pageSize);
    }

    /** Returns one issue from the local projection without contacting GitHub. */
    public Optional<RepositoryIssue> find(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return issues.find(workspace.monitoredRepositoryId(), issueNumber);
    }

    /** Returns locally persisted activity and type data without contacting GitHub. */
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
     * @param issueNumber GitHub issue number
     * @param body non-blank Markdown comment body
     * @return the created comment as a timeline event
     */
    public PrTimelineEvent comment(String workspaceId, int issueNumber, String body)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            checkText(body, "comment body");
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            Optional<RepositoryIssue> previous = issues.find(workspace.monitoredRepositoryId(), issueNumber);
            PrTimelineEvent created = issueWrites.createIssueComment(
                    credentials.resolveGitHubToken(), issueRef(workspace, issueNumber), body.trim());
            RepositoryIssueDetail current = cachedDetail(workspace, issueNumber);
            details.save(current.append(created));
            if (created.timestamp() != null) {
                previous.ifPresent(issue -> issues.updateActivity(workspace.monitoredRepositoryId(), issueNumber, issue.commentCount() + 1, created.timestamp()));
            }
            refreshActivity(workspace, issueNumber);
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
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            issueWrites.closeIssue(credentials.resolveGitHubToken(), issueRef(workspace, issueNumber));
            recent.issueAction(workspaceId, issueNumber, RecentReason.EDITED);
            issues.delete(workspace.monitoredRepositoryId(), issueNumber);
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
            String current = issueWrites.updateIssueType(
                    credentials.resolveGitHubToken(), issueRef(workspace, issueNumber), normalized);
            RepositoryIssueDetail detail = cachedDetail(workspace, issueNumber)
                    .withType(current);
            details.save(detail);
            refreshActivity(workspace, issueNumber);
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
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            List<String> normalizedAssignees = normalizeNames(assignees, "assignees");
            List<String> normalizedLabels = normalizeNames(labels, "labels");
            GitHubPullRequestWriteClient.IssueMetadata updated = issueWrites.updateIssueMetadata(
                    credentials.resolveGitHubToken(),
                    issueRef(workspace, issueNumber),
                    normalizedAssignees,
                    normalizedLabels);
            issues.updateMetadata(
                    workspace.monitoredRepositoryId(),
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
            if (!candidate.monitoredRepository().fullName().equalsIgnoreCase(repository.fullName())
                    || !refreshed.add(candidate.monitoredRepositoryId())) {
                continue;
            }
            WorkspaceOperationLocks.withLock(candidate.id(), () -> {
                Workspace workspace = requireWorkspace(workspaces, candidate.id());
                Lock lock = locks.get(workspace.monitoredRepositoryId());
                withLock(lock, () -> {
                    GitHubCredential credential = credentials.resolveGitHubCredential();
                    Optional<RepositoryIssue> issue = issueReads.fetchIssue(credential.token(), workspace.monitoredRepository(), issueNumber);
                    requireSameAccount(credential);
                    if (issue.isPresent()) {
                        issues.saveSyncPage(workspace.monitoredRepositoryId(), ImmutableList.of(issue.orElseThrow()));
                    }
                    else {
                        issues.delete(workspace.monitoredRepositoryId(), issueNumber);
                    }
                    return null;
                });
            });
        }
    }

    /** Incremental open-card changes and closures; a completed cursor survives sleep and restarts. */
    public void refreshRecentIssues()
    {
        Set<Long> refreshed = new HashSet<>();
        for (Workspace workspace : workspaces.findAll()) {
            if (!refreshed.add(workspace.monitoredRepositoryId())) {
                continue;
            }
            try {
                WorkspaceOperationLocks.withLock(workspace.id(), () -> refreshRecentIssuesLocked(workspace.id()));
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure) || failure instanceof ResponseStatusException response && response.getStatusCode().value() == 401) {
                    throw failure;
                }
                log.warn("Recent issue refresh failed for {}: {}", workspace.monitoredRepository(), failure.getMessage());
            }
        }
    }

    private void refreshRecentIssuesLocked(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        withLock(lock, () -> {
            GitHubCredential credential = credentials.resolveGitHubCredential();
            String checkpointKey = "github.issues.checkpoint." + credential.profile().login().toLowerCase(Locale.ROOT)
                    + "." + workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT);
            Instant startedAt = clock.instant();
            Optional<Instant> checkpoint = settings.get(checkpointKey).flatMap(WorkspaceIssueService::parseInstant)
                    .filter(at -> !at.isAfter(startedAt));
            if (checkpoint.filter(at -> at.plus(Duration.ofMinutes(2)).isAfter(startedAt)).isPresent()) {
                return null;
            }
            Instant since = checkpoint.map(at -> at.minus(Duration.ofMinutes(5))).orElse(startedAt.minus(Duration.ofHours(1)));
            for (int page = 1; ; page++) {
                GitHubRepoClient.ChangedIssuePage fetched = issueReads.fetchChangedIssuesPage(credential.token(), workspace.monitoredRepository(), since, page);
                requireSameAccount(credential);
                issues.saveSyncPage(workspace.monitoredRepositoryId(), fetched.openIssues());
                fetched.closedNumbers().forEach(number -> issues.delete(workspace.monitoredRepositoryId(), number));
                if (!fetched.hasNext()) {
                    break;
                }
                checkState(page != MAX_SYNC_PAGES, "Recent GitHub issue refresh exceeded 5000 rows");
            }
            requireSameAccount(credential);
            settings.put(checkpointKey, startedAt.toString());
            return null;
        });
    }

    private void requireSameAccount(GitHubCredential credential)
    {
        checkState(credentials.cachedLogin().filter(login -> login.equalsIgnoreCase(credential.profile().login())).isPresent(), "GitHub account changed during issue refresh");
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
        Optional<Instant> synced = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, 1).syncedAt();
        if (synced.filter(at -> at.plus(age).isAfter(clock.instant())).isEmpty()) {
            refreshIssues(workspaceId);
        }
    }

    public RepositoryIssuePage refreshIssues(String workspaceId)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            return refreshLocked(workspaceId, workspace.monitoredRepositoryId(), () -> {
                refreshIssueRows(workspace);
                return issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, 50);
            });
        });
    }

    public RepositoryIssueDetail refreshDetail(String workspaceId, int issueNumber)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Workspace workspace = requireWorkspace(workspaces, workspaceId);
            checkArgument(issueNumber > 0, "issueNumber must be positive");
            return refreshLocked(workspaceId, workspace.monitoredRepositoryId() + ":" + issueNumber, () -> {
                String token = credentials.resolveGitHubToken();
                RepositoryIssueDetail detail = new RepositoryIssueDetail(
                        workspace.monitoredRepositoryId(),
                        issueNumber,
                        fetchTimeline(token, issueRef(workspace, issueNumber)),
                        issueReads.fetchIssueType(token, workspace.monitoredRepository(), issueNumber),
                        issueReads.fetchIssueTypes(token, workspace.monitoredRepository()),
                        Optional.of(clock.instant()));
                details.save(detail);
                return details.find(workspace.monitoredRepositoryId(), issueNumber)
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

    private void refreshIssueRows(Workspace workspace)
    {
        String token = credentials.resolveGitHubToken();
        Set<Long> open = new HashSet<>();
        for (int page = 1; ; page++) {
            GitHubRepoClient.IssuePage fetched = issueReads.fetchOpenIssuesPage(token, workspace.monitoredRepository(), page);
            issues.saveSyncPage(workspace.monitoredRepositoryId(), fetched.issues());
            fetched.issues().forEach(issue -> open.add(issue.id()));
            if (!fetched.hasNext()) {
                break;
            }
            checkState(page != MAX_SYNC_PAGES, "GitHub issue refresh exceeded 5000 rows");
        }
        issues.completeSync(workspace.monitoredRepositoryId(), open, clock.instant());
    }

    private List<PrTimelineEvent> fetchTimeline(String token, PullRequestRef issue)
    {
        return Stream.concat(
                        timelineReads.fetchPrTimeline(token, issue).stream()
                                .filter(event -> !"commented".equals(event.event())),
                        timelineReads.fetchPrIssueComments(token, issue).stream())
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(toImmutableList());
    }

    private RuntimeException refreshFailure(String workspaceId, RuntimeException failure)
    {
        if (isRateLimit(failure)) {
            return failure;
        }
        String reference = UUID.randomUUID().toString();
        log.warn("Workspace {} issue refresh failed [{}]: {}", workspaceId, reference, failure.getMessage());
        return WorkspaceException.issueSyncFailed(reference);
    }

    private RepositoryIssueDetail cachedDetail(Workspace workspace, int issueNumber)
    {
        return details.find(workspace.monitoredRepositoryId(), issueNumber)
                .orElseGet(() -> RepositoryIssueDetail.empty(workspace.monitoredRepositoryId(), issueNumber));
    }

    private static PullRequestRef issueRef(Workspace workspace, int issueNumber)
    {
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return PullRequestRef.of(
                workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), issueNumber);
    }

    private void refreshActivity(Workspace workspace, int issueNumber)
    {
        try {
            GitHubRepoClient.IssueActivity activity = issueReads.fetchIssueActivity(
                    credentials.resolveGitHubToken(), workspace.monitoredRepository(), issueNumber);
            issues.updateActivity(workspace.monitoredRepositoryId(), issueNumber, activity.commentCount(), activity.updatedAt());
        }
        catch (RuntimeException failure) {
            // The GitHub write already succeeded; failing it here would invite posting the same comment twice.
            log.warn("Issue {}/{} activity refresh failed after a successful write", workspace.monitoredRepository().fullName(), issueNumber, failure);
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
