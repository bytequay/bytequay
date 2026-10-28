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

import com.bytequay.app.config.WriteVersion;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubNotificationClient;
import com.bytequay.app.repository.github.GitHubNotificationRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestSummary;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.hosting.DesktopHostingService;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.GithubReviewState;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.DiscussionProbe;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);
    private static final int CLOSED_RETENTION_DAYS = 30;
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final Duration NOTIFICATION_SETTLE = Duration.ofMinutes(2);
    // Every table the attention list reads, so agent and other unrelated writes leave its version alone. A new read needs its table here.
    private static final List<String> ATTENTION_TABLES =
            ImmutableList.of("pull_requests", "pr_view_state", "watched_repo_workspaces", "pr_detail", "pr_timeline", "pr_reviews", "pr_check_runs");
    // Timeline events that stay in the pull request's history without bringing an acknowledged card back to Needs attention.
    private static final Set<String> QUIET_EVENTS = ImmutableSet.of(
            "committed", "head_ref_force_pushed", "labeled", "unlabeled", "review_dismissed", "convert_to_draft", "base_ref_changed", "base_ref_force_pushed",
            "automatic_base_change_succeeded", "automatic_base_change_failed", "auto_rebase_enabled", "head_ref_deleted", "head_ref_restored",
            "mentioned", "subscribed", "unsubscribed", "cross-referenced", "referenced");

    private final Map<PullRequestRef, SummaryStamp> summaryStamps = new ConcurrentHashMap<>();
    private String summaryCredential;
    private String lastDiscovery = "";
    private Optional<PullRequestRef> lastAttentionSummary = Optional.empty();
    private final ReentrantLock summaryLock = new ReentrantLock(true);
    private long summaryGeneration;
    private final Map<DetailKey, DetailWork> detailRefreshes = new ConcurrentHashMap<>();
    private volatile TimelineActivities timelineActivities;

    private final GitHubPullRequestReadClient gitHub;
    private final PullRequestActions actions;
    private final GitHubRepoClient repositories;
    private final GitHubAccountClient accounts;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PrDetailStore details;
    private final PullRequestDetailFetcher detailFetcher;
    private final PrViewStateStore viewStateStore;
    private final GitHubPullRequestSummaryClient summaries;
    private final WorkspaceStore workspaces;
    private final CredentialService credentialService;
    private final DesktopHostingService desktop;
    private final WorkspaceMetadataService metadata;
    private final ApplicationContext applicationContext;
    private final GitHubNotificationClient notifications;
    private final AppSettingsStore syncSettings;
    private final WorkspaceIssueService issues;
    private final WriteVersion writes;
    private final Clock clock;
    @Autowired
    public PullRequestService(
            GitHubPullRequestReadClient gitHub,
            GitHubPullRequestWriteClient pullRequestWrites,
            GitHubRepoClient repositories,
            GitHubAccountClient accounts,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PrDetailStore details,
            PullRequestDetailFetcher detailFetcher,
            PrViewStateStore viewStateStore,
            RecentTouchRecorder recent,
            CredentialService credentialService,
            DesktopHostingService desktop,
            WorkspaceMetadataService metadata,
            GitHubPullRequestSummaryClient summaries,
            WorkspaceStore workspaces,
            ApplicationContext applicationContext,
            GitHubNotificationClient notifications,
            AppSettingsStore syncSettings,
            WorkspaceIssueService issues,
            WriteVersion writes,
            Clock clock)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.details = requireNonNull(details, "details is null");
        this.detailFetcher = requireNonNull(detailFetcher, "detailFetcher is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.desktop = requireNonNull(desktop, "desktop is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.summaries = requireNonNull(summaries, "summaries is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.applicationContext = requireNonNull(applicationContext, "applicationContext is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.syncSettings = requireNonNull(syncSettings, "syncSettings is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.actions = new PullRequestActions(gitHub, pullRequestWrites, store, snapshots, details, viewStateStore, recent, credentialService, desktop, this::getPullRequestDetail);
    }

    public PullRequestPage listPullRequests(int page, int pageSize)
    {
        checkPage(page, pageSize);
        return store.findPage(page, pageSize);
    }

    /**
     * Changes whenever the attention list may change: after a stored write, or when the login, the inbox mentions, or the pull
     * requests awaiting a summary change in memory. Read it before the list.
     */
    public String attentionVersion()
    {
        Optional<String> login = credentialService.cachedLogin();
        Set<PullRequestRef> mentions = login.map(notifications::mentions).orElse(ImmutableSet.of());
        return writes.current(ATTENTION_TABLES) + "-" + Objects.hash(login, mentions, summaryStamps.keySet());
    }

    public List<PullRequest> listAttentionCandidates()
    {
        List<PullRequest> candidates = store.findAttentionCandidates();
        Set<Long> cleared = candidates.stream()
                .filter(row -> "open".equalsIgnoreCase(row.state()) && row.handledAction() == null && row.reviewedAt() != null)
                .map(PullRequest::id).collect(toImmutableSet());
        if (cleared.isEmpty()) {
            return candidates;
        }
        Map<Long, PrViewState> states = viewStateStore.findByPrIds(cleared);
        Optional<String> login = credentialService.cachedLogin();
        return candidates.stream().map(row -> cachedApproval(row, states.get(row.id()), login)).collect(toImmutableList());
    }

    /** Use saved approval evidence while background sync has not reconciled the cleared action. */
    private PullRequest cachedApproval(PullRequest row, PrViewState state, Optional<String> login)
    {
        if (state == null || state.handledAction() != null || state.reviewedHeadSha() == null || !Objects.equals(state.reviewedAt(), row.reviewedAt())
                || summaryStamps.containsKey(parseRef(row.repo(), row.number()))) {
            return row;
        }
        PrDetailFreshness freshness = details.findFreshness(row.id());
        if (freshness.discussionAt().isEmpty() || newer(discussionSourceUpdatedAt(row), freshness.discussionSourceUpdatedAt())) {
            return row;
        }
        Optional<StoredPrDetail> cached = details.find(row.id()).filter(detail -> detail.reviewsComplete() && detail.timelineComplete());
        if (cached.isEmpty()) {
            return row;
        }
        StoredPrDetail detail = cached.orElseThrow();
        List<PrReviewState> matching = detail.reviews().stream()
                .filter(review -> state.reviewedAt().equals(review.submittedAt()) && state.reviewedHeadSha().equals(review.commitId()))
                .filter(review -> login.map(viewer -> viewer.equalsIgnoreCase(review.login())).orElse(true)).collect(toImmutableList());
        if (matching.size() != 1 || !"APPROVED".equals(matching.getFirst().state())) {
            return row;
        }
        String reviewer = matching.getFirst().login();
        boolean laterReview = detail.reviews().stream().filter(review -> reviewer.equalsIgnoreCase(review.login()) && !"PENDING".equals(review.state()))
                .anyMatch(review -> review.submittedAt() == null || review.submittedAt().isAfter(state.reviewedAt())
                        || review.submittedAt().equals(state.reviewedAt()) && !"APPROVED".equals(review.state()));
        boolean requestedAgain = row.reviewRequestedAt() != null && row.reviewRequestedAt().isAfter(state.reviewedAt())
                && row.requestedReviewers().stream().anyMatch(reviewer::equalsIgnoreCase);
        PrViewState approved = new PrViewState(state.prId(), state.viewedAt(), state.reviewedAt(), HandledAction.APPROVED, state.reviewedHeadSha());
        if (laterReview || requestedAgain || needsReviewAgain(row, detail, reviewer, approved)) {
            return row;
        }
        // Project the evidence only: a concurrent refresh may already have invalidated the persisted approval.
        return new PullRequest(row.id(), row.repo(), row.number(), row.title(), row.author(), row.htmlUrl(), row.createdAt(), row.updatedAt(), row.origin(), row.labels(),
                row.labelColors(), row.draft(), row.viewedAt(), row.reviewedAt(), HandledAction.APPROVED, row.requestedReviewers(), row.ciStatus(),
                row.additions(), row.deletions(), row.commentCount(), row.state(), row.closedAt(), row.mergedAt(), row.mergeable(), row.mergeableState(),
                row.headPushedAt(), row.headRef(), row.monitoredAt(),
                row.activityAt(), row.reviewRequestedAt(), row.reviewDecision());
    }

    /** Reads the stored row without contacting GitHub or marking the pull request viewed. */
    public Optional<PullRequest> findStored(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number).flatMap(store::findById);
    }

    /** Resolves an exact search without fetching discussions or marking the pull request viewed. */
    public PullRequest lookupPullRequest(String repo, int number)
    {
        PullRequestRef ref = parseRef(repo, number);
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String scope = desktop.scope(credential.token());
        HostingPullRequestSnapshot metadata = desktop.cached(credential.token(), ref).orElseGet(() -> refreshMetadata(credential.token(), ref, scope, false));
        return withCurrentScope(scope, () -> store.saveObserved(metadata.pullRequest().withViewerOrigin(credential.profile().login())));
    }

    /** Reads the cached description the same way, so it is empty until a detail read has stored the pull request once. */
    public Optional<String> findStoredDescription(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> store.findIdByRepoAndNumber(repo, number).flatMap(details::find).map(detail -> Optional.ofNullable(detail.raw().body()).orElse("")));
    }

    /** Reads cached avatar URLs without loading full details or contacting GitHub. */
    public Map<Long, String> findCachedAuthorAvatarUrls(Set<Long> prIds)
    {
        return details.findAuthorAvatarUrls(prIds);
    }

    public Map<Long, Instant> findCachedAttentionActivities(List<PullRequest> pullRequests)
    {
        return store.findAttentionActivities(pullRequests.stream().map(PullRequest::id).collect(toImmutableSet()));
    }

    public Map<Long, PrActivitySummary> findCachedLatestActivities(List<PullRequest> pullRequests)
    {
        Set<Long> ids = pullRequests.stream().map(PullRequest::id).collect(toImmutableSet());
        Map<Long, PrActivitySummary> activities = new HashMap<>(timelineActivities(ids));
        store.findActivities(ids).forEach((id, activity) -> activities.merge(id, activity, (cached, card) -> cached.timestamp().isAfter(card.timestamp()) ? cached : card));
        ImmutableMap.Builder<Long, PrActivitySummary> latest = ImmutableMap.builder();
        for (PullRequest pullRequest : pullRequests) {
            // GitHub can update a PR without a timeline event; an older actor cannot explain that update.
            Optional.ofNullable(activities.get(pullRequest.id()))
                    .filter(activity -> !activity.timestamp().isBefore(pullRequest.updatedAt()))
                    .ifPresent(activity -> latest.put(pullRequest.id(), activity));
        }
        return latest.buildOrThrow();
    }

    /**
     * The latest timeline activity of each pull request, kept until a timeline is written: reading it parses every review thread,
     * most of an attention rebuild, while most rebuilds follow writes elsewhere.
     */
    private Map<Long, PrActivitySummary> timelineActivities(Set<Long> ids)
    {
        String version = writes.current("pr_timeline");
        TimelineActivities cached = timelineActivities;
        if (cached != null && cached.version().equals(version) && cached.ids().equals(ids)) {
            return cached.activities();
        }
        Map<Long, PrActivitySummary> activities = details.findLatestActivities(ids);
        timelineActivities = new TimelineActivities(version, ImmutableSet.copyOf(ids), activities);
        return activities;
    }

    private record TimelineActivities(String version, Set<Long> ids, Map<Long, PrActivitySummary> activities) {}

    /**
     * Reads the cached commits each PR received after the viewer's last review, or after the review
     * request when there was none, for the PRs whose head moved since; newest first.
     */
    public Map<Long, List<PrCommitLine>> findCachedCommitsSinceReview(List<PullRequest> pullRequests)
    {
        List<PullRequest> moved = pullRequests.stream().filter(pullRequest -> {
            Instant anchor = reviewAnchor(pullRequest);
            return anchor != null && pullRequest.headPushedAt() != null && pullRequest.headPushedAt().isAfter(anchor);
        }).collect(toImmutableList());
        Map<Long, PrDetailFreshness> freshness = freshness(moved);
        ImmutableMap.Builder<Long, Instant> since = ImmutableMap.builder();
        for (PullRequest pullRequest : moved) {
            if (pullRequest.activityAt() == null || activityIsCurrent(pullRequest, freshness.getOrDefault(pullRequest.id(), PrDetailFreshness.unknown()).discussionAt())) {
                since.put(pullRequest.id(), reviewAnchor(pullRequest));
            }
        }
        return details.findCommitsAfter(since.buildOrThrow());
    }

    private static Instant reviewAnchor(PullRequest pullRequest)
    {
        return pullRequest.reviewedAt() != null ? pullRequest.reviewedAt() : pullRequest.reviewRequestedAt();
    }

    /** One read for every pull request whose extras must be validated against its activity, instead of one per pull request. */
    private Map<Long, PrDetailFreshness> freshness(List<PullRequest> pullRequests)
    {
        Set<Long> ids = pullRequests.stream().filter(pullRequest -> pullRequest.activityAt() != null).map(PullRequest::id).collect(toImmutableSet());
        return ids.isEmpty() ? ImmutableMap.of() : details.findFreshness(ids);
    }

    /** Reads the last polled GitHub inbox, never GitHub itself. */
    public Set<Long> findMentioned(List<PullRequest> pullRequests)
    {
        Set<PullRequestRef> mentions = credentialService.cachedLogin().map(notifications::mentions).orElse(ImmutableSet.of());
        return pullRequests.stream().filter(pullRequest -> mentions.contains(parseRef(pullRequest.repo(), pullRequest.number())))
                .map(PullRequest::id).collect(toImmutableSet());
    }

    /** Reads the names of the cached checks failing on each PR whose CI is failing. */
    public Map<Long, List<String>> findCachedFailingChecks(List<PullRequest> pullRequests)
    {
        List<PullRequest> red = pullRequests.stream().filter(pullRequest -> pullRequest.ciStatus() == PullRequestDetail.CiStatus.FAILING).collect(toImmutableList());
        Map<Long, PrDetailFreshness> freshness = freshness(red);
        Set<Long> failing = red.stream()
                .filter(pullRequest -> pullRequest.activityAt() == null
                        || activityIsCurrent(pullRequest, freshness.getOrDefault(pullRequest.id(), PrDetailFreshness.unknown()).checksAt()))
                .map(PullRequest::id)
                .collect(toImmutableSet());
        return details.findCheckRuns(failing).entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> PullRequestDetailMapper.failingCheckNames(entry.getValue())));
    }

    private static boolean activityIsCurrent(PullRequest pullRequest, Optional<Instant> checkedAt)
    {
        // Each extra comes from its own resource group; metadata alone cannot validate it.
        return pullRequest.activityAt() == null || checkedAt.filter(at -> !at.isBefore(pullRequest.activityAt())).isPresent();
    }

    public PullRequest setMonitored(long prId, boolean monitored)
    {
        PullRequest row = store.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request not found"));
        if (monitored && "merged".equalsIgnoreCase(row.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Merged pull requests cannot be monitored");
        }
        if (monitored && row.monitoredAt() == null) {
            GitHubCredential credential = credentialService.resolveGitHubCredential();
            GitHubPullRequestSummary summary = summaries.fetchSummaries(
                            credential.token(), ImmutableList.of(parseRef(row.repo(), row.number())), credential.profile().login()).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not load pull request activity. Retry monitoring shortly."));
            // Establish the same lightweight baseline used by polling before enabling notifications.
            storeCardSummary(credential.token(), store.saveObserved(summary.pullRequest()), summary, credential.profile().login());
            if ("merged".equalsIgnoreCase(summary.pullRequest().state()) || summary.pullRequest().mergedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Merged pull requests cannot be monitored");
            }
        }
        store.setMonitored(prId, monitored);
        return store.findById(prId).orElseThrow();
    }

    /** Returns the existing local PR rows restricted to one repository. */
    public PullRequestPage listPullRequests(RepoRef repository, int page, int pageSize, String search)
    {
        checkPage(page, pageSize);
        return store.findRepositoryPage(requireNonNull(repository, "repository is null").fullName(), page, pageSize, search);
    }

    /** Merged pull requests already stored for one repository; repository sync keeps roughly the last month of them. */
    public List<PullRequest> listMergedPullRequests(RepoRef repository, String search, int limit)
    {
        return store.findMergedRepositoryPullRequests(requireNonNull(repository, "repository is null").fullName(), search, limit);
    }

    /** Exact-fetches a newly created pull request so its workspace projection does not wait for GitHub search. */
    public void observeWorkspacePullRequest(RepoRef repository, int number)
    {
        requireNonNull(repository, "repository is null");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        PullRequestRef ref = PullRequestRef.of(repository.owner(), repository.repo(), number);
        String scope = desktop.scope(credential.token());
        FetchedPullRequest pullRequest = refreshMetadata(credential.token(), ref, scope, false).pullRequest()
                .withViewerOrigin(credential.profile().login());
        withCurrentScope(scope, () -> store.saveRepositorySyncPage(repository.fullName(), ImmutableList.of(pullRequest)));
    }

    /**
     * Discovers repository updates independently of the bounded full-detail warm set. After one complete pass, later passes
     * read only what changed since: rereading every open and recent pull request was most of the scheduled refresh's search
     * time, and the attention job already reads each watched repository's updates every few minutes.
     */
    public void syncRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        baselineSummaries(credential.token());
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        Consumer<List<FetchedPullRequest>> save = page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(credential.profile().login())).collect(toImmutableList());
            store.saveRepositorySyncPage(repository.fullName(), rows);
            rows.forEach(row -> seen.put(row.repo() + "#" + row.number(), row));
        };
        String query = "is:pr repo:" + repository.fullName();
        String key = "github.repository-sync." + credential.profile().login().toLowerCase(Locale.ROOT) + "." + repository.fullName().toLowerCase(Locale.ROOT);
        Instant started = clock.instant();
        // Deleting the last workspace of a repository hides its rows, so watching it again needs the full pass.
        Optional<Instant> synced = syncSettings.get(key).map(Instant::parse)
                .filter(at -> at.isAfter(started.minus(Duration.ofDays(CLOSED_RETENTION_DAYS))) && !at.isAfter(started))
                .filter(ignored -> store.findRepositoryPage(repository.fullName(), 0, 1).totalPullRequests() > 0);
        if (synced.isPresent()) {
            discoverQuery(credential.token(), query + " updated:>=" + synced.orElseThrow().minusSeconds(300).truncatedTo(ChronoUnit.SECONDS), save);
        }
        else {
            // A full pass that fails part way must not leave an older checkpoint behind its partly saved rows.
            syncSettings.delete(key);
            String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
            discoverQuery(credential.token(), query + " updated:>=" + sinceDate, save);
            discoverQuery(credential.token(), query + " is:open", save);
        }
        syncSettings.put(key, started.toString());
        enrichCards(credential, seen.values().stream().map(row -> parseRef(row.repo(), row.number())).collect(toImmutableList()));
        // Search can omit cached rows; discovery never deletes their data or local marks.
    }

    /** Discovers one task branch without a repository-wide search or detail refresh. */
    public void syncRepositoryBranch(RepoRef repository, String headOwner, String branch)
    {
        requireNonNull(repository, "repository is null");
        checkText(headOwner, "headOwner");
        checkText(branch, "branch");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        List<FetchedPullRequest> fresh = gitHub.fetchPullRequestsByHead(credential.token(), repository, headOwner + ":" + branch).stream()
                .map(pullRequest -> pullRequest.withViewerOrigin(credential.profile().login()))
                .collect(toImmutableList());
        if (!fresh.isEmpty()) {
            store.saveRepositorySyncPage(repository.fullName(), fresh);
        }
    }

    public List<RepoUser> listRepositoryUsers(String repo, String workspaceId)
    {
        PullRequestRef ref = parseRef(repo, 1);
        RepoRef repository = RepoRef.of(ref.owner(), ref.repo());
        Optional<RepositoryMetadataSnapshot> localSnapshot = workspaceId != null && !workspaceId.isBlank()
                ? Optional.of(metadata.getSnapshot(workspaceId))
                : metadata.findSnapshot(repository);
        if (localSnapshot.isPresent()) {
            RepositoryMetadataSnapshot snapshot = localSnapshot.orElseThrow();
            String avatarBaseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) applicationContext).getWebServer().getPort() + "/api/avatars/";
            checkArgument(
                    snapshot.repository().owner().equalsIgnoreCase(ref.owner())
                            && snapshot.repository().repo().equalsIgnoreCase(ref.repo()),
                    "workspace repository does not match requested repository");
            return snapshot.pickerUsers().stream()
                    .map(user -> new RepoUser(user.login(), user.avatarKey().map(avatarBaseUrl::concat).orElse(null), user.bot()))
                    .collect(toImmutableList());
        }
        String pat = credentialService.resolveGitHubToken();
        return gitHub.fetchAssignableUsers(pat, repository);
    }

    public List<String> listRepositoryTeams(String repo)
    {
        return gitHub.fetchOrganizationTeams(credentialService.resolveGitHubToken(), parseRef(repo, 1).owner());
    }

    public List<RepositoryLabel> listRepositoryLabels(String repo)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return repositories.fetchLabels(pat, RepoRef.of(ref.owner(), ref.repo()));
    }

    public boolean hasMergeQueue(String repo, String baseRef)
    {
        return actions.hasMergeQueue(repo, baseRef);
    }

    public PullRequestCheckLog readCheckLog(String repo, int number, long jobId)
    {
        checkArgument(jobId > 0, "jobId must be positive");
        return snapshots.readCheckLog(parseRef(repo, number), jobId);
    }

    public void rerunFailedChecks(String repo, int number)
    {
        snapshots.rerunFailedChecks(parseRef(repo, number));
    }

    /** Notification ingestion and incremental discovery never wait for full detail warming. */
    public synchronized void syncAttentionSummaries()
    {
        // One load a tick: rows that discovery or the inbox touch during the tick join the changed set on their own.
        List<PullRequest> candidates = store.findAttentionCandidates();
        Set<Long> unmonitored = candidates.stream()
                .filter(row -> row.monitoredAt() != null && "merged".equalsIgnoreCase(row.state()))
                .map(PullRequest::id)
                .collect(toImmutableSet());
        unmonitored.forEach(id -> store.setMonitored(id, false));
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        String login = credential.profile().login();
        baselineSummaries(pat);
        Set<PullRequestRef> changed = new LinkedHashSet<>();
        try {
            notifications.poll(pat, login, notification -> ingestNotification(credential, notification, changed));
        }
        catch (RuntimeException failure) {
            log.debug("GitHub notification sync deferred: {}", failure.getMessage());
        }
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        Map<String, PullRequest.Origin> queries = new LinkedHashMap<>();
        queries.put("is:pr author:@me", AUTHORED);
        queries.put("is:pr user-review-requested:@me", REVIEW_REQUESTED);
        queries.put("is:pr reviewed-by:@me", OTHER);
        queries.put("is:pr involves:@me", OTHER);
        workspaces.findAll().stream().map(workspace -> workspace.monitoredRepository().fullName()).distinct()
                .forEach(repo -> queries.put("is:pr repo:" + repo, OTHER));
        // One search per tick, in turn: back to back, a tick's searches land as one burst on GitHub's secondary limit.
        // Each query keeps its own checkpoint, so a query that waits for its turn still reads everything since its last run.
        List<String> order = ImmutableList.copyOf(queries.keySet());
        int next = order.indexOf(lastDiscovery) + 1;
        for (int offset = 0; offset < order.size(); offset++) {
            String query = order.get((next + offset) % order.size());
            // A failed query uses its turn too, so it cannot starve the queries after it.
            boolean ran = true;
            try {
                ran = discoverRecent(credential, query, queries.get(query), seen);
            }
            catch (RuntimeException failure) {
                if (!isRateLimit(failure)) {
                    log.info("Recent PR discovery deferred for {}: {}", query, failure.getMessage(), failure);
                }
            }
            if (ran) {
                lastDiscovery = query;
                break;
            }
        }
        seen.values().stream().map(row -> parseRef(row.repo(), row.number())).forEach(changed::add);
        Map<Long, Instant> discussed = store.findDiscussionActivities(candidates.stream().map(PullRequest::id).collect(toImmutableSet()));
        candidates.stream()
                .filter(row -> {
                    SummaryStamp stamp = summaryStamps.get(parseRef(row.repo(), row.number()));
                    boolean monitored = row.monitoredAt() != null && !unmonitored.contains(row.id());
                    return stamp == null || !stamp.credential().equals(pat) || monitored || row.ciStatus() == PullRequestDetail.CiStatus.PENDING
                            || discussionSource(row, discussed).isAfter(stamp.sourceAt());
                })
                .map(row -> parseRef(row.repo(), row.number())).forEach(changed::add);
        enrichAttentionCards(credential, ImmutableList.copyOf(changed));
    }

    /** Returns whether the query was due; one that completed within two minutes is skipped. */
    private boolean discoverRecent(GitHubCredential credential, String query, PullRequest.Origin origin, Map<String, FetchedPullRequest> seen)
    {
        String key = "github.discovery." + credential.profile().login().toLowerCase(Locale.ROOT) + "." + Hashing.sha256().hashString(query, StandardCharsets.UTF_8);
        Instant started = clock.instant();
        Optional<Instant> completed = syncSettings.get(key).map(Instant::parse).filter(at -> !at.isAfter(started));
        if (completed.filter(at -> started.isBefore(at.plusSeconds(120))).isPresent()) {
            return false;
        }
        Instant since = completed.orElse(started.minus(Duration.ofHours(1))).minusSeconds(300);
        discoverQuery(credential.token(), query + " updated:>=" + since.truncatedTo(ChronoUnit.SECONDS), page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(credential.profile().login()))
                    .map(row -> origin == REVIEW_REQUESTED && row.origin() != AUTHORED ? row.withOrigin(REVIEW_REQUESTED) : row)
                    .collect(toImmutableList());
            if (query.startsWith("is:pr repo:")) {
                store.saveRepositorySyncPage(query.substring("is:pr repo:".length()), rows);
                rows.forEach(row -> seen.putIfAbsent(row.repo() + "#" + row.number(), row));
            }
            else {
                rows.forEach(row -> {
                    if (seen.putIfAbsent(row.repo() + "#" + row.number(), row) == null) {
                        store.saveObserved(row);
                    }
                });
            }
        });
        // A failed page leaves this query's checkpoint unchanged; already saved pages replay safely.
        syncSettings.put(key, started.toString());
        return true;
    }

    private void ingestNotification(GitHubCredential credential, GitHubNotificationRef notification, Set<PullRequestRef> changed)
    {
        PullRequestRef ref = notification.ref();
        if (!notification.pullRequest()) {
            issues.refreshNotifiedIssue(RepoRef.of(ref.owner(), ref.repo()), ref.number());
            return;
        }
        if (notification.repositoryWatch()) {
            // Watching someone's repository on GitHub does not make its pull requests the viewer's work.
            return;
        }
        String scope = desktop.scope(credential.token());
        FetchedPullRequest fetched = refreshMetadata(credential.token(), ref, scope, false).pullRequest().withViewerOrigin(credential.profile().login());
        FetchedPullRequest observed = notification.reviewRequested() && fetched.origin() != AUTHORED ? fetched.withOrigin(REVIEW_REQUESTED) : fetched;
        PullRequest row = withCurrentScope(scope, () -> store.saveObserved(observed));
        // Save the dirty marker before enrichment: a comment request failure cannot hide the notification.
        if (!"ci_activity".equals(notification.reason())) {
            withCurrentScope(scope, () -> {
                store.observeActivity(row.id(), Optional.empty(), notification.updatedAt());
                return null;
            });
        }
        synchronized (summaryStamps) {
            summaryStamps.remove(ref);
            summaryGeneration++;
        }
        changed.add(ref);
        try {
            notifications.activity(credential.token(), notification)
                    .ifPresent(activity -> withCurrentScope(scope, () -> {
                        store.observeActivity(row.id(), Optional.of(activity), notification.updatedAt());
                        return null;
                    }));
        }
        catch (RuntimeException failure) {
            log.debug("Notification comment preview unavailable for {}: {}", ref, failure.getMessage());
        }
    }

    private void syncAttentionQuery(String pat, Map<String, FetchedPullRequest> seen, String query, PullRequest.Origin origin)
    {
        discoverQuery(pat, query, page -> saveAttentionRows(page, seen, origin));
    }

    private void saveAttentionRows(List<FetchedPullRequest> rows, Map<String, FetchedPullRequest> seen, PullRequest.Origin origin)
    {
        rows.forEach(fetched -> {
            FetchedPullRequest pullRequest = fetched.withOrigin(origin);
            if (seen.putIfAbsent(pullRequest.repo() + "#" + pullRequest.number(), pullRequest) == null) {
                store.saveObserved(pullRequest);
            }
        });
    }

    private void discoverQuery(String pat, String query, Consumer<List<FetchedPullRequest>> save)
    {
        for (int page = 1; page <= 10; page++) {
            PullRequestHistoryPage result = gitHub.searchPullRequestsPaged(pat, query, page, SEARCH_PAGE_SIZE);
            save.accept(result.items());
            if (!result.hasMore()) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pull request discovery exceeded the search result limit");
    }

    /** Discovers all personal rows, then warms only the newest full snapshots per repository. */
    public void syncFromGitHub()
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        baselineSummaries(pat);
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        syncAttentionQuery(pat, seen, "is:pr is:open author:@me", AUTHORED);
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        syncAttentionQuery(pat, seen, "is:pr is:closed author:@me updated:>=" + sinceDate, AUTHORED);
        syncAttentionQuery(pat, seen, "is:pr is:open user-review-requested:@me", REVIEW_REQUESTED);
        syncAttentionQuery(pat, seen, "is:pr is:open reviewed-by:@me", OTHER);
        syncAttentionQuery(pat, seen, "is:pr involves:@me updated:>=" + sinceDate, OTHER);
        List<FetchedPullRequest> fresh = ImmutableList.copyOf(seen.values());
        store.saveDashboardSyncPage(fresh);
        enrichCards(credential, fresh.stream().map(row -> parseRef(row.repo(), row.number())).collect(toImmutableList()));
    }

    /**
     * A restart forgets every stamp. Cards summarized again only when activity is reported, closed ones and other people's open
     * ones, then keep what is stored, because discovery and notifications, whose checkpoints survive a restart, report whatever
     * changes them later; summarizing a thousand of them again is twenty minutes of back-to-back GraphQL, and each saved summary
     * rebuilds the attention list. Another account still summarizes everything.
     */
    private void baselineSummaries(String pat)
    {
        // Not the service monitor: a scan must not wait here for a whole pass of the summary stream.
        synchronized (summaryStamps) {
            if (pat.equals(summaryCredential)) {
                return;
            }
            summaryStamps.clear();
            summaryGeneration++;
            if (summaryCredential == null) {
                // ponytail: a change saved but not summarized before the last shutdown stays stale until the next one; persist the stamp if that shows.
                Instant now = clock.instant();
                List<PullRequest> rows = store.findAttentionCandidates().stream().filter(row -> !timed(row)).collect(toImmutableList());
                Map<Long, Instant> discussed = store.findDiscussionActivities(rows.stream().map(PullRequest::id).collect(toImmutableSet()));
                rows.forEach(row -> summaryStamps.put(parseRef(row.repo(), row.number()), new SummaryStamp(pat, discussionSource(row, discussed), now)));
            }
            summaryCredential = pat;
        }
    }

    /** Checks and mergeability move without new activity only while open; a monitored pull request has no other discovery. */
    private static boolean settled(PullRequest row)
    {
        return row.monitoredAt() == null && row.state() != null && !"open".equalsIgnoreCase(row.state());
    }

    /**
     * Rechecking every open card in the watched repositories keeps GitHub's secondary limit nearly full. Someone else's
     * pull request is summarized or warmed again only when discovery reports activity, or while its checks are still running.
     */
    private static boolean timed(PullRequest row)
    {
        return !settled(row) && (row.origin() != OTHER || row.monitoredAt() != null || row.ciStatus() == PullRequestDetail.CiStatus.PENDING);
    }

    private void enrichCards(GitHubCredential credential, List<PullRequestRef> candidates)
    {
        List<PullRequestRef> refs = dueSummaries(credential, candidates);
        for (int offset = 0; offset < refs.size(); offset += GitHubPullRequestSummaryClient.BATCH_SIZE) {
            summaryLock.lock();
            try {
                List<PullRequestRef> batch = dueSummaries(credential, refs.subList(offset, Math.min(offset + GitHubPullRequestSummaryClient.BATCH_SIZE, refs.size())));
                enrichCardBatch(credential, batch);
            }
            finally {
                summaryLock.unlock();
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    /** The inbox must keep polling while a repository scan owns the summary stream; each tick attempts only one batch. */
    private void enrichAttentionCards(GitHubCredential credential, List<PullRequestRef> candidates)
    {
        if (!summaryLock.tryLock()) {
            return;
        }
        try {
            List<PullRequestRef> refs = dueSummaries(credential, candidates);
            if (refs.isEmpty()) {
                return;
            }
            int start = refs.size() <= GitHubPullRequestSummaryClient.BATCH_SIZE ? 0 : lastAttentionSummary.map(refs::indexOf).orElse(-1) + 1;
            List<PullRequestRef> batch = new ArrayList<>();
            for (int index = 0; index < Math.min(refs.size(), GitHubPullRequestSummaryClient.BATCH_SIZE); index++) {
                batch.add(refs.get((start + index) % refs.size()));
            }
            // Failed and omitted summaries use their turn too, so later cards cannot starve.
            lastAttentionSummary = Optional.of(batch.getLast());
            enrichCardBatch(credential, batch);
        }
        finally {
            summaryLock.unlock();
        }
    }

    private List<PullRequestRef> dueSummaries(GitHubCredential credential, List<PullRequestRef> candidates)
    {
        Instant now = clock.instant();
        return candidates.stream().distinct().filter(ref -> {
            Optional<PullRequest> row = store.findIdByRepoAndNumber(ref.repoFullName(), ref.number()).flatMap(store::findById);
            SummaryStamp stamp = summaryStamps.get(ref);
            if (stamp == null || !stamp.credential().equals(credential.token()) || row.isEmpty()) {
                return true;
            }
            PullRequest cached = row.orElseThrow();
            Instant source = store.findDiscussionActivityAt(cached.id()).filter(at -> at.isAfter(cached.updatedAt())).orElse(cached.updatedAt());
            Duration interval = cached.ciStatus() == PullRequestDetail.CiStatus.PENDING ? Duration.ofMinutes(1) : Duration.ofMinutes(10);
            // A notification's time trails the activity it reports, so GitHub's timeline can stay behind that marker for good;
            // a summary read a while after the notification already holds the activity.
            boolean behind = source.isAfter(stamp.sourceAt()) && stamp.checkedAt().isBefore(source.plus(NOTIFICATION_SETTLE));
            return behind || (timed(cached) && !now.isBefore(stamp.checkedAt().plus(interval)));
        }).collect(toImmutableList());
    }

    /** One batch owns GraphQL and its pacing delay, but never the inbox monitor. */
    private void enrichCardBatch(GitHubCredential credential, List<PullRequestRef> batch)
    {
        if (batch.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        long generation;
        synchronized (summaryStamps) {
            generation = summaryGeneration;
        }
        Set<PullRequestRef> reviewRequests = notifications.reviewRequests(credential.profile().login());
        long started = System.nanoTime();
        try {
            // Persist each completed batch before asking for the next one.
            for (GitHubPullRequestSummary summary : summaries.fetchSummaries(credential.token(), batch, credential.profile().login())) {
                synchronized (summaryStamps) {
                    if (generation != summaryGeneration) {
                        break;
                    }
                }
                FetchedPullRequest fetched = summary.pullRequest();
                PullRequestRef ref = parseRef(fetched.repo(), fetched.number());
                if (fetched.origin() != AUTHORED && reviewRequests.contains(ref)) {
                    fetched = fetched.withOrigin(REVIEW_REQUESTED);
                }
                PullRequest row = store.saveObserved(fetched);
                storeCardSummary(credential.token(), row, summary, credential.profile().login());
                if (row.updatedAt().isAfter(summary.pullRequest().updatedAt())) {
                    continue;
                }
                Instant source = summary.discussionUpdatedAt().isAfter(summary.pullRequest().updatedAt()) ? summary.discussionUpdatedAt() : summary.pullRequest().updatedAt();
                synchronized (summaryStamps) {
                    // An inbox update or credential switch during this request must remain eligible for a newer read.
                    if (generation == summaryGeneration) {
                        summaryStamps.put(ref, new SummaryStamp(credential.token(), source, now));
                    }
                }
            }
        }
        catch (RuntimeException failure) {
            if (isRateLimit(failure) || failure instanceof HostingFailure) {
                throw failure;
            }
            if (failure instanceof ResponseStatusException response && (response.getStatusCode().value() == 401
                    || response.getStatusCode().value() == 403 || response.getStatusCode().value() == 429)) {
                throw failure;
            }
            log.warn("GitHub PR card enrichment failed for {} PRs; keeping discovered updates: {}", batch.size(), failure.getMessage(), failure);
        }
        try {
            // GitHub's secondary limit allows about a minute of GraphQL execution per minute; resting as long as each batch ran leaves half of it free.
            Thread.sleep(Duration.ofNanos(System.nanoTime() - started));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record SummaryStamp(String credential, Instant sourceAt, Instant checkedAt) {}

    /** A separate slow pass warms the newest configured number of PRs in each repository. */
    public void warmRecentDetails(int limit)
    {
        Map<String, Integer> counts = new HashMap<>();
        checkArgument(limit == 10 || limit == 30, "warm limit must be 10 or 30");
        store.findAttentionCandidates().stream()
                .sorted(Comparator.comparing(PullRequest::updatedAt).reversed().thenComparing(Comparator.comparingInt(PullRequest::number).reversed()))
                .filter(row -> counts.merge(row.repo().toLowerCase(Locale.ROOT), 1, Integer::sum) <= limit)
                .forEach(row -> warmDetail(row.repo(), row.number(), Duration.ofMinutes(10)));
    }

    /** Refreshes one pull request's cached detail when it is older than {@code freshness}; a failure keeps the cached copy. */
    public void warmDetail(String repo, int number, Duration freshness)
    {
        try {
            refreshPullRequestDetailFromGitHub(repo, number, freshness);
        }
        catch (RuntimeException failure) {
            if (isRateLimit(failure) || failure instanceof HostingFailure) {
                throw failure;
            }
            log.warn("Could not warm PR {}#{}; retaining cached detail: {}", repo, number, failure.getMessage());
        }
    }

    private void storeCardSummary(String pat, PullRequest row, GitHubPullRequestSummary summary, String login)
    {
        if (row.updatedAt().isAfter(summary.pullRequest().updatedAt())) {
            return;
        }
        store.observeActivity(row.id(), summary.latestActivity(), summary.discussionUpdatedAt());
        PrViewState state = viewStateStore.findByPrIds(ImmutableSet.of(row.id())).get(row.id());
        List<PrReviewState> reviews = summary.viewerReview().map(ImmutableList::of).orElse(ImmutableList.of());
        reconcileHandledActionFromReviews(row.id(), reviews, login, state);
        state = viewStateStore.findByPrIds(ImmutableSet.of(row.id())).get(row.id());
        Instant pushedAt = summary.headCommittedAt().orElse(row.headPushedAt());
        if (row.headPushedAt() != null && (pushedAt == null || row.headPushedAt().isAfter(pushedAt))) {
            pushedAt = row.headPushedAt();
        }
        if (state != null && state.handledAction() != null && state.reviewedAt() != null) {
            boolean newHead = state.reviewedHeadSha() == null ? pushedAt != null && pushedAt.isAfter(state.reviewedAt()) : !state.reviewedHeadSha().equals(summary.headSha());
            boolean requestedAgain = summary.reviewRequestedAt().isPresent() && summary.reviewRequestedAt().orElseThrow().isAfter(state.reviewedAt());
            boolean dismissed = summary.viewerReview().filter(review -> "DISMISSED".equals(review.state())).isPresent();
            if (requestedAgain || dismissed || state.handledAction() != HandledAction.APPROVED
                    && (newHead || hasUnseenAuthorReply(pat, row, summary, login, state.reviewedAt()))) {
                viewStateStore.markNeedsReview(row.id());
            }
            if (newHead && (pushedAt == null || !pushedAt.isAfter(state.reviewedAt()))) {
                // A force-push can introduce a commit older than the review; use when the changed head was observed.
                pushedAt = row.headPushedAt() != null && row.headPushedAt().isAfter(state.reviewedAt()) ? row.headPushedAt() : Instant.now();
            }
        }
        store.updateEnrichment(
                row.id(), summary.pullRequest().updatedAt(), summary.ciStatus(), summary.additions(), summary.deletions(), summary.commentCount(),
                summary.pullRequest().mergeable(), summary.pullRequest().mergeableState(), pushedAt, summary.pullRequest().headRef(),
                summary.pullRequest().requestedReviewers(), row.origin(), summary.reviewRequestedAt(), summary.reviewDecision(),
                ImmutableMap.of("summary", summary.activityVersion()));
    }

    private boolean hasUnseenAuthorReply(String pat, PullRequest row, GitHubPullRequestSummary summary, String login, Instant reviewedAt)
    {
        if (row.author().equalsIgnoreCase(login) || !summary.pullRequest().updatedAt().isAfter(reviewedAt)) {
            return false;
        }
        // A failed reply check must keep the summary eligible for retry, not clear a handled card.
        return summaries.hasAuthorReplyAfter(pat, parseRef(row.repo(), row.number()), login, row.author(), reviewedAt, summary.activityVersion());
    }

    /** Returns detail for a pull request the user opened. Background readers use {@link #getPullRequestDetail} instead. */
    public PullRequestDetail openPullRequestDetail(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        PullRequestDetail detail = getPullRequestDetail(repo, number);
        // Opening the pull request dates the "since you last looked"
        // comparison. It is deliberately unconditional and separate from the
        // Recent stream, which needs a stronger signal than a triage click.
        return withCurrentScope(scope, () -> {
            markViewed(detail.id());
            return detail;
        });
    }

    /** Returns persisted detail, fetching and caching it on a local miss. */
    public PullRequestDetail getPullRequestDetail(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        PullRequestDetail detail = findPullRequestDetail(repo, number)
                .orElseGet(() -> refreshPullRequestDetailFromGitHub(repo, number).detail());
        return withCurrentScope(scope, () -> detail);
    }

    /** Prepares remote projections for the currently selected desktop credential without changing local request identity. */
    public String prepareRead(String repo, int number)
    {
        String scope = desktop.scope(credentialService.resolveGitHubToken());
        prepareRead(parseRef(repo, number), scope);
        return scope;
    }

    private void prepareRead(PullRequestRef ref, String scope)
    {
        withCurrentScope(scope, () -> {
            store.findIdByRepoAndNumber(ref.repoFullName(), ref.number()).ifPresent(id -> snapshots.prepareScope(id, scope));
            return null;
        });
    }

    public <T> T withCurrentScope(String scope, Supplier<T> action)
    {
        return desktop.withCurrent(scope, action);
    }

    private HostingPullRequestSnapshot refreshMetadata(String pat, PullRequestRef ref, String scope, boolean force)
    {
        try {
            return desktop.refresh(pat, ref, force);
        }
        catch (HostingFailure failure) {
            if (PullRequestSnapshotService.inaccessible(failure)) {
                withCurrentScope(scope, () -> {
                    store.findIdByRepoAndNumber(ref.repoFullName(), ref.number()).ifPresent(snapshots::clearRemote);
                    return null;
                });
            }
            throw failure;
        }
    }

    private void markViewed(long prId)
    {
        try {
            viewStateStore.markViewed(prId);
        }
        catch (RuntimeException failure) {
            log.debug("Could not stamp viewed_at for {}: {}", prId, failure.toString());
        }
    }

    DetailRefresh refreshPullRequestDetailFromGitHub(String repo, int number)
    {
        return refreshDetailOnce(parseRef(repo, number), RefreshPolicy.detail());
    }

    /** The background warm refresh, which nobody is waiting for. */
    DetailRefresh refreshPullRequestDetailFromGitHub(String repo, int number, Duration freshness)
    {
        return refreshDetailOnce(parseRef(repo, number), RefreshPolicy.background(freshness));
    }

    DetailRefresh refreshOpenedPullRequestDetailFromGitHub(String repo, int number)
    {
        return refreshDetailOnce(parseRef(repo, number), RefreshPolicy.opened());
    }

    DetailRefresh refreshPullRequestChecksFromGitHub(String repo, int number)
    {
        return refreshDetailOnce(parseRef(repo, number), RefreshPolicy.checks());
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(String repo, int number)
    {
        return refreshDetailOnce(parseRef(repo, number), RefreshPolicy.forced()).detail();
    }

    private DetailRefresh refreshDetailOnce(PullRequestRef ref, RefreshPolicy policy)
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String scope = desktop.scope(credential.token());
        DetailKey key = new DetailKey(parseRef(ref.repoFullName().toLowerCase(Locale.ROOT), ref.number()), scope);
        DetailWork work = new DetailWork(policy, new CompletableFuture<>());
        DetailWork running = detailRefreshes.putIfAbsent(key, work);
        if (running != null) {
            DetailRefresh result;
            try {
                result = running.result().join();
            }
            catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw failure;
            }
            if (!policy.canReuse(running.policy())) {
                detailRefreshes.remove(key, running);
                return refreshDetailOnce(ref, policy);
            }
            return withCurrentScope(scope, () -> result);
        }
        try {
            DetailRefresh result = refreshDetailGroups(ref, policy, credential, scope);
            return withCurrentScope(scope, () -> {
                work.result().complete(result);
                return result;
            });
        }
        catch (RuntimeException | Error failure) {
            work.result().completeExceptionally(failure);
            throw failure;
        }
        finally {
            detailRefreshes.remove(key, work);
        }
    }

    private DetailRefresh refreshDetailGroups(PullRequestRef ref, RefreshPolicy policy, GitHubCredential credential, String scope)
    {
        prepareRead(ref, scope);
        String pat = credential.token();
        String login = credential.profile().login();
        Optional<PullRequest> previousRow = store.findIdByRepoAndNumber(ref.repoFullName(), ref.number()).flatMap(store::findById);
        Optional<StoredPrDetail> previous = previousRow.flatMap(row -> details.find(row.id()));
        boolean missing = previous.isEmpty();
        PrDetailFreshness times = previousRow.map(row -> snapshots.findFreshness(row.id())).orElseGet(PrDetailFreshness::unknown);
        Instant now = clock.instant();
        boolean metadataLag = previousRow.isPresent() && previous.isPresent()
                && newer(previousRow.orElseThrow().updatedAt(), Optional.ofNullable(previous.orElseThrow().raw().updatedAt()));
        boolean missingCoordinates = previous.filter(detail -> detail.raw().headSha() == null || detail.raw().baseSha() == null).isPresent();
        boolean missingHead = previous.filter(detail -> detail.raw().headSha() == null || detail.raw().headSha().isBlank()).isPresent();
        // A warm pass rechecks on a timer only what timed summaries follow; any other pull request waits for activity. The reactions
        // and thread resolutions GitHub does not report as activity wait until someone opens it.
        boolean timed = !policy.warm() || previousRow.filter(PullRequestService::timed).isPresent();
        Optional<HostingPullRequestSnapshot> cachedMetadata = desktop.cached(pat, ref);
        boolean metadataDue = missing || policy.force() || metadataLag || missingHead || cachedMetadata.isEmpty()
                || (!policy.checksOnly() && (missingCoordinates || (timed && expired(times.metadataAt(), policy.freshness(), now))
                || cachedMetadata.flatMap(HostingPullRequestSnapshot::etag).isEmpty()));
        PullRequest row = previousRow.orElse(null);
        StoredPrDetail detail = previous.orElse(null);
        boolean full = missing;
        if (metadataDue) {
            HostingPullRequestSnapshot fetched = refreshMetadata(pat, ref, scope, policy.force() || missingCoordinates || missingHead);
            FetchedPullRequest observed = fetched.pullRequest().withViewerOrigin(login);
            if (observed.origin() != AUTHORED && notifications.reviewRequests(login).contains(ref)) {
                observed = observed.withOrigin(REVIEW_REQUESTED);
            }
            FetchedPullRequest accepted = observed;
            row = withCurrentScope(scope, () -> store.saveObserved(accepted));
            PrRawDetail raw = fetched.detail();
            full |= previous.filter(value -> !value.raw().equals(raw)).isPresent();
            boolean viewerCanWrite = accounts.fetchViewerCanWrite(pat, RepoRef.of(ref.owner(), ref.repo()));
            detail = snapshots.saveMetadata(row.id(), raw, viewerCanWrite, scope);
        }
        PullRequest current = requireNonNull(row, "pull request is missing");
        Instant sourceAt = discussionSourceUpdatedAt(current);
        boolean headChanged = previous.isPresent() && !Objects.equals(previous.orElseThrow().raw().headSha(), detail.raw().headSha());
        // Opening uses a short freshness window; periodic refreshes reconcile quiet discussions less often
        // because every pass paginates the full history, including several GraphQL reaction/thread queries.
        boolean quiet = !missing && !policy.force() && !headChanged && detail.reviewsComplete() && detail.timelineComplete() && !newer(sourceAt, times.discussionSourceUpdatedAt());
        boolean discussionDue = !quiet || (timed && expired(times.discussionAt(), policy.discussionFreshness(), now));
        if ((!policy.checksOnly() || missing) && discussionDue) {
            // A quiet warm reconcile rarely finds anything new, so it asks with ETags first and skips every GraphQL read on 304s.
            // Refreshes someone waits for, opening included, still read everything: thread resolutions change no REST response.
            Optional<DiscussionProbe> probe = policy.warm() && quiet ? Optional.of(gitHub.probeDiscussion(pat, ref, details.findDiscussionEtags(current.id()))) : Optional.empty();
            if (probe.filter(DiscussionProbe::unchanged).isPresent()) {
                detail = snapshots.saveDiscussion(current.id(), detail, sourceAt, scope);
            }
            else {
                StoredPrDetail fetched = PullRequestDetailFetcher.await(detailFetcher.fetchDiscussion(pat, ref, detail));
                detail = snapshots.saveDiscussion(current.id(), fetched, sourceAt, scope);
                // Taken before the refetch, these ETags can only be older than what was saved; a partial save keeps none.
                boolean complete = fetched.reviewsComplete() && fetched.timelineComplete();
                probe.ifPresent(asked -> withCurrentScope(scope, () -> {
                    details.updateDiscussionEtags(current.id(), complete ? asked.etags() : ImmutableMap.of());
                    return null;
                }));
                full = true;
            }
        }
        boolean checkChanged = current.ciStatus() != PullRequestDetailMapper.aggregateCiStatus(detail.checkRuns());
        // Checks still running when a pull request closed finish without any activity.
        if (missing || policy.force() || headChanged || checkChanged || !detail.checkRunsComplete()
                || ((timed || current.ciStatus() == PullRequestDetail.CiStatus.PENDING) && expired(times.checksAt(), policy.freshness(), now))) {
            try {
                detail = snapshots.refreshChecks(pat, ref, current.id());
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure) || failure instanceof HostingFailure || PullRequestSnapshotService.inaccessible(failure)) {
                    throw failure;
                }
                log.warn("Could not refresh checks for {}; retaining cached checks: {}", ref.fullName(), failure.getMessage());
            }
        }
        StoredPrDetail stored = detail;
        withCurrentScope(scope, () -> {
            storeDetail(current, stored, login);
            return null;
        });
        // An edited description leaves no timeline event; the update that carried it dates the edit.
        Optional<Instant> describedAt = previous.filter(old -> !Objects.equals(old.raw().body(), stored.raw().body())).map(ignored -> stored.raw().updatedAt());
        Stream.of(stored.timelineComplete() ? attentionActivityAt(stored.timeline(), login) : Optional.<Instant>empty(), describedAt)
                .flatMap(Optional::stream).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(at -> withCurrentScope(scope, () -> {
                    store.observeAttentionActivity(current.id(), at);
                    return null;
                }));
        if ((!policy.checksOnly() || missing) && (policy.force() || !snapshots.defaultDiffMatches(current.id(), detail.raw()))) {
            syncDiffQuietly(pat, current);
        }
        boolean complete = full;
        return withCurrentScope(scope, () -> new DetailRefresh(complete,
                PullRequestDetailMapper.toPullRequestDetail(current.id(), ref.repoFullName(), ref.number(), stored, stored.viewerCanWrite())));
    }

    Instant discussionSourceUpdatedAt(PullRequest row)
    {
        return store.findDiscussionActivityAt(row.id()).filter(at -> at.isAfter(row.updatedAt())).orElse(row.updatedAt());
    }

    /** {@link #discussionSourceUpdatedAt} from discussion times read in bulk. */
    private static Instant discussionSource(PullRequest row, Map<Long, Instant> discussed)
    {
        return Optional.ofNullable(discussed.get(row.id())).filter(at -> at.isAfter(row.updatedAt())).orElse(row.updatedAt());
    }

    private static boolean expired(Optional<Instant> validatedAt, Duration freshness, Instant now)
    {
        return validatedAt.filter(at -> at.isAfter(now.minus(freshness))).isEmpty();
    }

    private static boolean newer(Instant sourceAt, Optional<Instant> validatedSource)
    {
        return sourceAt != null && validatedSource.filter(at -> !at.isBefore(sourceAt)).isEmpty();
    }

    private record DetailKey(PullRequestRef ref, String scope) {}

    private record DetailWork(RefreshPolicy policy, CompletableFuture<DetailRefresh> result) {}

    /** Returns locally persisted diff files without contacting GitHub. */
    public List<DiffFile> getPullRequestDiffFiles(String repo, int number, String baseSha, String headSha)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> snapshots.getDiffFiles(storedPullRequestId(repo, number), baseSha, headSha));
    }

    /** Rejects a cached diff from another head before a review freezes it as evidence. */
    public List<DiffFile> getValidatedPullRequestDiffFiles(String repo, int number, String headSha)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> snapshots.getValidatedDiffFiles(storedPullRequestId(repo, number), headSha));
    }

    public List<DiffCommit> getPullRequestDiffCommits(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> snapshots.getDiffCommits(storedPullRequestId(repo, number)));
    }

    /** Returns locally persisted file content without contacting GitHub. */
    public DiffFileContent getPullRequestDiffFileContent(String repo, int number, String path, String baseSha, String headSha)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> snapshots.getFileContent(storedPullRequestId(repo, number), path, baseSha, headSha));
    }

    void refreshPullRequestDiffFromGitHub(String repo, int number, String baseSha, String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshDiff(pat, ref, storedPullRequestId(repo, number), baseSha, headSha);
    }

    void refreshPullRequestDiffFileContentFromGitHub(String repo, int number, String path, String baseSha, String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshFileContent(pat, ref, storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    public Posted createReviewComment(String repo, int number, InlineReviewComment comment)
    {
        return actions.createReviewComment(repo, number, comment);
    }

    public PullRequestDetail commentOnPullRequest(String repo, int number, String body)
    {
        return actions.commentOnPullRequest(repo, number, body);
    }

    public boolean toggleReaction(String repo, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        return actions.toggleReaction(repo, number, target, commentId, content, viewerHasReacted);
    }

    public void updateTitle(String repo, int number, String title)
    {
        actions.updateTitle(repo, number, title);
    }

    public void updateDescription(String repo, int number, String description)
    {
        actions.updateDescription(repo, number, description);
    }

    public void closePullRequest(String repo, int number)
    {
        actions.closePullRequest(repo, number);
    }

    public void markReadyForReview(String repo, int number)
    {
        actions.markReadyForReview(repo, number);
    }

    public void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        actions.updateMetadata(repo, number, assignees, labels);
    }

    public void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        actions.updateReviewerRequest(repo, number, login, requested);
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        actions.replyToReviewComment(repo, number, commentId, body);
    }

    /** Edits a posted comment or review body on GitHub, then patches the cached copies of that body. */
    public PullRequestDetail editComment(String repo, int number, String target, long commentId, String body)
    {
        return actions.editComment(repo, number, target, commentId, body);
    }

    /** The latest comment of each review thread whose author can push to the repository, read from GitHub now. */
    public Set<Long> reviewCommentsByPushers(String repo, int number)
    {
        return gitHub.latestReviewCommentsByPushers(credentialService.resolveGitHubToken(), parseRef(repo, number));
    }

    public void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        actions.setReviewThreadResolved(repo, number, rootCommentId, resolved);
    }

    /**
     * Submits a review, with any inline comments, for the given pull request on GitHub and
     * records a local reviewed state.
     */
    public Posted submitReview(String repo, int number, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        return actions.submitReview(repo, number, prId, event, body, comments, commitId);
    }

    /**
     * A write GitHub accepted. The threads are what it returned for the new comments, empty when they
     * could not be read back; a review id limits them to that review.
     */
    public record Posted(PullRequestDetail detail, OptionalLong reviewId, List<PrTimelineEvent> threads)
    {
        public Posted
        {
            requireNonNull(detail, "detail is null");
            requireNonNull(reviewId, "reviewId is null");
            threads = ImmutableList.copyOf(threads);
        }
    }

    /** The head SHA locally recorded for a pull request, without contacting GitHub. */
    public Optional<String> findHeadSha(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> store.findIdByRepoAndNumber(parseRef(repo, number).repoFullName(), number).flatMap(snapshots::findHeadSha));
    }

    /** Merges directly, falling back to GitHub's merge queue when required. */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy, String headSha)
    {
        return actions.mergePullRequest(repo, number, prId, strategy, headSha);
    }

    public void removeFromMergeQueue(String repo, int number, boolean autoMerge)
    {
        actions.removeFromMergeQueue(repo, number, autoMerge);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void checkPage(int page, int pageSize)
    {
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
    }

    private void storeDetail(PullRequest pullRequest, StoredPrDetail detail, String currentLogin)
    {
        long prId = pullRequest.id();
        PullRequest current = store.findById(prId).orElse(pullRequest);
        if (detail.raw().updatedAt() != null && current.updatedAt().isAfter(detail.raw().updatedAt())) {
            return;
        }
        updateListState(prId, detail);
        PrViewState viewState = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        reconcileHandledActionFromReviews(prId, detail.reviews(), currentLogin, viewState);
        viewState = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        if (needsReviewAgain(pullRequest, detail, currentLogin, viewState)) {
            viewStateStore.markNeedsReview(prId);
        }
        Instant pushedAt = detail.timelineComplete() ? PullRequestDetailMapper.latestPushAt(detail.timeline()) : current.headPushedAt();
        if (current.headPushedAt() != null && (pushedAt == null || current.headPushedAt().isAfter(pushedAt))) {
            // A summary can date an observed force-push later than the commit's timestamp.
            pushedAt = current.headPushedAt();
        }
        store.updateEnrichment(
                prId,
                detail.raw().updatedAt(),
                detail.checkRunsComplete() ? PullRequestDetailMapper.aggregateCiStatus(detail.checkRuns()) : pullRequest.ciStatus(),
                detail.raw().additions(),
                detail.raw().deletions(),
                detail.timelineComplete() ? PullRequestDetailMapper.countComments(detail.timeline()) : pullRequest.commentCount(),
                detail.raw().mergeable(),
                detail.raw().mergeableState(),
                pushedAt,
                detail.raw().headRef(),
                detail.raw().requestedReviewers(),
                pullRequest.author().equalsIgnoreCase(currentLogin) ? AUTHORED
                        : detail.raw().requestedReviewers().stream().anyMatch(currentLogin::equalsIgnoreCase)
                                || notifications.reviewRequests(currentLogin).contains(parseRef(pullRequest.repo(), pullRequest.number())) ? REVIEW_REQUESTED : OTHER,
                detail.timeline().stream()
                        .filter(event -> "review_requested".equals(event.event()) && currentLogin.equalsIgnoreCase(event.payload().requestedReviewer()))
                        .map(PrTimelineEvent::timestamp)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()),
                detail.reviewsComplete() ? reviewDecision(detail.reviews()) : Optional.ofNullable(pullRequest.reviewDecision()),
                activityFingerprints(detail));
    }

    /**
     * The latest timeline activity that ends an acknowledgment: anything but the quiet events, bot comments and reviews, and review
     * requests that do not name the viewer.
     */
    static Optional<Instant> attentionActivityAt(List<PrTimelineEvent> timeline, String viewer)
    {
        return timeline.stream().filter(event -> event.localEventKey().isEmpty()).flatMap(event -> switch (event.event()) {
            case "review_thread" -> event.payload().comments().stream().filter(comment -> !bot(comment.actor(), comment.actorBot())).map(PrTimelineComment::timestamp);
            case "commented", "reviewed" -> bot(event.actor(), event.payload().actorBot()) ? Stream.empty() : Stream.of(event.timestamp());
            case "review_requested", "review_request_removed" -> viewer.equalsIgnoreCase(event.payload().requestedReviewer()) ? Stream.of(event.timestamp()) : Stream.empty();
            default -> QUIET_EVENTS.contains(event.event()) ? Stream.empty() : Stream.of(event.timestamp());
        }).filter(Objects::nonNull).max(Comparator.naturalOrder());
    }

    private static boolean bot(String actor, boolean actorBot)
    {
        return actorBot || actor != null && actor.endsWith("[bot]");
    }

    static Map<String, String> activityFingerprints(StoredPrDetail detail)
    {
        PrRawDetail raw = detail.raw();
        PrRawDetail canonicalRaw = new PrRawDetail(
                raw.title(), raw.htmlUrl(), raw.state(), raw.author(), raw.authorAvatarUrl(), raw.createdAt(), raw.updatedAt(), raw.mergedAt(), raw.body(),
                raw.labels().stream().sorted().collect(toImmutableList()), new TreeMap<>(raw.labelColors()),
                raw.assignees().stream().sorted().collect(toImmutableList()), raw.milestone(), raw.draft(), raw.mergeable(), raw.mergeableState(),
                raw.additions(), raw.deletions(), raw.changedFiles(), raw.requestedReviewerCount(),
                raw.requestedReviewers().stream().sorted().collect(toImmutableList()), raw.headSha(), raw.headRef(), raw.baseRef());
        ImmutableMap.Builder<String, String> fingerprints = ImmutableMap.builder();
        fingerprints.put("raw", fingerprint(canonicalRaw));
        // Each successful source advances independently; a failed timeline fetch cannot hide a CI change.
        if (detail.reviewsComplete()) {
            fingerprints.put("reviews", fingerprint(detail.reviews().stream().map(Object::toString).sorted().collect(toImmutableList())));
        }
        if (detail.timelineComplete()) {
            fingerprints.put("timeline", fingerprint(detail.timeline().stream().map(Object::toString).sorted().collect(toImmutableList())));
        }
        if (detail.checkRunsComplete()) {
            fingerprints.put("checks", fingerprint(detail.checkRuns().stream().map(Object::toString).sorted().collect(toImmutableList())));
        }
        return fingerprints.buildOrThrow();
    }

    private static String fingerprint(Object value)
    {
        return Hashing.sha256().hashString(value.toString(), StandardCharsets.UTF_8).toString();
    }

    static Optional<String> reviewDecision(List<PrReviewState> reviews)
    {
        Set<String> verdicts = PrReviewState.latestVerdicts(reviews).stream().map(PrReviewState::state).collect(toImmutableSet());
        if (verdicts.contains("CHANGES_REQUESTED")) {
            return Optional.of("CHANGES_REQUESTED");
        }
        return verdicts.contains("APPROVED") ? Optional.of("APPROVED") : Optional.empty();
    }

    private boolean syncDiffQuietly(String pat, PullRequest pullRequest)
    {
        try {
            snapshots.refreshDefaultDiff(pat, parseRef(pullRequest.repo(), pullRequest.number()), pullRequest.id());
            return true;
        }
        catch (RuntimeException failure) {
            if (isRateLimit(failure) || failure instanceof HostingFailure || PullRequestSnapshotService.inaccessible(failure)) {
                throw failure;
            }
            log.warn("Failed to sync diff for PR {}/{}: {}", pullRequest.repo(), pullRequest.number(), failure.getMessage());
            return false;
        }
    }

    private long storedPullRequestId(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number)
                .orElseThrow(() -> PullRequestSnapshotService.missing("Pull request"));
    }

    /** Returns persisted detail without contacting GitHub or dating the viewer's last look. */
    public Optional<PullRequestDetail> findPullRequestDetail(String repo, int number)
    {
        String scope = prepareRead(repo, number);
        return withCurrentScope(scope, () -> store.findIdByRepoAndNumber(repo, number).flatMap(prId -> snapshots.findDetail(repo, number, prId)));
    }

    private void updateListState(long prId, StoredPrDetail detail)
    {
        if (detail.raw() != null && detail.raw().state() != null) {
            store.updateDetails(prId, detail.raw());
        }
    }

    /**
     * If the user has submitted a review on this PR outside the app (via the
     * embedded github.com window, a browser tab, or the GitHub mobile app),
     * mirror that state into {@code pr_view_state.handled_action} so the PR
     * shows up correctly in the Handled bucket. A later GitHub review advances
     * the baseline; an equal or newer local action remains authoritative.
     */
    private void reconcileHandledActionFromReviews(long prId, List<PrReviewState> reviews, String currentLogin, PrViewState existing)
    {
        PrReviewState review = latestHandledReview(reviews, currentLogin);
        if (review == null) {
            return;
        }
        // A review already recorded stays cleared once the PR needs review again; re-applying it made every refresh write the action and
        // clear it again, and each write rebuilt the attention list. Only a newer review counts.
        if (existing != null && (existing.handledAction() != null || existing.reviewedAt() != null)
                && (review.submittedAt() == null || existing.reviewedAt() == null || !review.submittedAt().isAfter(existing.reviewedAt()))) {
            return;
        }
        viewStateStore.markReviewed(prId, handledAction(review), review.commitId(), Optional.ofNullable(review.submittedAt()).orElse(Instant.now()));
    }

    private static PrReviewState latestHandledReview(List<PrReviewState> reviews, String currentLogin)
    {
        if (currentLogin == null || reviews == null || reviews.isEmpty()) {
            return null;
        }
        PrReviewState derived = null;
        for (PrReviewState r : reviews) {
            if (r == null || !currentLogin.equalsIgnoreCase(r.login())) {
                continue;
            }
            if (GithubReviewState.APPROVED.equals(r.state())
                    || GithubReviewState.CHANGES_REQUESTED.equals(r.state())
                    || GithubReviewState.COMMENTED.equals(r.state())) {
                derived = r;
            }
            else if ("DISMISSED".equals(r.state())) {
                // A later DISMISSED resets the derived state — if nothing
                // else follows, the user effectively has no stance.
                derived = null;
            }
        }
        return derived;
    }

    private static HandledAction handledAction(PrReviewState review)
    {
        if (GithubReviewState.APPROVED.equals(review.state())) {
            return HandledAction.APPROVED;
        }
        if (GithubReviewState.CHANGES_REQUESTED.equals(review.state())) {
            return HandledAction.CHANGES_REQUESTED;
        }
        return HandledAction.COMMENTED;
    }

    static boolean needsReviewAgain(PullRequest pullRequest, StoredPrDetail detail, String currentLogin, PrViewState viewState)
    {
        if (viewState == null || viewState.handledAction() == null || viewState.reviewedAt() == null) {
            return false;
        }
        if (currentLogin != null && detail.raw().requestedReviewers().stream()
                .anyMatch(currentLogin::equalsIgnoreCase)
                && detail.timeline().stream().anyMatch(event -> "review_requested".equals(event.event())
                        && currentLogin.equalsIgnoreCase(event.payload().requestedReviewer())
                        && event.timestamp() != null && event.timestamp().isAfter(viewState.reviewedAt()))) {
            return true;
        }
        if (latestReviewState(detail.reviews(), currentLogin).map("DISMISSED"::equalsIgnoreCase).orElse(false)) {
            return true;
        }
        // Approval keeps routine updates quiet until someone explicitly asks for review again or dismisses it.
        if (viewState.handledAction() == HandledAction.APPROVED) {
            return false;
        }
        String currentHeadSha = detail.raw().headSha();
        if (viewState.reviewedHeadSha() != null && currentHeadSha != null
                && !viewState.reviewedHeadSha().equals(currentHeadSha)) {
            return true;
        }
        if (viewState.reviewedHeadSha() == null) {
            Instant latestPush = PullRequestDetailMapper.latestPushAt(detail.timeline());
            if (latestPush != null && latestPush.isAfter(viewState.reviewedAt())) {
                return true;
            }
        }
        return currentLogin != null && pullRequest.author() != null
                && hasAuthorReplyAfter(detail.timeline(), currentLogin, pullRequest.author(), viewState.reviewedAt());
    }

    private static boolean hasAuthorReplyAfter(List<PrTimelineEvent> timeline, String currentLogin, String author, Instant reviewedAt)
    {
        return timeline.stream()
                .filter(item -> "review_thread".equals(item.event()))
                .filter(item -> item.payload().comments().stream().anyMatch(comment -> comment.inReplyToId() == null
                        && currentLogin.equalsIgnoreCase(comment.actor())))
                .flatMap(item -> item.payload().comments().stream())
                .anyMatch(comment -> comment.inReplyToId() != null
                        && author.equalsIgnoreCase(comment.actor())
                        && comment.timestamp() != null
                        && comment.timestamp().isAfter(reviewedAt));
    }

    private static Optional<String> latestReviewState(List<PrReviewState> reviews, String currentLogin)
    {
        if (currentLogin == null) {
            return Optional.empty();
        }
        String state = null;
        for (PrReviewState review : reviews) {
            if (review != null && currentLogin.equalsIgnoreCase(review.login())) {
                state = review.state();
            }
        }
        return Optional.ofNullable(state);
    }

    record DetailRefresh(boolean full, PullRequestDetail detail)
    {
        DetailRefresh
        {
            requireNonNull(detail, "detail is null");
        }
    }
}
