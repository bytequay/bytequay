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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubNotificationClient;
import com.bytequay.app.repository.github.GitHubNotificationRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.DiscussionProbe;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.app.repository.github.GitHubPullRequestSummary;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
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
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.StoredPrDetail;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
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
    private static final Set<String> REACTION_CONTENTS = ImmutableSet.of("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes");
    // Timeline events that stay in the pull request's history without bringing an acknowledged card back to Needs attention.
    private static final Set<String> QUIET_EVENTS = ImmutableSet.of(
            "committed", "head_ref_force_pushed", "labeled", "unlabeled", "review_dismissed", "convert_to_draft", "base_ref_changed", "base_ref_force_pushed",
            "automatic_base_change_succeeded", "automatic_base_change_failed", "auto_rebase_enabled", "head_ref_deleted", "head_ref_restored",
            "mentioned", "subscribed", "unsubscribed", "cross-referenced", "referenced");

    private final Map<PullRequestRef, SummaryStamp> summaryStamps = new ConcurrentHashMap<>();
    private String summaryCredential;
    private String lastDiscovery = "";
    private final Map<PullRequestRef, DetailWork> detailRefreshes = new ConcurrentHashMap<>();

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubPullRequestWriteClient pullRequestWrites;
    private final GitHubRepoClient repositories;
    private final GitHubAccountClient accounts;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PrDetailStore details;
    private final PullRequestDetailFetcher detailFetcher;
    private final PrViewStateStore viewStateStore;
    private final RecentTouchRecorder recent;
    private final GitHubPullRequestSummaryClient summaries;
    private final WorkspaceStore workspaces;
    private final CredentialService credentialService;
    private final WorkspaceMetadataService metadata;
    private final ApplicationContext applicationContext;
    private final GitHubNotificationClient notifications;
    private final AppSettingsStore syncSettings;
    private final WorkspaceIssueService issues;
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
            WorkspaceMetadataService metadata,
            GitHubPullRequestSummaryClient summaries,
            WorkspaceStore workspaces,
            ApplicationContext applicationContext,
            GitHubNotificationClient notifications,
            AppSettingsStore syncSettings,
            WorkspaceIssueService issues,
            Clock clock)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.details = requireNonNull(details, "details is null");
        this.detailFetcher = requireNonNull(detailFetcher, "detailFetcher is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.summaries = requireNonNull(summaries, "summaries is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.applicationContext = requireNonNull(applicationContext, "applicationContext is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.syncSettings = requireNonNull(syncSettings, "syncSettings is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public PullRequestPage listPullRequests(int page, int pageSize)
    {
        checkPage(page, pageSize);
        return store.findPage(page, pageSize);
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
        return findStored(ref.repoFullName(), ref.number()).orElseGet(() -> {
            GitHubCredential credential = credentialService.resolveGitHubCredential();
            return store.saveObserved(gitHub.getPullRequest(credential.token(), ref).withViewerOrigin(credential.profile().login()));
        });
    }

    /** Reads the cached description the same way, so it is empty until a detail read has stored the pull request once. */
    public Optional<String> findStoredDescription(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number).flatMap(details::find).map(detail -> Optional.ofNullable(detail.raw().body()).orElse(""));
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
        Map<Long, PrActivitySummary> activities = new HashMap<>(details.findLatestActivities(ids));
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
     * Reads the cached commits each PR received after the viewer's last review, or after the review
     * request when there was none, for the PRs whose head moved since; newest first.
     */
    public Map<Long, List<PrCommitLine>> findCachedCommitsSinceReview(List<PullRequest> pullRequests)
    {
        ImmutableMap.Builder<Long, Instant> since = ImmutableMap.builder();
        for (PullRequest pullRequest : pullRequests) {
            Instant anchor = pullRequest.reviewedAt() != null ? pullRequest.reviewedAt() : pullRequest.reviewRequestedAt();
            if (anchor != null && pullRequest.headPushedAt() != null && pullRequest.headPushedAt().isAfter(anchor)
                    && (pullRequest.activityAt() == null || activityIsCurrent(pullRequest, snapshots.findFreshness(pullRequest.id()).discussionAt()))) {
                since.put(pullRequest.id(), anchor);
            }
        }
        return details.findCommitsAfter(since.buildOrThrow());
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
        Set<Long> failing = pullRequests.stream()
                .filter(pullRequest -> pullRequest.ciStatus() == PullRequestDetail.CiStatus.FAILING)
                .filter(pullRequest -> pullRequest.activityAt() == null || activityIsCurrent(pullRequest, snapshots.findFreshness(pullRequest.id()).checksAt()))
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
        FetchedPullRequest pullRequest = gitHub.getPullRequest(credential.token(), ref)
                .withViewerOrigin(credential.profile().login());
        store.saveRepositorySyncPage(repository.fullName(), ImmutableList.of(pullRequest), UUID.randomUUID().toString());
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
        String syncMarker = UUID.randomUUID().toString();
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        Consumer<List<FetchedPullRequest>> save = page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(credential.profile().login())).collect(toImmutableList());
            store.saveRepositorySyncPage(repository.fullName(), rows, syncMarker);
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
            store.saveRepositorySyncPage(repository.fullName(), fresh, UUID.randomUUID().toString());
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
        checkText(baseRef, "baseRef");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return pullRequestWrites.hasMergeQueue(pat, RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    public String readCheckLog(String repo, int number, long jobId)
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
        store.findAttentionCandidates().stream()
                .filter(row -> row.monitoredAt() != null && "merged".equalsIgnoreCase(row.state()))
                .forEach(row -> store.setMonitored(row.id(), false));
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
                    log.info("Recent PR discovery deferred for {}: {}", query, failure.getMessage());
                }
            }
            if (ran) {
                lastDiscovery = query;
                break;
            }
        }
        seen.values().stream().map(row -> parseRef(row.repo(), row.number())).forEach(changed::add);
        store.findAttentionCandidates().stream()
                .filter(row -> {
                    SummaryStamp stamp = summaryStamps.get(parseRef(row.repo(), row.number()));
                    Instant source = store.findDiscussionActivityAt(row.id()).filter(at -> at.isAfter(row.updatedAt())).orElse(row.updatedAt());
                    return stamp == null || !stamp.credential().equals(pat) || source.isAfter(stamp.sourceAt())
                            || row.monitoredAt() != null || row.ciStatus() == PullRequestDetail.CiStatus.PENDING;
                })
                .map(row -> parseRef(row.repo(), row.number())).forEach(changed::add);
        enrichCards(credential, ImmutableList.copyOf(changed));
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
                store.saveRepositorySyncPage(query.substring("is:pr repo:".length()), rows, UUID.randomUUID().toString());
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
        FetchedPullRequest fetched = gitHub.getPullRequest(credential.token(), ref).withViewerOrigin(credential.profile().login());
        PullRequest row = store.saveObserved(notification.reviewRequested() && fetched.origin() != AUTHORED ? fetched.withOrigin(REVIEW_REQUESTED) : fetched);
        // Save the dirty marker before enrichment: a comment request failure cannot hide the notification.
        if (!"ci_activity".equals(notification.reason())) {
            store.observeActivity(row.id(), Optional.empty(), notification.updatedAt());
        }
        summaryStamps.remove(ref);
        changed.add(ref);
        try {
            notifications.activity(credential.token(), notification)
                    .ifPresent(activity -> store.observeActivity(row.id(), Optional.of(activity), notification.updatedAt()));
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
        store.saveDashboardSyncPage(fresh, UUID.randomUUID().toString());
        enrichCards(credential, fresh.stream().map(row -> parseRef(row.repo(), row.number())).collect(toImmutableList()));
    }

    /**
     * A restart forgets every stamp. Closed cards then keep what is stored, because discovery and
     * notifications report whatever changes them later; summarizing all of them again is minutes of
     * back-to-back GraphQL. Another account still summarizes everything.
     */
    private void baselineSummaries(String pat)
    {
        // Not the service monitor: a scan must not wait here for a whole pass of the summary stream.
        synchronized (summaryStamps) {
            if (pat.equals(summaryCredential)) {
                return;
            }
            summaryStamps.clear();
            if (summaryCredential == null) {
                // ponytail: a change saved but not summarized before the last shutdown stays stale until the next one; persist the stamp if that shows.
                Instant now = clock.instant();
                store.findAttentionCandidates().stream().filter(PullRequestService::settled)
                        .forEach(row -> summaryStamps.put(parseRef(row.repo(), row.number()), new SummaryStamp(pat, discussionSourceUpdatedAt(row), now)));
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

    // One stream: concurrent scans would summarize the same pull requests twice and together exceed GitHub's GraphQL execution allowance.
    private synchronized void enrichCards(GitHubCredential credential, List<PullRequestRef> candidates)
    {
        Instant now = clock.instant();
        List<PullRequestRef> refs = candidates.stream().distinct().filter(ref -> {
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
        Set<PullRequestRef> reviewRequests = notifications.reviewRequests(credential.profile().login());
        for (int offset = 0; offset < refs.size(); offset += GitHubPullRequestSummaryClient.BATCH_SIZE) {
            List<PullRequestRef> batch = refs.subList(offset, Math.min(offset + GitHubPullRequestSummaryClient.BATCH_SIZE, refs.size()));
            long started = System.nanoTime();
            try {
                // Persist each completed batch before asking for the next one.
                for (GitHubPullRequestSummary summary : summaries.fetchSummaries(credential.token(), batch, credential.profile().login())) {
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
                    summaryStamps.put(ref, new SummaryStamp(credential.token(), source, now));
                }
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                if (failure instanceof ResponseStatusException response && (response.getStatusCode().value() == 401
                        || response.getStatusCode().value() == 403 || response.getStatusCode().value() == 429)) {
                    throw failure;
                }
                log.warn("GitHub PR card enrichment failed for {} PRs; keeping discovered updates: {}", batch.size(), failure.getMessage());
            }
            if (offset + GitHubPullRequestSummaryClient.BATCH_SIZE < refs.size()) {
                try {
                    // GitHub's secondary limit allows about a minute of GraphQL execution per minute; resting as long as each batch ran leaves half of it free.
                    Thread.sleep(Duration.ofNanos(System.nanoTime() - started));
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
            if (isRateLimit(failure)) {
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

    /** Returns persisted detail, fetching and caching it on a local miss. */
    public PullRequestDetail getPullRequestDetail(String repo, int number)
    {
        parseRef(repo, number);
        return findPullRequestDetail(repo, number)
                .orElseGet(() -> refreshPullRequestDetailFromGitHub(repo, number).detail());
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
        PullRequestRef key = parseRef(ref.repoFullName().toLowerCase(Locale.ROOT), ref.number());
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
            return result;
        }
        try {
            DetailRefresh result = refreshDetailGroups(ref, policy);
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

    private DetailRefresh refreshDetailGroups(PullRequestRef ref, RefreshPolicy policy)
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
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
        boolean metadataDue = missing || policy.force() || metadataLag || missingHead
                || (!policy.checksOnly() && (missingCoordinates || (timed && expired(times.metadataAt(), policy.freshness(), now))
                || details.findEtag(previousRow.orElseThrow().id()).isEmpty()));
        PullRequest row = previousRow.orElse(null);
        StoredPrDetail detail = previous.orElse(null);
        boolean full = missing;
        if (metadataDue) {
            Optional<String> etag = policy.force() || missing || missingCoordinates || missingHead ? Optional.empty() : details.findEtag(previousRow.orElseThrow().id());
            PullRequestFetch fetched = gitHub.getPullRequest(pat, ref, etag);
            PrRawDetail raw;
            if (fetched.pullRequest().isPresent()) {
                FetchedPullRequest observed = fetched.pullRequest().orElseThrow().withViewerOrigin(login);
                if (observed.origin() != AUTHORED && notifications.reviewRequests(login).contains(ref)) {
                    observed = observed.withOrigin(REVIEW_REQUESTED);
                }
                row = store.saveObserved(observed);
                raw = detailFetcher.raw(pat, ref, fetched);
                full = true;
            }
            else {
                raw = previous.orElseThrow().raw();
            }
            boolean viewerCanWrite = accounts.fetchViewerCanWrite(pat, RepoRef.of(ref.owner(), ref.repo()));
            detail = snapshots.saveMetadata(row.id(), raw, viewerCanWrite);
            // A metadata 304 must not restore an ETag invalidated by a concurrent user write.
            if (fetched.pullRequest().isPresent()) {
                details.updateEtag(row.id(), fetched.etag());
            }
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
                detail = snapshots.saveDiscussion(current.id(), detail, sourceAt);
            }
            else {
                StoredPrDetail fetched = PullRequestDetailFetcher.await(detailFetcher.fetchDiscussion(pat, ref, detail));
                detail = snapshots.saveDiscussion(current.id(), fetched, sourceAt);
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
                detail = snapshots.refreshChecks(pat, ref, current.id());
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                log.warn("Could not refresh checks for {}; retaining cached checks: {}", ref.fullName(), failure.getMessage());
            }
        }
        storeDetail(pat, current, detail, login);
        StoredPrDetail stored = detail;
        // An edited description leaves no timeline event; the update that carried it dates the edit.
        Optional<Instant> describedAt = previous.filter(old -> !Objects.equals(old.raw().body(), stored.raw().body())).map(ignored -> stored.raw().updatedAt());
        Stream.of(stored.timelineComplete() ? attentionActivityAt(stored.timeline(), login) : Optional.<Instant>empty(), describedAt)
                .flatMap(Optional::stream).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .ifPresent(at -> store.observeAttentionActivity(current.id(), at));
        if ((!policy.checksOnly() || missing) && (policy.force() || !snapshots.defaultDiffMatches(current.id(), detail.raw()))) {
            syncDiffQuietly(pat, current);
        }
        return new DetailRefresh(full, PullRequestDetailMapper.toPullRequestDetail(current.id(), ref.repoFullName(), ref.number(), detail, detail.viewerCanWrite()));
    }

    Instant discussionSourceUpdatedAt(PullRequest row)
    {
        return store.findDiscussionActivityAt(row.id()).filter(at -> at.isAfter(row.updatedAt())).orElse(row.updatedAt());
    }

    private static boolean expired(Optional<Instant> validatedAt, Duration freshness, Instant now)
    {
        return validatedAt.filter(at -> at.isAfter(now.minus(freshness))).isEmpty();
    }

    private static boolean newer(Instant sourceAt, Optional<Instant> validatedSource)
    {
        return sourceAt != null && validatedSource.filter(at -> !at.isBefore(sourceAt)).isEmpty();
    }

    private record DetailWork(RefreshPolicy policy, CompletableFuture<DetailRefresh> result) {}

    /** Returns locally persisted diff files without contacting GitHub. */
    public List<DiffFile> getPullRequestDiffFiles(String repo, int number, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getDiffFiles(storedPullRequestId(repo, number), baseSha, headSha);
    }

    public List<DiffCommit> getPullRequestDiffCommits(String repo, int number)
    {
        parseRef(repo, number);
        return snapshots.getDiffCommits(storedPullRequestId(repo, number));
    }

    /** Returns locally persisted file content without contacting GitHub. */
    public DiffFileContent getPullRequestDiffFileContent(String repo, int number, String path, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getFileContent(storedPullRequestId(repo, number), path, baseSha, headSha);
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
        validateReviewComment(comment, true);
        String pat = credentialService.resolveGitHubToken();
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        PullRequestDetail before = getPullRequestDetail(repo, number);
        if (comment.replyToCommentId().isPresent()) {
            replyToReviewComment(repo, number, comment.replyToCommentId().getAsLong(), comment.body());
            return new Posted(afterWrite(repo, number, () -> getPullRequestDetail(repo, number), before), OptionalLong.empty(), ImmutableList.of());
        }
        PrTimelineEvent created = pullRequestWrites.createReviewComment(pat, parseRef(repo, number), comment);
        PullRequestDetail detail = afterWrite(repo, number, () -> {
            PullRequestDetail patched = patchCachedTimeline(repo, number, prId, created);
            prId.ifPresent(id -> markReviewed(id, "COMMENT", comment.commitId()));
            recordComment(repo, number, "review-comment", created, comment.body());
            return patched;
        }, before);
        return new Posted(detail, OptionalLong.empty(), ImmutableList.of(created));
    }

    public PullRequestDetail commentOnPullRequest(String repo, int number, String body)
    {
        checkText(body, "comment body");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PullRequestDetail before = getPullRequestDetail(repo, number);
        PrTimelineEvent created = pullRequestWrites.createIssueComment(pat, ref, body);
        return afterWrite(repo, number, () -> {
            PullRequestDetail detail = patchCachedTimeline(repo, number, store.findIdByRepoAndNumber(repo, number), created);
            recordComment(repo, number, "comment", created, body);
            return detail;
        }, before);
    }

    /**
     * Runs a step after a GitHub write landed. Its failure must not fail the request: the caller would
     * take the write for failed and send it again. The next refresh repairs the local copy.
     *
     * <p>Writers read the detail they fall back to before writing, so a failed read sends nothing.
     */
    private static <T> T afterWrite(String repo, int number, Supplier<T> step, T fallback)
    {
        try {
            return step.get();
        }
        catch (RuntimeException failure) {
            log.warn("Wrote to {}#{} on GitHub but could not update the local copy: {}", repo, number, failure.toString());
            return fallback;
        }
    }

    private static void afterWrite(String repo, int number, Runnable step)
    {
        afterWrite(repo, number, () -> {
            step.run();
            return true;
        }, false);
    }

    public boolean toggleReaction(String repo, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        checkText(target, "reaction target");
        checkText(content, "reaction content");
        String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
        String normalizedContent = content.trim().toLowerCase(Locale.ROOT);
        if (!REACTION_CONTENTS.contains(normalizedContent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction content");
        }
        switch (normalizedTarget) {
            case "pull_request" -> {
                if (commentId != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pull request reaction must not include a comment id");
                }
            }
            case "issue_comment", "review_comment" -> {
                if (commentId == null || commentId <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment reaction requires a positive comment id");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction target");
        }

        PullRequestRef ref = parseRef(repo, number);
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        try {
            return switch (normalizedTarget) {
                case "pull_request" -> pullRequestWrites.togglePullRequestReaction(credential.token(), ref, normalizedContent, viewerHasReacted);
                case "issue_comment" -> pullRequestWrites.toggleIssueCommentReaction(credential.token(), ref, commentId, normalizedContent, viewerHasReacted);
                case "review_comment" -> pullRequestWrites.toggleReviewCommentReaction(credential.token(), ref, commentId, normalizedContent, viewerHasReacted);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction target");
            };
        }
        finally {
            invalidatePullRequestDetail(repo, number);
        }
    }

    public void updateTitle(String repo, int number, String title)
    {
        checkText(title, "pull request title");
        String normalizedTitle = title.trim();
        pullRequestWrites.updatePullRequestTitle(credentialService.resolveGitHubToken(), parseRef(repo, number), normalizedTitle);
        afterWrite(repo, number, () -> {
            store.findIdByRepoAndNumber(repo, number).ifPresent(prId -> {
                snapshots.patchDetail(prId, cached -> new StoredPrDetail(cached.raw().withTitle(normalizedTitle), cached.reviews(), cached.timeline(),
                        cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite()));
                store.updateTitle(prId, normalizedTitle);
            });
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    public void updateDescription(String repo, int number, String description)
    {
        pullRequestWrites.updatePullRequestDescription(credentialService.resolveGitHubToken(), parseRef(repo, number), requireNonNull(description, "description is null"));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    public void closePullRequest(String repo, int number)
    {
        pullRequestWrites.closePullRequest(credentialService.resolveGitHubToken(), parseRef(repo, number));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestCaches(repo, number);
        });
    }

    public void markReadyForReview(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, parseRef(repo, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        try {
            pullRequestWrites.markReadyForReview(pat, nodeId);
        }
        finally {
            // A lost response may still have promoted the draft; refresh without repeating the write.
            afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        }
        afterWrite(repo, number, () -> recent.pullRequestAction(repo, number, RecentReason.EDITED, 0));
    }

    public void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        pullRequestWrites.updateIssueMetadata(
                credentialService.resolveGitHubToken(),
                parseRef(repo, number),
                normalizeNames(assignees, "assignees"),
                normalizeNames(labels, "labels"));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    public void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        checkText(login, "reviewer login");
        pullRequestWrites.updateReviewerRequest(credentialService.resolveGitHubToken(), parseRef(repo, number), login.trim(), requested);
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        checkText(body, "reply body");
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.replyToReviewComment(pat, parseRef(repo, number), commentId, body);
        afterWrite(repo, number, () -> {
            invalidatePullRequestCaches(repo, number);
            recent.pullRequestAction(repo, number, RecentReason.COMMENTED, 0);
            recent.wroteOnPullRequest(repo, number, RecentTextKind.COMMENT, "reply:" + commentId, body);
        });
    }

    /** Edits a posted comment or review body on GitHub, then patches the cached copies of that body. */
    public PullRequestDetail editComment(String repo, int number, String target, long commentId, String body)
    {
        checkText(target, "comment target");
        String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
        // The keys the create paths index, so an edit replaces the written text instead of adding a copy.
        String textKey = switch (normalizedTarget) {
            case "issue_comment" -> "comment:" + commentId;
            case "review_comment" -> "review-comment:" + commentId;
            case "review" -> "review:" + commentId;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported comment target");
        };
        if (commentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment edit requires a positive comment id");
        }
        checkText(body, "comment body");

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        switch (normalizedTarget) {
            case "issue_comment" -> pullRequestWrites.updateIssueComment(pat, ref, commentId, body);
            case "review_comment" -> pullRequestWrites.updateReviewComment(pat, ref, commentId, body);
            case "review" -> pullRequestWrites.updateReviewBody(pat, ref, commentId, body);
            default -> throw new IllegalStateException("unsupported comment target: " + normalizedTarget);
        }
        Optional<PullRequestDetail> patched = afterWrite(repo, number, () -> {
            Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
            return prId.flatMap(id -> snapshots.patchDetail(id, cached -> withEditedBody(cached, normalizedTarget, commentId, body)))
                    .map(stored -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), repo, number, stored, stored.viewerCanWrite()));
        }, Optional.empty());
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            recent.wroteOnPullRequest(repo, number, "review".equals(normalizedTarget) ? RecentTextKind.REVIEW_BODY : RecentTextKind.COMMENT, textKey, body);
        });
        return patched.orElseGet(() -> getPullRequestDetail(repo, number));
    }

    /** GitHub ids are unique only within one resource type, so each target patches only its own kind of copy. */
    private static StoredPrDetail withEditedBody(StoredPrDetail cached, String target, Long commentId, String body)
    {
        List<PrReviewState> reviews = cached.reviews().stream()
                .map(review -> "review".equals(target) && commentId.equals(review.githubId()) ? withBody(review, body) : review)
                .collect(toImmutableList());
        List<PrTimelineEvent> timeline = cached.timeline().stream()
                .map(event -> switch (target) {
                    case "issue_comment" -> "commented".equals(event.event()) && commentId.equals(event.githubId()) ? withBody(event, body) : event;
                    case "review" -> "reviewed".equals(event.event()) && commentId.equals(event.githubId()) ? withBody(event, body) : event;
                    case "review_comment" -> "review_thread".equals(event.event()) ? withThreadCommentBody(event, commentId, body) : event;
                    default -> throw new IllegalStateException("unsupported comment target: " + target);
                })
                .collect(toImmutableList());
        return new StoredPrDetail(cached.raw(), reviews, timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
    }

    private static PrReviewState withBody(PrReviewState review, String body)
    {
        return new PrReviewState(
                review.githubId(), review.login(), review.avatarUrl(), review.bot(), review.state(), review.submittedAt(), body, review.htmlUrl(), review.commitId());
    }

    private static PrTimelineEvent withBody(PrTimelineEvent event, String body)
    {
        return new PrTimelineEvent(event.githubId(), event.event(), event.actor(), event.timestamp(), body, event.payload(), event.localEventKey());
    }

    private static PrTimelineEvent withThreadCommentBody(PrTimelineEvent thread, Long commentId, String body)
    {
        List<PrTimelineComment> comments = thread.payload().comments().stream()
                .map(comment -> commentId.equals(comment.githubId())
                        ? new PrTimelineComment(
                                comment.githubId(), comment.actor(), comment.actorAvatarUrl(), comment.actorBot(), comment.timestamp(),
                                body, comment.htmlUrl(), comment.reviewId(), comment.inReplyToId(), comment.reactions())
                        : comment)
                .collect(toImmutableList());
        return new PrTimelineEvent(
                thread.githubId(),
                thread.event(),
                thread.actor(),
                thread.timestamp(),
                thread.body(),
                PrTimelinePayload.builder(thread.payload()).comments(comments).build(),
                thread.localEventKey());
    }

    /** The latest comment of each review thread whose author can push to the repository, read from GitHub now. */
    public Set<Long> reviewCommentsByPushers(String repo, int number)
    {
        return gitHub.latestReviewCommentsByPushers(credentialService.resolveGitHubToken(), parseRef(repo, number));
    }

    public void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        String threadId = gitHub.findReviewThreadId(pat, ref, rootCommentId);
        pullRequestWrites.setReviewThreadResolved(pat, threadId, resolved);
        afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
    }

    /**
     * Submits a review, with any inline comments, for the given pull request on GitHub and
     * records a local reviewed state.
     */
    public Posted submitReview(String repo, int number, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        checkText(commitId, "review commit");
        checkText(event, "review event");
        String normalizedEvent = event.toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("COMMENT", "APPROVE", "REQUEST_CHANGES").contains(normalizedEvent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review event");
        }
        List<InlineReviewComment> reviewComments = comments == null ? ImmutableList.of() : comments;
        reviewComments.forEach(comment -> validateReviewComment(comment, false));
        // GitHub anchors a whole review at one commit and resolves every comment's path and line
        // against it, so a mixed batch would land on the wrong lines — or, for a file that commit
        // does not have yet, be rejected outright. Callers split such a batch into one review each.
        if (reviewComments.stream().map(InlineReviewComment::commitId).filter(commit -> commit != null && !commit.isBlank()).distinct().count() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "one review carries comments written on one commit");
        }
        if (reviewComments.stream().filter(comment -> comment.replyToCommentId().isEmpty())
                .anyMatch(comment -> !commitId.equals(comment.commitId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comments must match the review commit");
        }
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent) && reviewBody.isBlank() && reviewComments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review body or inline comment must not be blank");
        }

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        Map<Long, String> replyThreads = new LinkedHashMap<>();
        reviewComments.stream().map(InlineReviewComment::replyToCommentId).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).distinct()
                .forEach(commentId -> replyThreads.put(commentId, gitHub.findReviewThreadId(pat, ref, commentId)));
        PullRequestDetail before = getPullRequestDetail(repo, number);
        PrReviewState created = replyThreads.isEmpty()
                ? pullRequestWrites.submitReview(pat, ref, normalizedEvent, reviewBody, reviewComments, commitId)
                : pullRequestWrites.submitReviewWithReplies(pat, ref, normalizedEvent, reviewBody, reviewComments, replyThreads, commitId);
        // The review response carries the review alone, so the inline comments it
        // was submitted with have to be read back or they vanish from the diff
        // until the next poll.
        List<PrTimelineEvent> threads = reviewComments.isEmpty() || created.githubId() == null
                ? ImmutableList.of()
                : afterWrite(repo, number, () -> replyThreads.isEmpty() ? gitHub.fetchReviewComments(pat, ref, created.githubId()) : gitHub.fetchPrReviewThreads(pat, ref),
                        ImmutableList.of());
        PullRequestDetail detail = afterWrite(repo, number, () -> {
            PullRequestDetail patched = patchCachedReview(repo, number, prId, created, threads);
            markReviewed(prId, normalizedEvent, commitId);
            recordReview(repo, number, normalizedEvent, reviewBody, created);
            reviewComments.forEach(inline -> recent.wroteOnPullRequest(
                    repo,
                    number,
                    RecentTextKind.COMMENT,
                    "review-comment:" + created.githubId() + ":" + inline.path() + ":" + inline.line(),
                    inline.body()));
            return patched;
        }, before);
        return new Posted(detail, created.githubId() == null ? OptionalLong.empty() : OptionalLong.of(created.githubId()), threads);
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

    /**
     * Splices a submitted review, and any inline comments it carried, into the
     * cached detail. Approving cannot change the diff or the checks, so nothing
     * else is invalidated and the poll picks up whatever else moved.
     */
    private PullRequestDetail patchCachedReview(String repo, int number, long prId, PrReviewState created, List<PrTimelineEvent> threads)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = snapshots.patchDetail(prId, cached -> {
            List<PrReviewState> reviews = new ArrayList<>(cached.reviews());
            reviews.removeIf(review -> Objects.equals(review.githubId(), created.githubId()));
            reviews.add(created);
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            PrTimelineEvent reviewed = reviewedTimelineEvent(created);
            timeline.removeIf(event -> Objects.equals(event.githubId(), reviewed.githubId())
                    && reviewed.event().equals(event.event()));
            timeline.add(reviewed);
            for (PrTimelineEvent thread : threads) {
                timeline.removeIf(event -> Objects.equals(event.githubId(), thread.githubId())
                        && thread.event().equals(event.event()));
                timeline.add(thread);
            }
            return new StoredPrDetail(cached.raw(), reviews, timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        });
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId, repo, number, detail, detail.viewerCanWrite()))
                .orElseGet(() -> getPullRequestDetail(repo, number));
    }

    private static PrTimelineEvent reviewedTimelineEvent(PrReviewState review)
    {
        return new PrTimelineEvent(
                review.githubId(),
                "reviewed",
                review.login(),
                review.submittedAt(),
                review.body(),
                PrTimelinePayload.builder()
                        .actorAvatarUrl(review.avatarUrl())
                        .actorBot(review.bot())
                        .state(review.state())
                        .htmlUrl(review.htmlUrl())
                        .build(),
                Optional.empty());
    }

    private void markReviewed(long prId, String event, String commitId)
    {
        HandledAction action = switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        };
        viewStateStore.markReviewed(prId, action, commitId, Instant.now());
    }

    private void recordComment(String repo, int number, String kind, PrTimelineEvent created, String body)
    {
        recent.pullRequestAction(repo, number, RecentReason.COMMENTED, 0);
        recent.wroteOnPullRequest(repo, number, RecentTextKind.COMMENT, textKey(kind, created), body);
    }

    private void recordReview(String repo, int number, String event, String body, PrReviewState created)
    {
        RecentReason reason = switch (event) {
            case "APPROVE" -> RecentReason.APPROVED;
            case "REQUEST_CHANGES" -> RecentReason.CHANGES_REQUESTED;
            default -> RecentReason.COMMENTED;
        };
        recent.pullRequestAction(repo, number, reason, 0);
        recent.wroteOnPullRequest(repo, number, RecentTextKind.REVIEW_BODY, "review:" + Objects.toString(created == null ? null : created.githubId(), "latest"), body);
    }

    /** Stable id for one written body, so re-posting cannot pile up duplicates. */
    private static String textKey(String kind, PrTimelineEvent created)
    {
        Long githubId = created == null ? null : created.githubId();
        return kind + ":" + (githubId != null ? githubId : Objects.toString(created == null ? null : created.timestamp(), "local"));
    }

    /** The head SHA locally recorded for a pull request, without contacting GitHub. */
    public Optional<String> findHeadSha(String repo, int number)
    {
        return store.findIdByRepoAndNumber(parseRef(repo, number).repoFullName(), number).flatMap(snapshots::findHeadSha);
    }

    private static void validateReviewComment(InlineReviewComment comment, boolean requireCommit)
    {
        if (comment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment must not be null");
        }
        checkText(comment.body(), "review comment body");
        if (comment.replyToCommentId().isPresent()) {
            if (comment.replyToCommentId().getAsLong() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reply requires a positive comment id");
            }
            return;
        }
        checkText(comment.path(), "review comment path");
        checkText(comment.side(), "review comment side");
        String side = comment.side().toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("LEFT", "RIGHT").contains(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment side must be LEFT or RIGHT");
        }
        if (comment.line() < 1 || comment.startLine() != null && (comment.startLine() < 1 || comment.startLine() > comment.line())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid review comment line range");
        }
        if (comment.startLine() != null && !side.equalsIgnoreCase(comment.startSide())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment range must stay on one side");
        }
        if (requireCommit) {
            checkText(comment.commitId(), "review comment commit");
        }
    }
    /** Merges directly, falling back to GitHub's merge queue when required. */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy, String headSha)
    {
        checkText(headSha, "pull request head commit");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        if ("queue".equalsIgnoreCase(strategy)) {
            return enqueuePullRequest(pat, ref, repo, number, headSha);
        }
        MergePullRequestCommand command = strategyCommand(strategy, headSha);
        MergeResult result;
        try {
            result = pullRequestWrites.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                return enqueuePullRequest(pat, ref, repo, number, headSha);
            }
            throw e;
        }
        afterWrite(repo, number, () -> {
            if (result.merged()) {
                store.setMonitored(prId, false);
            }
            viewStateStore.markReviewed(prId, HandledAction.MERGED, headSha, Instant.now());
            recent.pullRequestAction(repo, number, RecentReason.MERGED, 0);
            invalidatePullRequestDetail(repo, number);
        });
        return result;
    }

    public void removeFromMergeQueue(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, parseRef(repo, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        try {
            pullRequestWrites.dequeuePullRequest(pat, nodeId);
        }
        finally {
            // A lost response may still have removed the entry; let refresh reconcile it.
            afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        }
        afterWrite(repo, number, () -> recent.pullRequestAction(repo, number, RecentReason.EDITED, 0));
    }

    private MergeResult enqueuePullRequest(String pat, PullRequestRef ref, String repo, int number, String headSha)
    {
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        MergeResult result = pullRequestWrites.enqueuePullRequest(pat, nodeId, headSha);
        // Queueing does not change the diff; retain it while the timeline refreshes.
        afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        return result;
    }

    /** True when a direct-merge rejection is GitHub requiring the change to
     *  go through the merge queue (HTTP 405 with a queue message). */
    private static boolean requiresMergeQueue(ResponseStatusException e)
    {
        return e.getStatusCode().value() == 405
                && e.getReason() != null
                && e.getReason().toLowerCase(Locale.ROOT).contains("merge queue");
    }

    private static MergePullRequestCommand strategyCommand(String strategy, String headSha)
    {
        String method = switch (strategy == null ? "rebase" : strategy.toLowerCase(Locale.ROOT)) {
            case "squash" -> "squash";
            case "merge" -> "merge";
            default -> "rebase";
        };
        return new MergePullRequestCommand(method, Optional.empty(), Optional.empty(), Optional.of(headSha));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void checkPage(int page, int pageSize)
    {
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
    }

    private void storeDetail(String pat, PullRequest pullRequest, StoredPrDetail detail, String currentLogin)
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
            if (isRateLimit(failure)) {
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
        if (existing != null && existing.handledAction() != null
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

    private void invalidatePullRequestDetail(String repo, int number)
    {
        // Keep the snapshot readable by a conditional refresh already in flight.
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(prId -> details.updateEtag(prId, Optional.empty()));
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(snapshots::delete);
    }

    private PullRequestDetail patchCachedTimeline(String repo, int number, Optional<Long> prId, PrTimelineEvent created)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = prId.flatMap(id -> snapshots.patchDetail(id, cached -> {
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            timeline.removeIf(event -> created.githubId() != null
                    && created.githubId().equals(event.githubId())
                    && created.event().equals(event.event()));
            timeline.add(created);
            return new StoredPrDetail(cached.raw(), cached.reviews(), timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        }));
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), repo, number, detail, detail.viewerCanWrite()))
                .orElseGet(() -> getPullRequestDetail(repo, number));
    }

    record DetailRefresh(boolean full, PullRequestDetail detail)
    {
        DetailRefresh
        {
            requireNonNull(detail, "detail is null");
        }
    }
}
