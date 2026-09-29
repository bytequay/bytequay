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
import com.bytequay.app.repository.github.GitHubMergeClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.AttentionPrRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.app.repository.github.GitHubPullRequestSummary;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.recent.RecentTouchRecorder;
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
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.app.utils.StringInputUtil.normalizeNames;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

@Service
public class PullRequestService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);
    private static final Duration STATUS_REFRESH_INTERVAL = Duration.ofMinutes(6);
    private static final int CLOSED_RETENTION_DAYS = 30;
    private static final int DETAIL_WARM_LIMIT = 30;
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final Set<String> REACTION_CONTENTS = ImmutableSet.of(
            "+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes");

    private final Map<String, AttentionPrRef> observedNotifications = new HashMap<>();
    private String summaryCredential;

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubPullRequestWriteClient pullRequestWrites;
    private final GitHubRepoClient repositories;
    private final GitHubMergeClient merges;
    private final GitHubAccountClient accounts;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PullRequestDetailFetcher detailFetcher;
    private final PrViewStateStore viewStateStore;
    private final RecentTouchRecorder recent;
    private final GitHubPullRequestSummaryClient summaries;
    private final WorkspaceStore workspaces;
    private final CredentialService credentialService;
    private final WorkspaceMetadataService metadata;
    private final ApplicationContext applicationContext;
    @Autowired
    public PullRequestService(
            GitHubPullRequestReadClient gitHub,
            GitHubPullRequestWriteClient pullRequestWrites,
            GitHubRepoClient repositories,
            GitHubMergeClient merges,
            GitHubAccountClient accounts,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PullRequestDetailFetcher detailFetcher,
            PrViewStateStore viewStateStore,
            RecentTouchRecorder recent,
            CredentialService credentialService,
            WorkspaceMetadataService metadata,
            GitHubPullRequestSummaryClient summaries,
            WorkspaceStore workspaces,
            ApplicationContext applicationContext)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.detailFetcher = requireNonNull(detailFetcher, "detailFetcher is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.summaries = requireNonNull(summaries, "summaries is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.applicationContext = requireNonNull(applicationContext, "applicationContext is null");
    }

    public PullRequestPage listPullRequests(int page, int pageSize)
    {
        checkPage(page, pageSize);
        return store.findPage(page, pageSize);
    }

    public List<PullRequest> listAttentionCandidates()
    {
        return store.findAttentionCandidates();
    }

    /** Reads the stored row without contacting GitHub or marking the pull request viewed. */
    public Optional<PullRequest> findStored(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number).flatMap(store::findById);
    }

    /** Reads cached avatar URLs without loading full details or contacting GitHub. */
    public Map<Long, String> findCachedAuthorAvatarUrls(Set<Long> prIds)
    {
        return snapshots.findAuthorAvatarUrls(prIds);
    }

    public Map<Long, PrActivitySummary> findCachedLatestActivities(List<PullRequest> pullRequests)
    {
        Map<Long, PrActivitySummary> activities = snapshots.findLatestActivities(pullRequests.stream().map(PullRequest::id).collect(toImmutableSet()));
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
            if (anchor != null && pullRequest.headPushedAt() != null && pullRequest.headPushedAt().isAfter(anchor) && detailActivityIsCurrent(pullRequest)) {
                since.put(pullRequest.id(), anchor);
            }
        }
        return snapshots.findCommitsAfter(since.buildOrThrow());
    }

    /** Reads the names of the cached checks failing on each PR whose CI is failing. */
    public Map<Long, List<String>> findCachedFailingChecks(List<PullRequest> pullRequests)
    {
        Set<Long> failing = pullRequests.stream()
                .filter(pullRequest -> pullRequest.ciStatus() == PullRequestDetail.CiStatus.FAILING && detailActivityIsCurrent(pullRequest))
                .map(PullRequest::id)
                .collect(toImmutableSet());
        return snapshots.findCheckRuns(failing).entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> PullRequestDetailMapper.failingCheckNames(entry.getValue())));
    }

    private boolean detailActivityIsCurrent(PullRequest pullRequest)
    {
        // Summary-only changes must not attach old check names or commit lists to a fresh card.
        return pullRequest.activityAt() == null || snapshots.findDetailSyncedAt(pullRequest.repo(), pullRequest.number())
                .filter(at -> !at.isBefore(pullRequest.activityAt())).isPresent();
    }

    public PullRequest setMonitored(long prId, boolean monitored)
    {
        PullRequest row = store.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request not found"));
        if (monitored && row.monitoredAt() == null) {
            GitHubCredential credential = credentialService.resolveGitHubCredential();
            GitHubPullRequestSummary summary = summaries.fetchSummaries(
                            credential.token(), ImmutableList.of(parseRef(row.repo(), row.number())), credential.profile().login()).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not load pull request activity. Retry monitoring shortly."));
            // Establish the same lightweight baseline used by polling before enabling notifications.
            storeCardSummary(credential.token(), store.saveObserved(summary.pullRequest()), summary, credential.profile().login());
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

    /** Exact-fetches a newly created pull request so its workspace projection does not wait for GitHub search. */
    public void observeWorkspacePullRequest(RepoRef repository, int number)
    {
        requireNonNull(repository, "repository is null");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        PullRequestRef ref = PullRequestRef.of(repository.owner(), repository.repo(), number);
        FetchedPullRequest pullRequest = gitHub.getPullRequest(credential.token(), ref)
                .withViewerOrigin(credential.profile().login());
        store.saveRepositorySyncPage(
                repository.fullName(), ImmutableList.of(pullRequest), UUID.randomUUID().toString());
    }

    /** Discovers repository updates independently of the bounded full-detail warm set. */
    public void syncRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String syncMarker = UUID.randomUUID().toString();
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        Consumer<List<FetchedPullRequest>> save = page -> {
            List<FetchedPullRequest> rows = page.stream().map(row -> row.withViewerOrigin(credential.profile().login())).collect(toImmutableList());
            store.saveRepositorySyncPage(repository.fullName(), rows, syncMarker);
            rows.forEach(row -> seen.put(row.repo() + "#" + row.number(), row));
        };
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        discoverQuery(credential.token(), "is:pr repo:" + repository.fullName() + " updated:>=" + sinceDate, save);
        discoverQuery(credential.token(), "is:pr repo:" + repository.fullName() + " is:open", save);
        List<FetchedPullRequest> fresh = ImmutableList.copyOf(seen.values());
        List<PullRequestRef> refs = fresh.stream().map(pullRequest -> parseRef(pullRequest.repo(), pullRequest.number())).collect(toImmutableList());
        Set<PullRequestRef> reviewRequests = gitHub.fetchPrNotificationRefs(credential.token()).stream()
                .filter(AttentionPrRef::reviewRequested).map(AttentionPrRef::ref).collect(toImmutableSet());
        for (GitHubPullRequestSummary summary : fetchCardSummaries(credential.token(), refs, credential.profile().login())) {
            FetchedPullRequest fetched = summary.pullRequest();
            if (fetched.origin() != AUTHORED && reviewRequests.contains(parseRef(fetched.repo(), fetched.number()))) {
                fetched = fetched.withOrigin(REVIEW_REQUESTED);
            }
            PullRequest row = store.saveRepositorySyncPage(repository.fullName(), ImmutableList.of(fetched), syncMarker).getFirst();
            storeCardSummary(credential.token(), row, summary, credential.profile().login());
        }
        warmDetails(newestPerRepository(fresh));
        // Search can omit cached rows; discovery never deletes their data or local marks.
    }

    /** Discovers one task branch without a repository-wide search or detail refresh. */
    public void syncRepositoryBranch(RepoRef repository, String headOwner, String branch)
    {
        requireNonNull(repository, "repository is null");
        requireNotBlank(headOwner, "headOwner");
        requireNotBlank(branch, "branch");
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
                    .map(user -> new RepoUser(
                            user.login(),
                            user.avatarKey().map(avatarBaseUrl::concat).orElse(null),
                            user.bot()))
                    .collect(toImmutableList());
        }
        String pat = credentialService.resolveGitHubToken();
        return gitHub.fetchAssignableUsers(pat, repository);
    }

    public List<RepositoryLabel> listRepositoryLabels(String repo)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return repositories.fetchLabels(pat, RepoRef.of(ref.owner(), ref.repo()));
    }

    public boolean hasMergeQueue(String repo, String baseRef)
    {
        requireNotBlank(baseRef, "baseRef");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return merges.hasMergeQueue(pat, RepoRef.of(ref.owner(), ref.repo()), baseRef);
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

    /** Checks the newest personal card updates without warming full details. */
    public synchronized void syncAttentionSummaries()
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        if (!pat.equals(summaryCredential)) {
            summaryCredential = pat;
            observedNotifications.clear();
        }
        Map<String, FetchedPullRequest> seen = new HashMap<>();
        try {
            syncRecentAttentionQuery(pat, seen, "is:pr is:open author:@me", AUTHORED);
            String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
            syncRecentAttentionQuery(pat, seen, "is:pr is:closed author:@me updated:>=" + sinceDate, AUTHORED);
            syncRecentAttentionQuery(pat, seen, "is:pr is:open user-review-requested:@me", REVIEW_REQUESTED);
            syncRecentAttentionQuery(pat, seen, "is:pr is:open reviewed-by:@me", OTHER);
        }
        catch (RuntimeException failure) {
            if (!isRateLimit(failure)) {
                log.info("Attention summary search failed: {}", failure.getMessage());
            }
        }
        // Search and notification endpoints have separate rate-limit buckets.
        syncAttentionNotifications(pat, credential.profile().login(), seen);
    }

    private void syncRecentAttentionQuery(String pat, Map<String, FetchedPullRequest> seen, String query, PullRequest.Origin origin)
    {
        saveAttentionRows(gitHub.searchPullRequestsPaged(pat, query, 1, SEARCH_PAGE_SIZE).items(), seen, origin);
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

    private void syncAttentionNotifications(String pat, String currentLogin, Map<String, FetchedPullRequest> seen)
    {
        List<AttentionPrRef> notifications = gitHub.fetchPrNotificationRefs(pat);
        Set<String> eligible = notifications.stream().anyMatch(notification -> !notification.attentionRequested())
                ? store.findAttentionCandidates().stream().map(pullRequest -> pullRequest.repo() + "#" + pullRequest.number()).collect(toImmutableSet())
                : ImmutableSet.of();
        Set<String> current = new HashSet<>();
        for (AttentionPrRef attention : notifications) {
            PullRequestRef ref = attention.ref();
            String key = ref.fullName();
            current.add(key);
            if (!attention.attentionRequested() && !eligible.contains(key)) {
                continue;
            }
            if (attention.equals(observedNotifications.get(key))) {
                continue;
            }
            try {
                FetchedPullRequest pullRequest = seen.get(key);
                if (pullRequest == null || (attention.updatedAt() != null && attention.updatedAt().isAfter(pullRequest.updatedAt()))) {
                    pullRequest = gitHub.getPullRequest(pat, ref).withViewerOrigin(currentLogin);
                }
                if ("open".equals(pullRequest.state()) || eligible.contains(key)) {
                    store.saveObserved(attention.reviewRequested() && pullRequest.origin() != AUTHORED
                            ? pullRequest.withOrigin(REVIEW_REQUESTED)
                            : pullRequest);
                }
                observedNotifications.put(key, attention);
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                log.info("Attention summary PR {} fetch failed: {}", key, failure.getMessage());
            }
        }
        observedNotifications.keySet().retainAll(current);
    }

    /** Discovers all personal rows, then warms only the newest full snapshots per repository. */
    public void syncFromGitHub()
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        String login = credential.profile().login();
        Map<String, FetchedPullRequest> seen = new LinkedHashMap<>();
        syncAttentionQuery(pat, seen, "is:pr is:open author:@me", AUTHORED);
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        syncAttentionQuery(pat, seen, "is:pr is:closed author:@me updated:>=" + sinceDate, AUTHORED);
        syncAttentionQuery(pat, seen, "is:pr is:open user-review-requested:@me", REVIEW_REQUESTED);
        syncAttentionQuery(pat, seen, "is:pr is:open reviewed-by:@me", OTHER);
        List<FetchedPullRequest> fresh = ImmutableList.copyOf(seen.values());
        store.saveDashboardSyncPage(fresh, UUID.randomUUID().toString());
        Set<String> watched = workspaces.findAll().stream()
                .map(workspace -> workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT)).collect(toImmutableSet());
        List<FetchedPullRequest> personal = fresh.stream().filter(row -> !watched.contains(row.repo().toLowerCase(Locale.ROOT))).collect(toImmutableList());
        Set<PullRequestRef> refs = personal.stream()
                .map(pullRequest -> parseRef(pullRequest.repo(), pullRequest.number()))
                .collect(toCollection(LinkedHashSet::new));
        store.findAttentionCandidates().stream().filter(pullRequest -> pullRequest.monitoredAt() != null)
                .map(pullRequest -> parseRef(pullRequest.repo(), pullRequest.number())).forEach(refs::add);
        Set<PullRequestRef> reviewRequests = new HashSet<>();
        for (AttentionPrRef notification : gitHub.fetchPrNotificationRefs(pat)) {
            refs.add(notification.ref());
            if (notification.reviewRequested()) {
                reviewRequests.add(notification.ref());
            }
        }
        for (GitHubPullRequestSummary summary : fetchCardSummaries(pat, ImmutableList.copyOf(refs), login)) {
            FetchedPullRequest fetched = summary.pullRequest();
            if (fetched.origin() != AUTHORED && reviewRequests.contains(parseRef(fetched.repo(), fetched.number()))) {
                fetched = fetched.withOrigin(REVIEW_REQUESTED);
            }
            storeCardSummary(pat, store.saveObserved(fetched), summary, login);
        }
        warmDetails(newestPerRepository(personal));
    }

    private static List<FetchedPullRequest> newestPerRepository(List<FetchedPullRequest> rows)
    {
        Map<String, Integer> counts = new HashMap<>();
        return rows.stream()
                .sorted(Comparator.comparing(FetchedPullRequest::updatedAt).reversed().thenComparing(Comparator.comparingInt(FetchedPullRequest::number).reversed()))
                .filter(row -> counts.merge(row.repo().toLowerCase(Locale.ROOT), 1, Integer::sum) <= DETAIL_WARM_LIMIT)
                .collect(toImmutableList());
    }

    private List<GitHubPullRequestSummary> fetchCardSummaries(String pat, List<PullRequestRef> refs, String login)
    {
        ImmutableList.Builder<GitHubPullRequestSummary> fetched = ImmutableList.builder();
        for (int offset = 0; offset < refs.size(); offset += 30) {
            try {
                fetched.addAll(summaries.fetchSummaries(pat, refs.subList(offset, Math.min(offset + 30, refs.size())), login));
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                // Discovery already persisted. An unavailable enrichment batch must not hide newer cards.
                log.warn("GitHub PR card enrichment failed; keeping discovered updates: {}", failure.getMessage());
            }
        }
        return fetched.build();
    }

    void warmDetails(List<FetchedPullRequest> rows)
    {
        Instant freshAfter = Instant.now().minus(STATUS_REFRESH_INTERVAL);
        for (FetchedPullRequest row : rows) {
            Optional<Long> storedId = store.findIdByRepoAndNumber(row.repo(), row.number());
            Optional<Instant> updatedAt = storedId.flatMap(snapshots::findDetailUpdatedAt);
            boolean incomplete = storedId.filter(snapshots::detailIsIncomplete).isPresent();
            if (!incomplete && updatedAt.filter(at -> !at.isBefore(row.updatedAt())).isPresent()
                    && snapshots.findDetailSyncedAt(row.repo(), row.number()).filter(at -> at.isAfter(freshAfter)).isPresent()) {
                continue;
            }
            try {
                // Sequential PRs bound concurrency; the existing conditional path preserves detail and diff ETags.
                if (incomplete) {
                    refreshAllPullRequestDetailFromGitHub(row.repo(), row.number());
                }
                else {
                    refreshPullRequestDetailFromGitHub(row.repo(), row.number());
                }
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                log.warn("Could not warm PR {}#{}; retaining cached detail: {}", row.repo(), row.number(), failure.getMessage());
            }
        }
    }

    private void storeCardSummary(String pat, PullRequest row, GitHubPullRequestSummary summary, String login)
    {
        if (row.updatedAt().isAfter(summary.pullRequest().updatedAt())) {
            return;
        }
        PrViewState state = viewStateStore.findByPrIds(ImmutableSet.of(row.id())).get(row.id());
        List<PrReviewState> reviews = summary.viewerReview().map(ImmutableList::of).orElse(ImmutableList.of());
        reconcileHandledActionFromReviews(row.id(), reviews, login, state);
        state = viewStateStore.findByPrIds(ImmutableSet.of(row.id())).get(row.id());
        Instant pushedAt = summary.headCommittedAt().orElse(row.headPushedAt());
        if (row.headPushedAt() != null && (pushedAt == null || row.headPushedAt().isAfter(pushedAt))) {
            pushedAt = row.headPushedAt();
        }
        if (state != null && state.handledAction() != null && state.reviewedAt() != null) {
            boolean newHead = state.reviewedHeadSha() == null
                    ? pushedAt != null && pushedAt.isAfter(state.reviewedAt())
                    : !state.reviewedHeadSha().equals(summary.headSha());
            boolean requestedAgain = summary.reviewRequestedAt().isPresent() && summary.reviewRequestedAt().orElseThrow().isAfter(state.reviewedAt());
            boolean dismissed = summary.viewerReview().filter(review -> "DISMISSED".equals(review.state())).isPresent();
            if (newHead || requestedAgain || dismissed || hasUnseenAuthorReply(pat, row, summary, login, state.reviewedAt())) {
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
        try {
            return summaries.hasAuthorReplyAfter(pat, parseRef(row.repo(), row.number()), login, row.author(), reviewedAt, summary.activityVersion());
        }
        catch (RuntimeException failure) {
            if (!isRateLimit(failure)) {
                log.info("Could not check review replies for {}#{}: {}", row.repo(), row.number(), failure.getMessage());
            }
            return false;
        }
    }

    /** Opens a stored row, fetching its detail on a local miss. */
    public PullRequestDetail openPullRequestDetail(long prId)
    {
        PullRequestRef ref = store.findRefById(prId)
                .orElseThrow(() -> PullRequestSnapshotService.missing("Pull request"));
        return openPullRequestDetail(ref.repoFullName(), ref.number());
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
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        PullRequestRef ref = parseRef(repo, number);
        Optional<Long> storedId = store.findIdByRepoAndNumber(repo, number);
        Optional<String> etag = storedId.flatMap(snapshots::findDetailEtag);
        PullRequestFetch fetched = gitHub.getPullRequest(pat, ref, etag);
        if (fetched.pullRequest().isEmpty()) {
            StoredPrDetail detail = snapshots.refreshStatus(pat, ref, storedId.orElseThrow(), fetched);
            store.findById(storedId.orElseThrow()).ifPresent(row -> storeDetail(pat, row, detail, credential.profile().login()));
            return new DetailRefresh(false, PullRequestDetailMapper.toPullRequestDetail(
                    storedId.orElseThrow(), ref.repoFullName(), ref.number(), detail, detail.viewerCanWrite()));
        }
        return new DetailRefresh(true, storeFullRefresh(credential, ref, fetched));
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(String repo, int number)
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        PullRequestRef ref = parseRef(repo, number);
        PullRequestFetch fetched = gitHub.getPullRequest(credential.token(), ref, Optional.empty());
        return storeFullRefresh(credential, ref, fetched);
    }

    private PullRequestDetail storeFullRefresh(
            GitHubCredential credential,
            PullRequestRef ref,
            PullRequestFetch fetched)
    {
        String pat = credential.token();
        String currentLogin = credential.profile().login();
        ListenableFuture<StoredPrDetail> pending = detailFetcher.fetch(pat, ref);

        FetchedPullRequest observed = fetched.pullRequest().orElseThrow().withViewerOrigin(currentLogin);
        if (observed.origin() != AUTHORED && gitHub.fetchPrNotificationRefs(pat).stream()
                .anyMatch(notification -> notification.reviewRequested() && notification.ref().equals(ref))) {
            observed = observed.withOrigin(REVIEW_REQUESTED);
        }
        PullRequest pullRequest = store.saveObserved(observed);
        boolean viewerCanWrite = accounts.fetchViewerCanWrite(pat, RepoRef.of(ref.owner(), ref.repo()));

        StoredPrDetail detail = snapshots.saveDetail(pullRequest.id(), PullRequestDetailFetcher.await(pending), viewerCanWrite);
        storeDetail(pat, pullRequest, detail, currentLogin);
        boolean diffComplete = syncDiffQuietly(pat, pullRequest);
        // A PR 304 only refreshes status, so a failed diff must leave the next refresh unconditional.
        snapshots.updateDetailEtag(pullRequest.id(), diffComplete ? fetched.etag() : Optional.empty());
        return PullRequestDetailMapper.toPullRequestDetail(
                pullRequest.id(), ref.repoFullName(), ref.number(), detail, detail.viewerCanWrite());
    }

    /** Returns locally persisted diff files without contacting GitHub. */
    public List<DiffFile> getPullRequestDiffFiles(
            String repo, int number, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getDiffFiles(
                storedPullRequestId(repo, number), baseSha, headSha);
    }

    public List<DiffCommit> getPullRequestDiffCommits(String repo, int number)
    {
        parseRef(repo, number);
        return snapshots.getDiffCommits(storedPullRequestId(repo, number));
    }

    /** Returns locally persisted file content without contacting GitHub. */
    public DiffFileContent getPullRequestDiffFileContent(
            String repo, int number, String path, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getFileContent(
                storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    void refreshPullRequestDiffFromGitHub(
            String repo, int number, String baseSha, String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshDiff(
                pat, ref, storedPullRequestId(repo, number), baseSha, headSha);
    }

    void refreshPullRequestDiffFileContentFromGitHub(
            String repo,
            int number,
            String path,
            String baseSha,
            String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshFileContent(
                pat, ref, storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    public PullRequestDetail createReviewComment(String repo, int number, InlineReviewComment comment)
    {
        validateReviewComment(comment, true);
        String pat = credentialService.resolveGitHubToken();
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        PrTimelineEvent created = pullRequestWrites.createReviewComment(pat, parseRef(repo, number), comment);
        PullRequestDetail detail = patchCachedTimeline(repo, number, prId, created);
        prId.ifPresent(id -> markReviewed(id, "COMMENT"));
        recordComment(repo, number, "review-comment", created, comment.body());
        return detail;
    }

    public PullRequestDetail commentOnPullRequest(String repo, int number, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PrTimelineEvent created = pullRequestWrites.createIssueComment(pat, ref, body);
        PullRequestDetail detail = patchCachedTimeline(repo, number, store.findIdByRepoAndNumber(repo, number), created);
        recordComment(repo, number, "comment", created, body);
        return detail;
    }

    public boolean toggleReaction(
            String repo,
            int number,
            String target,
            Long commentId,
            String content,
            Boolean viewerHasReacted)
    {
        requireNotBlank(target, "reaction target must not be blank");
        requireNotBlank(content, "reaction content must not be blank");
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

    public void updateDescription(String repo, int number, String description)
    {
        pullRequestWrites.updatePullRequestDescription(
                credentialService.resolveGitHubToken(),
                parseRef(repo, number),
                requireNonNull(description, "description is null"));
        recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        invalidatePullRequestDetail(repo, number);
    }

    public void closePullRequest(String repo, int number)
    {
        pullRequestWrites.closePullRequest(
                credentialService.resolveGitHubToken(), parseRef(repo, number));
        recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        invalidatePullRequestCaches(repo, number);
    }

    public void markReadyForReview(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        String nodeId = merges.pullRequestNodeId(pat, parseRef(repo, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        try {
            pullRequestWrites.markReadyForReview(pat, nodeId);
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        }
        finally {
            // A lost response may still have promoted the draft; refresh without repeating the write.
            invalidatePullRequestDetail(repo, number);
        }
    }

    public void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        pullRequestWrites.updateIssueMetadata(
                credentialService.resolveGitHubToken(),
                parseRef(repo, number),
                normalizeNames(assignees, "assignees"),
                normalizeNames(labels, "labels"));
        recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        invalidatePullRequestDetail(repo, number);
    }

    public void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        requireNotBlank(login, "reviewer login must not be blank");
        pullRequestWrites.updateReviewerRequest(
                credentialService.resolveGitHubToken(), parseRef(repo, number), login.trim(), requested);
        recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        invalidatePullRequestDetail(repo, number);
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        requireNotBlank(body, "reply body must not be blank");
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.replyToReviewComment(pat, parseRef(repo, number), commentId, body);
        recent.pullRequestAction(repo, number, RecentReason.COMMENTED, 0);
        recent.wroteOnPullRequest(repo, number, RecentTextKind.COMMENT, "reply:" + commentId, body);
        invalidatePullRequestCaches(repo, number);
    }

    /** Edits a posted comment or review body on GitHub, then patches the cached copies of that body. */
    public PullRequestDetail editComment(String repo, int number, String target, long commentId, String body)
    {
        requireNotBlank(target, "comment target must not be blank");
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
        requireNotBlank(body, "comment body must not be blank");

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        switch (normalizedTarget) {
            case "issue_comment" -> pullRequestWrites.updateIssueComment(pat, ref, commentId, body);
            case "review_comment" -> pullRequestWrites.updateReviewComment(pat, ref, commentId, body);
            case "review" -> pullRequestWrites.updateReviewBody(pat, ref, commentId, body);
            default -> throw new IllegalStateException("unsupported comment target: " + normalizedTarget);
        }
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        PullRequestDetail detail = prId.flatMap(id -> snapshots.patchDetail(id, cached -> withEditedBody(cached, normalizedTarget, commentId, body)))
                .map(stored -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), repo, number, stored, stored.viewerCanWrite()))
                .orElseGet(() -> getPullRequestDetail(repo, number));
        recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
        recent.wroteOnPullRequest(repo, number, "review".equals(normalizedTarget) ? RecentTextKind.REVIEW_BODY : RecentTextKind.COMMENT, textKey, body);
        return detail;
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

    public void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        String threadId = gitHub.findReviewThreadId(pat, ref, rootCommentId);
        pullRequestWrites.setReviewThreadResolved(pat, threadId, resolved);
        invalidatePullRequestDetail(repo, number);
    }

    /**
     * Submits a review, with any inline comments, for the given pull request on GitHub and
     * records a local reviewed state.
     */
    public PullRequestDetail submitReview(
            String repo,
            int number,
            long prId,
            String event,
            String body,
            List<InlineReviewComment> comments)
    {
        requireNotBlank(event, "review event must not be blank");
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
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent) && reviewBody.isBlank() && reviewComments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review body or inline comment must not be blank");
        }

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PrReviewState created = pullRequestWrites.submitReview(
                pat, ref, normalizedEvent, reviewBody, reviewComments);
        // The review response carries the review alone, so the inline comments it
        // was submitted with have to be read back or they vanish from the diff
        // until the next poll.
        List<PrTimelineEvent> threads = reviewComments.isEmpty() || created.githubId() == null
                ? ImmutableList.of()
                : gitHub.fetchReviewComments(pat, ref, created.githubId());
        PullRequestDetail detail = patchCachedReview(repo, number, prId, created, threads);
        markReviewed(prId, normalizedEvent);
        recordReview(repo, number, normalizedEvent, reviewBody, created);
        reviewComments.forEach(inline -> recent.wroteOnPullRequest(
                repo,
                number,
                RecentTextKind.COMMENT,
                "review-comment:" + created.githubId() + ":" + inline.path() + ":" + inline.line(),
                inline.body()));
        return detail;
    }

    /**
     * Splices a submitted review, and any inline comments it carried, into the
     * cached detail. Approving cannot change the diff or the checks, so nothing
     * else is invalidated and the poll picks up whatever else moved.
     */
    private PullRequestDetail patchCachedReview(
            String repo,
            int number,
            long prId,
            PrReviewState created,
            List<PrTimelineEvent> threads)
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
            return new StoredPrDetail(
                    cached.raw(),
                    reviews,
                    timeline,
                    cached.checkRuns(),
                    cached.unavailableSections(),
                    cached.viewerCanWrite());
        });
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(
                        prId, repo, number, detail, detail.viewerCanWrite()))
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

    private void markReviewed(long prId, String event)
    {
        HandledAction action = switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        };
        viewStateStore.markReviewed(prId, action, currentHeadSha(prId), Instant.now());
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
        recent.wroteOnPullRequest(
                repo,
                number,
                RecentTextKind.REVIEW_BODY,
                "review:" + Objects.toString(created == null ? null : created.githubId(), "latest"),
                body);
    }

    /** Stable id for one written body, so re-posting cannot pile up duplicates. */
    private static String textKey(String kind, PrTimelineEvent created)
    {
        Long githubId = created == null ? null : created.githubId();
        return kind + ":" + (githubId != null ? githubId : Objects.toString(created == null ? null : created.timestamp(), "local"));
    }

    private String currentHeadSha(long prId)
    {
        return snapshots.findHeadSha(prId).orElse(null);
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
        requireNotBlank(comment.path(), "review comment path must not be blank");
        requireNotBlank(comment.body(), "review comment body must not be blank");
        requireNotBlank(comment.side(), "review comment side must not be blank");
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
            requireNotBlank(comment.commitId(), "review comment commit must not be blank");
        }
    }
    /** Merges directly, falling back to GitHub's merge queue when required. */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        if ("queue".equalsIgnoreCase(strategy)) {
            return enqueuePullRequest(pat, ref, repo, number);
        }
        MergePullRequestCommand command = strategyCommand(strategy);
        MergeResult result;
        try {
            result = merges.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                return enqueuePullRequest(pat, ref, repo, number);
            }
            throw e;
        }
        viewStateStore.markReviewed(prId, HandledAction.MERGED, currentHeadSha(prId), Instant.now());
        recent.pullRequestAction(repo, number, RecentReason.MERGED, 0);
        invalidatePullRequestDetail(repo, number);
        return result;
    }

    private MergeResult enqueuePullRequest(String pat, PullRequestRef ref, String repo, int number)
    {
        String nodeId = merges.pullRequestNodeId(pat, ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        MergeResult result = merges.enqueuePullRequest(pat, nodeId);
        // Queueing does not change the diff; retain it while the timeline refreshes.
        invalidatePullRequestDetail(repo, number);
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

    private static MergePullRequestCommand strategyCommand(String strategy)
    {
        if (strategy == null) {
            return MergePullRequestCommand.rebase();
        }
        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "squash" -> MergePullRequestCommand.squash();
            case "merge" -> MergePullRequestCommand.mergeCommit();
            default -> MergePullRequestCommand.rebase();
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    static RefreshMode refreshMode(
            PullRequest pullRequest,
            Instant previousUpdatedAt,
            Optional<Instant> detailUpdatedAt,
            Optional<Instant> detailSyncedAt,
            Instant now)
    {
        if (previousUpdatedAt == null
                || !Objects.equals(previousUpdatedAt, pullRequest.updatedAt())
                || !Objects.equals(detailUpdatedAt.orElse(null), pullRequest.updatedAt())
                || detailSyncedAt.isEmpty()) {
            return RefreshMode.FULL;
        }
        if (!"open".equalsIgnoreCase(pullRequest.state()) && pullRequest.monitoredAt() == null) {
            return RefreshMode.NONE;
        }
        return detailSyncedAt.orElseThrow().isAfter(now.minus(STATUS_REFRESH_INTERVAL))
                ? RefreshMode.NONE
                : RefreshMode.STATUS;
    }

    enum RefreshMode { FULL, STATUS, NONE }

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
                                || gitHub.fetchPrNotificationRefs(pat).stream().anyMatch(notification -> notification.reviewRequested()
                                        && notification.ref().equals(parseRef(pullRequest.repo(), pullRequest.number()))) ? REVIEW_REQUESTED : OTHER,
                detail.timeline().stream()
                        .filter(event -> "review_requested".equals(event.event()) && currentLogin.equalsIgnoreCase(event.payload().requestedReviewer()))
                        .map(PrTimelineEvent::timestamp)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()),
                detail.reviewsComplete() ? reviewDecision(detail.reviews()) : Optional.ofNullable(pullRequest.reviewDecision()),
                activityFingerprints(detail));
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
        Map<String, String> verdicts = new HashMap<>();
        reviews.stream()
                .sorted(Comparator.comparing(review -> Optional.ofNullable(review.submittedAt()).orElse(Instant.EPOCH)))
                .filter(review -> !"COMMENTED".equals(review.state()) && !"PENDING".equals(review.state()))
                .forEach(review -> verdicts.put(review.login().toLowerCase(Locale.ROOT), review.state()));
        if (verdicts.containsValue("CHANGES_REQUESTED")) {
            return Optional.of("CHANGES_REQUESTED");
        }
        return verdicts.containsValue("APPROVED") ? Optional.of("APPROVED") : Optional.empty();
    }

    private boolean syncDiffQuietly(String pat, PullRequest pullRequest)
    {
        try {
            snapshots.refreshDefaultDiff(
                    pat,
                    parseRef(pullRequest.repo(), pullRequest.number()),
                    pullRequest.id());
            return true;
        }
        catch (RuntimeException failure) {
            if (!isRateLimit(failure)) {
                log.warn("Failed to sync diff for PR {}/{}: {}",
                        pullRequest.repo(), pullRequest.number(), failure.getMessage());
            }
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

    /**
     * Pure-logic version of the review→handled-action mapping, exposed
     * package-private for unit tests. Returns the action implied by the
     * current user's latest review, or {@code null} if no review remains after
     * a dismissal.
     */
    static HandledAction deriveHandledActionFromReviews(List<PrReviewState> reviews, String currentLogin)
    {
        PrReviewState review = latestHandledReview(reviews, currentLogin);
        return review == null ? null : handledAction(review);
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

    static boolean needsReviewAgain(
            PullRequest pullRequest,
            StoredPrDetail detail,
            String currentLogin,
            PrViewState viewState)
    {
        if (viewState == null || viewState.handledAction() == null || viewState.reviewedAt() == null) {
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
        if (currentLogin != null && detail.raw().requestedReviewers().stream()
                .anyMatch(currentLogin::equalsIgnoreCase)
                && hasEventAfter(detail.timeline(), "review_requested", viewState.reviewedAt())) {
            return true;
        }
        if (currentLogin != null && pullRequest.author() != null
                && hasAuthorReplyAfter(detail.timeline(), currentLogin, pullRequest.author(), viewState.reviewedAt())) {
            return true;
        }
        return latestReviewState(detail.reviews(), currentLogin)
                .map("DISMISSED"::equalsIgnoreCase)
                .orElse(false);
    }

    private static boolean hasEventAfter(List<PrTimelineEvent> timeline, String event, Instant reviewedAt)
    {
        return timeline.stream().anyMatch(item -> event.equals(item.event())
                && item.timestamp() != null
                && item.timestamp().isAfter(reviewedAt));
    }

    private static boolean hasAuthorReplyAfter(
            List<PrTimelineEvent> timeline,
            String currentLogin,
            String author,
            Instant reviewedAt)
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
                .ifPresent(prId -> snapshots.updateDetailEtag(prId, Optional.empty()));
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(snapshots::delete);
    }

    private PullRequestDetail patchCachedTimeline(
            String repo,
            int number,
            Optional<Long> prId,
            PrTimelineEvent created)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = prId.flatMap(id -> snapshots.patchDetail(id, cached -> {
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            timeline.removeIf(event -> created.githubId() != null
                    && created.githubId().equals(event.githubId())
                    && created.event().equals(event.event()));
            timeline.add(created);
            return new StoredPrDetail(
                    cached.raw(),
                    cached.reviews(),
                    timeline,
                    cached.checkRuns(),
                    cached.unavailableSections(),
                    cached.viewerCanWrite());
        }));
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(
                        prId.orElseThrow(), repo, number, detail, detail.viewerCanWrite()))
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
