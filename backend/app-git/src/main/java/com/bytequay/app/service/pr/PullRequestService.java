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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingPullRequestWrites;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
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
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.DiscussionProbe;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestListPage;
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
import java.util.stream.Stream;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
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
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final WorkspaceHostingService workspaceHosting;
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
            HostingPullRequestWrites pullRequestWrites,
            GitHubRepoClient repositories,
            GitHubAccountClient accounts,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PrDetailStore details,
            PullRequestDetailFetcher detailFetcher,
            PrViewStateStore viewStateStore,
            RecentTouchRecorder recent,
            CredentialService credentialService,
            HostingConnectionService connections,
            HostingPullRequestService requests,
            WorkspaceHostingService workspaceHosting,
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
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.workspaceHosting = requireNonNull(workspaceHosting, "workspaceHosting is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.summaries = requireNonNull(summaries, "summaries is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.applicationContext = requireNonNull(applicationContext, "applicationContext is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.syncSettings = requireNonNull(syncSettings, "syncSettings is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.actions = new PullRequestActions(pullRequestWrites, store, snapshots, details, viewStateStore,
                recent, requests, this::getPullRequestDetail, this::findStoredId);
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
        return lookupPullRequest(prepareRead(repo, number));
    }

    public PullRequest lookupPullRequest(PullRequestRead route)
    {
        prepareRead(route);
        HostingPullRequestSnapshot metadata = requests.cached(route.session(), route.repository().id(), route.number())
                .orElseGet(() -> refreshMetadata(route, false));
        return project(route, metadata.pullRequest().withViewerOrigin(route.login()));
    }

    /** Reads the cached description the same way, so it is empty until a detail read has stored the pull request once. */
    public Optional<String> findStoredDescription(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number).flatMap(details::find).map(detail -> Optional.ofNullable(detail.raw().body()).orElse(""));
    }

    public Optional<String> findStoredDescription(PullRequestRead route)
    {
        prepareRead(route);
        return findStoredId(route).flatMap(details::find).map(detail -> Optional.ofNullable(detail.raw().body()).orElse(""));
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
        return setMonitored(prId, monitored, null);
    }

    public PullRequest setMonitored(long prId, boolean monitored, String workspaceId)
    {
        PullRequest row = store.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request not found"));
        if (monitored && "merged".equalsIgnoreCase(row.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Merged pull requests cannot be monitored");
        }
        if (store.findHostingIdentity(prId).isPresent()) {
            if (monitored && row.monitoredAt() == null) {
                PullRequestRead route = prepareRead(workspaceId, row.repo(), row.number());
                checkArgument(storedPullRequestId(route) == prId, "Pull request belongs to another workspace");
                refreshPullRequestDetailFromGitHub(route);
            }
            store.setMonitored(prId, monitored);
            return store.findById(prId).orElseThrow();
        }
        if (monitored && row.monitoredAt() == null) {
            HostingReadSession session = credentialService.captureHostingRead();
            String login = connections.account(session).login();
            GitHubPullRequestSummary summary = summaries.fetchSummaries(
                            session.access().token(), ImmutableList.of(parseRef(row.repo(), row.number())), login).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not load pull request activity. Retry monitoring shortly."));
            // Establish the same lightweight baseline used by polling before enabling notifications.
            PullRequest observed = store.saveObserved(summary.pullRequest());
            storeCardSummary(session.access().token(), observed, summary, login);
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
    public void observeWorkspacePullRequest(String workspaceId, int number)
    {
        WorkspaceHostingRead route = workspaceHosting.captureRead(workspaceId);
        FetchedPullRequest pullRequest = workspaceHosting.readPullRequest(route, number).pullRequest().withViewerOrigin(route.login());
        project(new PullRequestRead(route.session(), route.repository(), number, route.login()), pullRequest);
    }

    /**
     * Discovers repository updates independently of the bounded full-detail warm set. After one complete pass, later passes
     * read only what changed since: rereading every open and recent pull request was most of the scheduled refresh's search
     * time, and the attention job already reads each watched repository's updates every few minutes.
     */
    public void syncRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        HostingReadSession session = credentialService.captureHostingRead();
        String login = connections.account(session).login();
        syncRepository(session, connections.resolveRepository(session, repository.fullName()), login);
    }

    public void syncWorkspace(String workspaceId)
    {
        WorkspaceHostingRead route = workspaceHosting.captureRead(workspaceId);
        syncRepository(route.session(), route.repository(), route.login());
    }

    private void syncRepository(HostingReadSession session, HostingRepository repository, String login)
    {
        String pat = session.access().token();
        boolean github = session.access().server().provider() == GITHUB;
        if (github) {
            baselineSummaries(pat);
        }
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        Consumer<List<FetchedPullRequest>> save = page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(login)).collect(toImmutableList());
            if (github) {
                store.saveRepositorySyncPage(repository.fullPath(), rows);
            }
            rows.forEach(row -> seen.put(row.repo() + "#" + row.number(), row));
        };
        String key = "hosting.repository-sync." + session.cacheScope() + "." + repository.id();
        Instant started = clock.instant();
        // Deleting the last workspace of a repository hides its rows, so watching it again needs the full pass.
        Optional<Instant> synced = syncSettings.get(key).map(Instant::parse)
                .filter(at -> at.isAfter(started.minus(Duration.ofDays(CLOSED_RETENTION_DAYS))) && !at.isAfter(started))
                .filter(ignored -> github ? store.findRepositoryPage(repository.fullPath(), 0, 1).totalPullRequests() > 0
                        : !requests.cachedPage(session, repository.id(), 1, 1).items().isEmpty());
        if (synced.isPresent()) {
            discoverRepository(session, repository.id(), false, synced.orElseThrow().minusSeconds(300), save);
        }
        else {
            // A full pass that fails part way must not leave an older checkpoint behind its partly saved rows.
            syncSettings.delete(key);
            discoverRepository(session, repository.id(), false, started.minus(Duration.ofDays(CLOSED_RETENTION_DAYS)), save);
            discoverRepository(session, repository.id(), true, Instant.MIN, save);
        }
        syncSettings.put(key, started.toString());
        if (github) {
            enrichCards(pat, login, seen.values().stream().map(row -> parseRef(row.repo(), row.number())).collect(toImmutableList()));
        }
        // Discovery never deletes cached rows or local marks.
    }

    private void discoverRepository(HostingReadSession session, long repositoryId, boolean openOnly, Instant since, Consumer<List<FetchedPullRequest>> save)
    {
        for (int page = 1; ; page++) {
            PullRequestListPage result = requests.refreshPage(session, repositoryId, openOnly, page, SEARCH_PAGE_SIZE);
            List<FetchedPullRequest> recent = result.items().stream().filter(row -> !row.updatedAt().isBefore(since)).collect(toImmutableList());
            save.accept(recent);
            if (!result.hasNext() || recent.size() < result.items().size()) {
                return;
            }
        }
    }

    /** Discovers one task branch without a repository-wide search or detail refresh. */
    public void syncRepositoryBranch(String workspaceId, String headOwner, String branch)
    {
        checkText(headOwner, "headOwner");
        checkText(branch, "branch");
        WorkspaceHostingRead route = workspaceHosting.captureRead(workspaceId);
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        RepoRef source = RepoRef.of(headOwner, workspace.selectedRepository().repo());
        PullRequestRead selected = new PullRequestRead(route.session(), route.repository(), 1, route.login());
        for (var identity : detailFetcher.identitiesByHead(selected, source, branch)) {
            observeWorkspacePullRequest(workspaceId, identity.pullRequest().number());
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

    public List<String> listRepositoryTeams(String repo, String workspaceId)
    {
        return workspaceId == null || workspaceId.isBlank() ? listRepositoryTeams(repo) : metadata.listTeams(workspaceId);
    }

    public List<RepositoryLabel> listRepositoryLabels(String repo, String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return listRepositoryLabels(repo);
        }
        RepositoryMetadataSnapshot snapshot = metadata.getSnapshot(workspaceId);
        checkArgument(snapshot.repository().fullName().equalsIgnoreCase(repo), "Request belongs to another workspace repository");
        return snapshot.labels();
    }

    public boolean hasMergeQueue(String repo, String baseRef)
    {
        return actions.hasMergeQueue(prepareRead(repo, 1), baseRef);
    }

    public PullRequestCheckLog readCheckLog(String repo, int number, long jobId)
    {
        checkArgument(jobId > 0, "jobId must be positive");
        return readCheckLog(prepareRead(repo, number), jobId);
    }

    public void rerunFailedChecks(String repo, int number)
    {
        rerunFailedChecks(prepareRead(repo, number));
    }

    public PullRequestCheckLog readCheckLog(PullRequestRead route, long jobId)
    {
        checkArgument(jobId > 0, "jobId must be positive");
        return snapshots.readCheckLog(route, jobId);
    }

    public void rerunFailedChecks(PullRequestRead route)
    {
        snapshots.rerunFailedChecks(route);
    }

    public boolean hasMergeQueue(String repo, String baseRef, String workspaceId)
    {
        return actions.hasMergeQueue(prepareRead(workspaceId, repo, 1), baseRef);
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
        HostingReadSession session = credentialService.captureHostingRead();
        String pat = session.access().token();
        String login = connections.account(session).login();
        baselineSummaries(pat);
        Set<PullRequestRef> changed = new LinkedHashSet<>();
        try {
            notifications.poll(session.access().token(), login, notification -> ingestNotification(session, login, notification, changed));
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
        workspaces.findAll().stream().filter(Workspace::isGitHub).map(workspace -> workspace.monitoredRepository().fullName()).distinct()
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
                ran = discoverRecent(session, login, query, queries.get(query), seen);
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
        enrichAttentionCards(session, login, ImmutableList.copyOf(changed));
    }

    /** Returns whether the query was due; one that completed within two minutes is skipped. */
    private boolean discoverRecent(HostingReadSession session, String login, String query, PullRequest.Origin origin, Map<String, FetchedPullRequest> seen)
    {
        String key = "github.discovery." + login.toLowerCase(Locale.ROOT) + "." + Hashing.sha256().hashString(query, StandardCharsets.UTF_8);
        Instant started = clock.instant();
        Optional<Instant> completed = syncSettings.get(key).map(Instant::parse).filter(at -> !at.isAfter(started));
        if (completed.filter(at -> started.isBefore(at.plusSeconds(120))).isPresent()) {
            return false;
        }
        Instant since = completed.orElse(started.minus(Duration.ofHours(1))).minusSeconds(300);
        discoverQuery(session, query + " updated:>=" + since.truncatedTo(ChronoUnit.SECONDS), page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(login))
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

    private void ingestNotification(HostingReadSession session, String login, GitHubNotificationRef notification, Set<PullRequestRef> changed)
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

        PullRequestRead route = requestRead(session, ref.repoFullName(), ref.number());
        FetchedPullRequest fetched = refreshMetadata(route, false).pullRequest().withViewerOrigin(login);
        FetchedPullRequest observed = notification.reviewRequested() && fetched.origin() != AUTHORED ? fetched.withOrigin(REVIEW_REQUESTED) : fetched;
        PullRequest row = store.saveObserved(observed);
        // Save the dirty marker before enrichment: a comment request failure cannot hide the notification.
        if (!"ci_activity".equals(notification.reason())) {
            store.observeActivity(row.id(), Optional.empty(), notification.updatedAt());
        }
        synchronized (summaryStamps) {
            summaryStamps.remove(ref);
            summaryGeneration++;
        }
        changed.add(ref);
        try {
            notifications.activity(session.access().token(), notification)
                    .ifPresent(activity -> store.observeActivity(row.id(), Optional.of(activity), notification.updatedAt()));
        }
        catch (RuntimeException failure) {
            log.debug("Notification comment preview unavailable for {}: {}", ref, failure.getMessage());
        }
    }

    private void syncAttentionQuery(HostingReadSession session, Map<String, FetchedPullRequest> seen, String query, PullRequest.Origin origin)
    {
        discoverQuery(session, query, page -> saveAttentionRows(page, seen, origin));
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

    private void discoverQuery(HostingReadSession session, String query, Consumer<List<FetchedPullRequest>> save)
    {
        discoverQuery(session.access().token(), query, save);
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
        HostingReadSession session = credentialService.captureHostingRead();
        String login = connections.account(session).login();
        String pat = session.access().token();
        baselineSummaries(pat);
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        syncAttentionQuery(session, seen, "is:pr is:open author:@me", AUTHORED);
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        syncAttentionQuery(session, seen, "is:pr is:closed author:@me updated:>=" + sinceDate, AUTHORED);
        syncAttentionQuery(session, seen, "is:pr is:open user-review-requested:@me", REVIEW_REQUESTED);
        syncAttentionQuery(session, seen, "is:pr is:open reviewed-by:@me", OTHER);
        syncAttentionQuery(session, seen, "is:pr involves:@me updated:>=" + sinceDate, OTHER);
        List<FetchedPullRequest> fresh = ImmutableList.copyOf(seen.values());
        store.saveDashboardSyncPage(fresh);
        enrichCards(session, login, fresh.stream().map(row -> parseRef(row.repo(), row.number())).collect(toImmutableList()));
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

    private void enrichCards(HostingReadSession session, String login, List<PullRequestRef> candidates)
    {
        enrichCards(session.access().token(), login, candidates);
    }

    private void enrichCards(String pat, String login, List<PullRequestRef> candidates)
    {
        List<PullRequestRef> refs = dueSummaries(pat, candidates);
        for (int offset = 0; offset < refs.size(); offset += GitHubPullRequestSummaryClient.BATCH_SIZE) {
            summaryLock.lock();
            try {
                List<PullRequestRef> batch = dueSummaries(pat, refs.subList(offset, Math.min(offset + GitHubPullRequestSummaryClient.BATCH_SIZE, refs.size())));
                enrichCardBatch(pat, login, batch);
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
    private void enrichAttentionCards(HostingReadSession session, String login, List<PullRequestRef> candidates)
    {
        if (!summaryLock.tryLock()) {
            return;
        }
        try {
            List<PullRequestRef> refs = dueSummaries(session.access().token(), candidates);
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
            enrichCardBatch(session, login, batch);
        }
        finally {
            summaryLock.unlock();
        }
    }

    private List<PullRequestRef> dueSummaries(String pat, List<PullRequestRef> candidates)
    {
        Instant now = clock.instant();
        return candidates.stream().distinct().filter(ref -> {
            Optional<PullRequest> row = store.findIdByRepoAndNumber(ref.repoFullName(), ref.number()).flatMap(store::findById);
            SummaryStamp stamp = summaryStamps.get(ref);
            if (stamp == null || !stamp.credential().equals(pat) || row.isEmpty()) {
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
    private void enrichCardBatch(HostingReadSession session, String login, List<PullRequestRef> batch)
    {
        enrichCardBatch(session.access().token(), login, batch);
    }

    private void enrichCardBatch(String pat, String login, List<PullRequestRef> batch)
    {
        if (batch.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        long generation;
        synchronized (summaryStamps) {
            generation = summaryGeneration;
        }
        Set<PullRequestRef> reviewRequests = notifications.reviewRequests(login);
        long started = System.nanoTime();
        try {
            // Persist each completed batch before asking for the next one.
            for (GitHubPullRequestSummary summary : summaries.fetchSummaries(pat, batch, login)) {
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
                storeCardSummary(pat, row, summary, login);
                if (row.updatedAt().isAfter(summary.pullRequest().updatedAt())) {
                    continue;
                }
                Instant source = summary.discussionUpdatedAt().isAfter(summary.pullRequest().updatedAt()) ? summary.discussionUpdatedAt() : summary.pullRequest().updatedAt();
                synchronized (summaryStamps) {
                    // An inbox update or credential switch during this request must remain eligible for a newer read.
                    if (generation == summaryGeneration) {
                        summaryStamps.put(ref, new SummaryStamp(pat, source, now));
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
        PullRequestDetail detail = getPullRequestDetail(repo, number);
        // Opening the pull request dates the "since you last looked"
        // comparison. It is deliberately unconditional and separate from the
        // Recent stream, which needs a stronger signal than a triage click.
        markViewed(detail.id());
        return detail;
    }

    public PullRequestDetail openPullRequestDetail(PullRequestRead route)
    {
        PullRequestDetail detail = getPullRequestDetail(route);
        // Opening the pull request dates the "since you last looked"
        // comparison. It is deliberately unconditional and separate from the
        // Recent stream, which needs a stronger signal than a triage click.
        markViewed(detail.id());
        return detail;
    }

    /** Returns persisted detail, fetching and caching it on a local miss. */
    public PullRequestDetail getPullRequestDetail(String repo, int number)
    {
        parseRef(repo, number);
        return findPullRequestDetail(repo, number)
                .orElseGet(() -> refreshPullRequestDetailFromGitHub(repo, number).detail());
    }

    public PullRequestDetail getPullRequestDetail(PullRequestRead route)
    {
        prepareRead(route);
        return findStoredId(route)
                .flatMap(id -> snapshots.findDetail(route.repository().fullPath(), route.number(), id))
                .orElseGet(() -> refreshPullRequestDetailFromGitHub(route).detail());
    }

    /** Captures account and repository identity once before reading desktop projections. */
    public PullRequestRead prepareRead(String repo, int number)
    {
        PullRequestRead route = requestRead(credentialService.captureHostingRead(), repo, number);
        prepareRead(route);
        return route;
    }

    public PullRequestRead prepareRead(String workspaceId, String repo, int number)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return prepareRead(repo, number);
        }
        WorkspaceHostingRead selected = workspaceHosting.captureRead(workspaceId);
        checkArgument(selected.repository().fullPath().equalsIgnoreCase(repo), "Request belongs to another workspace repository");
        PullRequestRead route = new PullRequestRead(selected.session(), selected.repository(), number, selected.login());
        prepareRead(route);
        return route;
    }

    public String requestHeadRef(PullRequestRead route)
    {
        return detailFetcher.headRef(route);
    }

    public Optional<PullRequest> findStored(PullRequestRead route)
    {
        return route.session().access().server().provider() == GITHUB
                ? findStored(route.repository().fullPath(), route.number()) : store.findByHostingRepository(route.repository().id(), route.number());
    }

    public Optional<Long> findStoredId(PullRequestRead route)
    {
        return route.session().access().server().provider() == GITHUB
                ? store.findIdByRepoAndNumber(route.repository().fullPath(), route.number())
                : store.findByHostingRepository(route.repository().id(), route.number()).map(PullRequest::id);
    }

    public long storedPullRequestId(PullRequestRead route)
    {
        return findStoredId(route).orElseThrow(() -> PullRequestSnapshotService.missing("Pull request"));
    }

    private PullRequest project(PullRequestRead route, FetchedPullRequest summary)
    {
        return route.session().access().server().provider() == GITHUB ? store.saveObserved(summary)
                : store.findByHostingRepository(route.repository().id(), route.number()).orElseGet(() -> store.saveHosted(route.repository().id(), summary));
    }

    public List<PullRequest> listWorkspaceAttentionCandidates(String workspaceId)
    {
        return workspaceHosting.cachedAttention(workspaceId);
    }

    public boolean hasMergeQueue(PullRequestRead route, String baseRef)
    {
        return actions.hasMergeQueue(route, baseRef);
    }

    public PullRequestPage listWorkspacePullRequests(String workspaceId, int page, int pageSize, String search)
    {
        checkPage(page, pageSize);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String number = term.startsWith("#") ? term.substring(1) : term;
        List<PullRequest> rows = workspaceHosting.cachedAttention(workspaceId).stream()
                .filter(row -> term.isEmpty() || row.title().toLowerCase(Locale.ROOT).contains(term) || Integer.toString(row.number()).equals(number))
                .collect(toImmutableList());
        int start = Math.min(Math.multiplyExact(page, pageSize), rows.size());
        int end = Math.min(start + pageSize, rows.size());
        return new PullRequestPage(rows.subList(start, end), page, pageSize, rows.size(), rows.stream().filter(row -> "open".equals(row.state())).count(), end < rows.size());
    }

    private PullRequestRead requestRead(HostingReadSession session, String repo, int number)
    {
        var repository = connections.findRepository(session, repo).orElseGet(() -> connections.resolveRepository(session, repo));
        return new PullRequestRead(session, repository, number, connections.account(session).login());
    }

    void prepareRead(PullRequestRead route)
    {
        findStoredId(route).ifPresent(id -> snapshots.prepareScope(id, route.scope()));
    }

    private HostingPullRequestSnapshot refreshMetadata(PullRequestRead route, boolean force)
    {
        try {
            return requests.refresh(route.session(), route.repository().id(), route.number(), force);
        }
        catch (HostingFailure failure) {
            if (PullRequestSnapshotService.inaccessible(failure)) {
                findStoredId(route).ifPresent(snapshots::clearRemote);
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
        return refreshPullRequestDetailFromGitHub(prepareRead(repo, number));
    }

    DetailRefresh refreshPullRequestDetailFromGitHub(PullRequestRead route)
    {
        return refreshDetailOnce(route, RefreshPolicy.detail());
    }

    /** The background warm refresh, which nobody is waiting for. */
    DetailRefresh refreshPullRequestDetailFromGitHub(String repo, int number, Duration freshness)
    {
        return refreshPullRequestDetailFromGitHub(prepareRead(repo, number), freshness);
    }

    DetailRefresh refreshPullRequestDetailFromGitHub(PullRequestRead route, Duration freshness)
    {
        return refreshDetailOnce(route, RefreshPolicy.background(freshness));
    }

    DetailRefresh refreshOpenedPullRequestDetailFromGitHub(String repo, int number)
    {
        return refreshOpenedPullRequestDetailFromGitHub(prepareRead(repo, number));
    }

    DetailRefresh refreshOpenedPullRequestDetailFromGitHub(PullRequestRead route)
    {
        return refreshDetailOnce(route, RefreshPolicy.opened());
    }

    DetailRefresh refreshPullRequestChecksFromGitHub(String repo, int number)
    {
        return refreshPullRequestChecksFromGitHub(prepareRead(repo, number));
    }

    DetailRefresh refreshPullRequestChecksFromGitHub(PullRequestRead route)
    {
        return refreshDetailOnce(route, RefreshPolicy.checks());
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(String repo, int number)
    {
        return refreshAllPullRequestDetailFromGitHub(prepareRead(repo, number));
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(PullRequestRead route)
    {
        return refreshDetailOnce(route, RefreshPolicy.forced()).detail();
    }

    private DetailRefresh refreshDetailOnce(PullRequestRead route, RefreshPolicy policy)
    {
        prepareRead(route);
        PullRequestRef ref = route.ref();
        DetailKey key = new DetailKey(parseRef(ref.repoFullName().toLowerCase(Locale.ROOT), ref.number()), route.scope());
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
                return refreshDetailOnce(route, policy);
            }
            return result;
        }
        try {
            DetailRefresh result = refreshDetailGroups(route, policy);
            work.result().complete(result);
            return result;
        }
        catch (RuntimeException | Error failure) {
            work.result().completeExceptionally(failure);
            throw failure;
        }
        finally {
            detailRefreshes.remove(key, work);
        }
    }

    private DetailRefresh refreshDetailGroups(PullRequestRead route, RefreshPolicy policy)
    {
        PullRequestRef ref = route.ref();
        String login = route.login();
        Optional<PullRequest> previousRow = findStored(route);
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
        Optional<HostingPullRequestSnapshot> cachedMetadata = requests.cached(route.session(), route.repository().id(), route.number());
        boolean metadataDue = missing || policy.force() || metadataLag || missingHead || cachedMetadata.isEmpty()
                || (!policy.checksOnly() && (missingCoordinates || (timed && expired(times.metadataAt(), policy.freshness(), now))
                || cachedMetadata.flatMap(HostingPullRequestSnapshot::etag).isEmpty()));
        PullRequest row = previousRow.orElse(null);
        StoredPrDetail detail = previous.orElse(null);
        boolean full = missing;
        if (metadataDue) {
            HostingPullRequestSnapshot fetched = refreshMetadata(route, policy.force() || missingCoordinates || missingHead);
            FetchedPullRequest observed = fetched.pullRequest().withViewerOrigin(login);
            if (route.session().access().server().provider() == GITHUB && observed.origin() != AUTHORED && notifications.reviewRequests(login).contains(ref)) {
                observed = observed.withOrigin(REVIEW_REQUESTED);
            }
            row = project(route, observed);
            PrRawDetail raw = fetched.detail();
            full |= previous.filter(value -> !value.raw().equals(raw)).isPresent();
            boolean viewerCanWrite = detailFetcher.viewerCanWrite(route);
            detail = snapshots.saveMetadata(row.id(), raw, viewerCanWrite, route);
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
            Optional<DiscussionProbe> probe = policy.warm() && quiet
                    ? detailFetcher.probeDiscussion(route, details.findDiscussionEtags(current.id())) : Optional.empty();
            if (probe.filter(DiscussionProbe::unchanged).isPresent()) {
                detail = snapshots.saveDiscussion(current.id(), detail, sourceAt, route);
            }
            else {
                StoredPrDetail fetched = PullRequestDetailFetcher.await(detailFetcher.fetchDiscussion(route, detail));
                detail = snapshots.saveDiscussion(current.id(), fetched, sourceAt, route);
                // Taken before the refetch, these ETags can only be older than what was saved; a partial save keeps none.
                boolean complete = fetched.reviewsComplete() && fetched.timelineComplete();
                probe.ifPresent(asked -> details.updateDiscussionEtags(current.id(), complete ? asked.etags() : ImmutableMap.of()));
                full = true;
            }
        }
        boolean checkChanged = current.ciStatus() != PullRequestDetailMapper.aggregateCiStatus(detail.checkRuns());
        // Checks still running when a pull request closed finish without any activity.
        if (missing || policy.force() || headChanged || checkChanged || !detail.checkRunsComplete()
                || ((timed || current.ciStatus() == PullRequestDetail.CiStatus.PENDING) && expired(times.checksAt(), policy.freshness(), now))) {
            try {
                detail = snapshots.refreshChecks(route, current.id());
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure) || failure instanceof HostingFailure || PullRequestSnapshotService.inaccessible(failure)) {
                    throw failure;
                }
                log.warn("Could not refresh checks for {}; retaining cached checks: {}", ref.fullName(), failure.getMessage());
            }
        }
        StoredPrDetail stored = detail;
        storeDetail(current, stored, login);
        // An edited description leaves no timeline event; the update that carried it dates the edit.
        Optional<Instant> describedAt = previous.filter(old -> !Objects.equals(old.raw().body(), stored.raw().body())).map(ignored -> stored.raw().updatedAt());
        Stream.of(stored.timelineComplete() ? attentionActivityAt(stored.timeline(), login) : Optional.<Instant>empty(), describedAt)
                .flatMap(Optional::stream).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(at -> store.observeAttentionActivity(current.id(), at));
        if ((!policy.checksOnly() || missing) && (policy.force() || !snapshots.defaultDiffMatches(current.id(), detail.raw()))) {
            syncDiffQuietly(route, current);
        }
        return new DetailRefresh(full,
                PullRequestDetailMapper.toPullRequestDetail(current.id(), ref.repoFullName(), ref.number(), stored, stored.viewerCanWrite()));
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
        parseRef(repo, number);
        return snapshots.getDiffFiles(storedPullRequestId(repo, number), baseSha, headSha);
    }

    public List<DiffFile> getPullRequestDiffFiles(PullRequestRead route, String baseSha, String headSha)
    {
        prepareRead(route);
        return snapshots.getDiffFiles(storedPullRequestId(route), baseSha, headSha);
    }

    /** Rejects a cached diff from another head before a review freezes it as evidence. */
    public List<DiffFile> getValidatedPullRequestDiffFiles(String repo, int number, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getValidatedDiffFiles(storedPullRequestId(repo, number), headSha);
    }

    public List<DiffFile> getValidatedPullRequestDiffFiles(PullRequestRead route, String headSha)
    {
        prepareRead(route);
        return snapshots.getValidatedDiffFiles(storedPullRequestId(route), headSha);
    }

    public List<DiffCommit> getPullRequestDiffCommits(String repo, int number)
    {
        parseRef(repo, number);
        return snapshots.getDiffCommits(storedPullRequestId(repo, number));
    }

    public List<DiffCommit> getPullRequestDiffCommits(PullRequestRead route)
    {
        prepareRead(route);
        return snapshots.getDiffCommits(storedPullRequestId(route));
    }

    /** Returns locally persisted file content without contacting GitHub. */
    public DiffFileContent getPullRequestDiffFileContent(String repo, int number, String path, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getFileContent(storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    public DiffFileContent getPullRequestDiffFileContent(PullRequestRead route, String path, String baseSha, String headSha)
    {
        prepareRead(route);
        return snapshots.getFileContent(storedPullRequestId(route), path, baseSha, headSha);
    }

    void refreshPullRequestDiffFromGitHub(String repo, int number, String baseSha, String headSha)
    {
        refreshPullRequestDiffFromGitHub(prepareRead(repo, number), baseSha, headSha);
    }

    void refreshPullRequestDiffFromGitHub(PullRequestRead route, String baseSha, String headSha)
    {
        snapshots.refreshDiff(route, storedPullRequestId(route), baseSha, headSha);
    }

    void refreshPullRequestDiffFileContentFromGitHub(String repo, int number, String path, String baseSha, String headSha)
    {
        refreshPullRequestDiffFileContentFromGitHub(prepareRead(repo, number), path, baseSha, headSha);
    }

    void refreshPullRequestDiffFileContentFromGitHub(PullRequestRead route, String path, String baseSha, String headSha)
    {
        snapshots.refreshFileContent(route, storedPullRequestId(route), path, baseSha, headSha);
    }

    public Posted createReviewComment(String repo, int number, InlineReviewComment comment)
    {
        return actions.createReviewComment(prepareRead(repo, number), comment);
    }

    public Posted createReviewComment(PullRequestRead route, InlineReviewComment comment)
    {
        return actions.createReviewComment(route, comment);
    }

    public PullRequestDetail commentOnPullRequest(String repo, int number, String body)
    {
        return actions.commentOnPullRequest(prepareRead(repo, number), body);
    }

    public PullRequestDetail commentOnPullRequest(PullRequestRead route, String body)
    {
        return actions.commentOnPullRequest(route, body);
    }

    public boolean toggleReaction(String repo, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        return actions.toggleReaction(prepareRead(repo, number), target, commentId, content, viewerHasReacted);
    }

    public boolean toggleReaction(PullRequestRead route, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        return actions.toggleReaction(route, target, commentId, content, viewerHasReacted);
    }

    public void updateTitle(String repo, int number, String title)
    {
        actions.updateTitle(prepareRead(repo, number), title);
    }

    public void updateTitle(PullRequestRead route, String title)
    {
        actions.updateTitle(route, title);
    }

    public void updateDescription(String repo, int number, String description)
    {
        actions.updateDescription(prepareRead(repo, number), description);
    }

    public void updateDescription(PullRequestRead route, String description)
    {
        actions.updateDescription(route, description);
    }

    public void closePullRequest(String repo, int number)
    {
        actions.closePullRequest(prepareRead(repo, number));
    }

    public void closePullRequest(PullRequestRead route)
    {
        actions.closePullRequest(route);
    }

    public void markReadyForReview(String repo, int number)
    {
        actions.markReadyForReview(prepareRead(repo, number));
    }

    public void markReadyForReview(PullRequestRead route)
    {
        actions.markReadyForReview(route);
    }

    public void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        actions.updateMetadata(prepareRead(repo, number), assignees, labels);
    }

    public void updateMetadata(PullRequestRead route, List<String> assignees, List<String> labels)
    {
        actions.updateMetadata(route, assignees, labels);
    }

    public void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        actions.updateReviewerRequest(prepareRead(repo, number), login, requested);
    }

    public void updateReviewerRequest(PullRequestRead route, String login, boolean requested)
    {
        actions.updateReviewerRequest(route, login, requested);
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        actions.replyToReviewComment(prepareRead(repo, number), commentId, body);
    }

    public void replyToReviewComment(PullRequestRead route, long commentId, String body)
    {
        actions.replyToReviewComment(route, commentId, body);
    }

    /** Edits a posted comment or review body on GitHub, then patches the cached copies of that body. */
    public PullRequestDetail editComment(String repo, int number, String target, long commentId, String body)
    {
        return actions.editComment(prepareRead(repo, number), target, commentId, body);
    }

    public PullRequestDetail editComment(PullRequestRead route, String target, long commentId, String body)
    {
        return actions.editComment(route, target, commentId, body);
    }

    /** The latest comment of each review thread whose author can push to the repository, read from GitHub now. */
    public Set<Long> reviewCommentsByPushers(String repo, int number)
    {
        return gitHub.latestReviewCommentsByPushers(credentialService.resolveGitHubToken(), parseRef(repo, number));
    }

    public void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        actions.setReviewThreadResolved(prepareRead(repo, number), rootCommentId, resolved);
    }

    public void setReviewThreadResolved(PullRequestRead route, long rootCommentId, boolean resolved)
    {
        actions.setReviewThreadResolved(route, rootCommentId, resolved);
    }

    /**
     * Submits a review, with any inline comments, for the given pull request on GitHub and
     * records a local reviewed state.
     */
    public Posted submitReview(String repo, int number, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        return actions.submitReview(prepareRead(repo, number), prId, event, body, comments, commitId);
    }

    public Posted submitReview(PullRequestRead route, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        return actions.submitReview(route, prId, event, body, comments, commitId);
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
        return store.findIdByRepoAndNumber(parseRef(repo, number).repoFullName(), number).flatMap(snapshots::findHeadSha);
    }

    public Optional<String> findHeadSha(PullRequestRead route)
    {
        prepareRead(route);
        return findStoredId(route).flatMap(snapshots::findHeadSha);
    }

    /** Merges directly, falling back to GitHub's merge queue when required. */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy, String headSha)
    {
        return actions.mergePullRequest(prepareRead(repo, number), prId, strategy, headSha);
    }

    public MergeResult mergePullRequest(PullRequestRead route, long prId, String strategy, String headSha)
    {
        return actions.mergePullRequest(route, prId, strategy, headSha);
    }

    public void removeFromMergeQueue(String repo, int number, boolean autoMerge)
    {
        actions.removeFromMergeQueue(prepareRead(repo, number), autoMerge);
    }

    public void removeFromMergeQueue(PullRequestRead route, boolean autoMerge)
    {
        actions.removeFromMergeQueue(route, autoMerge);
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

    private boolean syncDiffQuietly(PullRequestRead route, PullRequest pullRequest)
    {
        try {
            snapshots.refreshDefaultDiff(route, pullRequest.id());
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
        return store.findIdByRepoAndNumber(repo, number)
                .flatMap(prId -> snapshots.findDetail(repo, number, prId));
    }

    public Optional<PullRequest> findWorkspaceStored(String workspaceId, String repo, int number)
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        checkArgument(workspace.monitoredRepository().fullName().equalsIgnoreCase(repo), "Request belongs to another workspace repository");
        return workspace.isGitHub() ? findStored(repo, number)
                : workspaceHosting.cachedRepositoryId(workspaceId).flatMap(id -> store.findByHostingRepository(id, number));
    }

    public Optional<Long> findWorkspaceHostingRepositoryId(String workspaceId)
    {
        return workspaceHosting.cachedRepositoryId(workspaceId);
    }

    public Optional<PullRequestDetail> findWorkspacePullRequestDetail(String workspaceId, String repo, int number)
    {
        return findWorkspaceStored(workspaceId, repo, number).flatMap(row -> snapshots.findDetail(repo, number, row.id()));
    }

    public Optional<PullRequestDetail> findPullRequestDetail(PullRequestRead route)
    {
        prepareRead(route);
        return findStoredId(route).flatMap(id -> snapshots.findDetail(route.repository().fullPath(), route.number(), id));
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
