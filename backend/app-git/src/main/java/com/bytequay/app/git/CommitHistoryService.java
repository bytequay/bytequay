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
package com.bytequay.app.git;

import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryRouting.ResolvedCommitHistoryRef;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.repository.github.CommitAvatarSnapshot;
import com.bytequay.app.repository.github.CommitAvatarSnapshot.CommitAvatar;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitRefreshMetadata;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.bytequay.core.domain.CommitRefreshStatus.State.FAILED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/** Local-first application boundary for commit history. */
@Service
public class CommitHistoryService
{
    private static final Logger log = LoggerFactory.getLogger(CommitHistoryService.class);
    private static final int MAX_COMMITS = 300;
    private static final Duration AVATAR_REFRESH_INTERVAL = Duration.ofDays(1);

    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final GitBrowseService git;
    private final CommitHistoryStore store;
    private final CredentialService credentials;
    private final GitHubRepoClient gitHub;
    private final CherryPickPullRequestLookup pullRequests;
    private final AvatarCache avatars;
    private final Clock clock;
    private final ApplicationContext applicationContext;

    public CommitHistoryService(
            WorkspaceRepositoryService workspaces,
            WorkspaceProvisioningService provisioning,
            GitBrowseService git,
            CommitHistoryStore store,
            CredentialService credentials,
            GitHubRepoClient gitHub,
            CherryPickPullRequestLookup pullRequests,
            AvatarCache avatars,
            Clock clock,
            ApplicationContext applicationContext)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.git = requireNonNull(git, "git is null");
        this.store = requireNonNull(store, "store is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.avatars = requireNonNull(avatars, "avatars is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.applicationContext = requireNonNull(applicationContext, "applicationContext is null");
    }

    /**
     * Reads commits from the managed checkout and joins durable local enrichment.
     * This method never contacts a provider or starts refresh work.
     */
    public CommitHistorySnapshot listHistory(String workspaceId, CommitHistoryRef ref, int limit)
    {
        requireNonNull(ref, "ref is null");
        checkArgument(limit > 0, "limit must be positive");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        ResolvedCommitHistoryRef resolved = CommitHistoryRouting.resolve(context, ref);
        // Refresh metadata is committed after enrichment. Reading its token
        // first guarantees a concurrent completion causes one later reload.
        CommitRefreshStatus refresh = status(workspaceId);
        List<Commit> commits = git.log(context.repository(), resolved.gitRef(), Math.min(limit, MAX_COMMITS));
        Map<String, CommitEnrichment> enrichmentBySha = resolved.repositoryId().isEmpty() || !hasGitHubEnrichment(workspaceId)
                ? ImmutableMap.of()
                : store.findEnrichment(resolved.repositoryId().orElseThrow(), commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        ImmutableList.Builder<CommitHistoryItem> items = ImmutableList.builderWithExpectedSize(commits.size());
        for (Commit commit : commits) {
            items.add(item(commit, Optional.ofNullable(enrichmentBySha.get(commit.sha()))));
        }
        return new CommitHistorySnapshot(items.build(), refresh);
    }

    /** Fetches, enriches, and persists commits before returning. */
    public CommitRefreshStatus refresh(String workspaceId, List<CommitHistoryRef> refs)
    {
        return refresh(workspaceId, refs, Optional.empty());
    }

    /** Refreshes only if no manual or scheduled attempt made this workspace fresh while queued. */
    public CommitRefreshStatus refreshIfDue(String workspaceId, List<CommitHistoryRef> refs, Instant staleBefore)
    {
        requireNonNull(staleBefore, "staleBefore is null");
        return refresh(workspaceId, refs, Optional.of(staleBefore));
    }

    private CommitRefreshStatus refresh(String workspaceId, List<CommitHistoryRef> refs, Optional<Instant> staleBefore)
    {
        List<CommitHistoryRef> requested = validatedRefs(workspaceId, refs);
        requireNonNull(workspaceId, "workspaceId is null");
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            workspaces.getWorkspace(workspaceId);
            if (staleBefore.isPresent()) {
                CommitRefreshStatus current = status(workspaceId);
                if (!due(current, staleBefore.orElseThrow())) {
                    return current;
                }
            }
            return refreshLocked(workspaceId, requested);
        });
    }

    private List<CommitHistoryRef> validatedRefs(String workspaceId, List<CommitHistoryRef> refs)
    {
        requireNonNull(refs, "refs is null");
        List<CommitHistoryRef> requested = refs.stream().distinct().toList();
        checkArgument(!requested.isEmpty(), "refs is empty");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        requested.forEach(ref -> CommitHistoryRouting.resolve(context, ref));
        return requested;
    }

    /** Reads the last completed refresh outcome without Git or provider access. */
    public CommitRefreshStatus getRefreshStatus(String workspaceId)
    {
        workspaces.getWorkspace(workspaceId);
        return status(workspaceId);
    }

    private CommitHistoryItem item(Commit commit, Optional<CommitEnrichment> enrichment)
    {
        String avatarBaseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) applicationContext).getWebServer().getPort() + "/api/avatars/";
        Optional<Integer> pullRequestNumber = enrichment.flatMap(CommitEnrichment::pullRequestNumber)
                .or(commit::pullRequestNumber);
        return new CommitHistoryItem(
                commit.sha(),
                commit.title(),
                commit.body(),
                enrichment.flatMap(CommitEnrichment::authorLogin).orElse(commit.author()),
                enrichment.flatMap(CommitEnrichment::avatarKey).map(avatarBaseUrl::concat),
                enrichment.flatMap(CommitEnrichment::committerLogin).orElse(commit.committer()),
                enrichment.flatMap(CommitEnrichment::committerAvatarKey).map(avatarBaseUrl::concat),
                commit.authoredAt(),
                commit.committedAt(),
                pullRequestNumber,
                enrichment.flatMap(CommitEnrichment::pullRequestTitle));
    }

    private CommitRefreshStatus status(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return status(store.findRefreshMetadata(workspaceId));
    }

    private static CommitRefreshStatus status(CommitRefreshMetadata metadata)
    {
        return new CommitRefreshStatus(
                metadata.workspaceId(),
                metadata.error().isPresent() ? FAILED : IDLE,
                metadata.lastAttemptAt(),
                metadata.lastSucceededAt(),
                metadata.error());
    }

    private CommitRefreshStatus refreshLocked(String workspaceId, Collection<CommitHistoryRef> refs)
    {
        Instant attempt = clock.instant();
        try {
            synchronize(workspaceId, refs);
            CommitRefreshMetadata metadata = new CommitRefreshMetadata(workspaceId, Optional.of(attempt), Optional.of(clock.instant()), Optional.empty());
            store.saveRefreshMetadata(metadata);
            return status(metadata);
        }
        catch (RuntimeException failure) {
            return fail(workspaceId, attempt, failure);
        }
    }

    private static boolean due(CommitRefreshStatus status, Instant staleBefore)
    {
        boolean successStale = status.lastSucceededAt().map(value -> value.isBefore(staleBefore)).orElse(true);
        boolean attemptStale = status.lastAttemptAt().map(value -> value.isBefore(staleBefore)).orElse(true);
        return successStale && attemptStale;
    }

    private void synchronize(String workspaceId, Collection<CommitHistoryRef> requestedRefs)
    {
        WorkspaceCodeSyncResult synchronization = provisioning.synchronizeCode(workspaceId);
        checkState(synchronization.successful(), "managed repository synchronization failed");
        validateRemoteSources(requestedRefs, synchronization);
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        List<ResolvedCommitHistoryRef> refs = requestedRefs.stream().map(ref -> CommitHistoryRouting.resolve(context, ref)).toList();
        store.upsertEnrichment(enrichRefs(context, refs));
    }

    private static void validateRemoteSources(Collection<CommitHistoryRef> refs, WorkspaceCodeSyncResult synchronization)
    {
        refs.stream()
                .filter(ref -> ref.source() == Source.REMOTE)
                .map(ref -> ref.remote().orElseThrow())
                .filter(synchronization.failedRemoteSources()::contains)
                .findFirst()
                .ifPresent(remote -> {
                    throw new IllegalStateException("selected remote source synchronization failed: " + remote);
                });
    }

    private Collection<CommitEnrichment> enrichRefs(WorkspaceRepositoryContext context, Collection<ResolvedCommitHistoryRef> refs)
    {
        if (!hasGitHubEnrichment(context.workspaceId())) {
            // GitLab history comes from the checkout; this enrichment cache contains GitHub identities only.
            refs.forEach(ref -> git.log(context.repository(), ref.gitRef(), MAX_COMMITS));
            return ImmutableList.of();
        }
        String token = credentials.resolveGitHubToken(context.workspaceId());
        Instant now = clock.instant();
        Map<EnrichmentKey, CommitEnrichment> updates = new LinkedHashMap<>();
        for (ResolvedCommitHistoryRef ref : refs) {
            if (ref.repository().isEmpty()) {
                // A source without provider identity stays Git-only, but reading
                // the requested ref proves the synchronized branch exists.
                git.log(context.repository(), ref.gitRef(), MAX_COMMITS);
                continue;
            }
            enrich(context, ref, token, now).forEach(value -> updates.merge(new EnrichmentKey(value.repositoryId(), value.sha()), value, CommitHistoryService::merge));
        }
        return updates.values();
    }

    private boolean hasGitHubEnrichment(String workspaceId)
    {
        var server = workspaces.getWorkspace(workspaceId).hostingServer();
        return server == null || server.provider() == HostingProvider.GITHUB;
    }

    private List<CommitEnrichment> enrich(WorkspaceRepositoryContext context, ResolvedCommitHistoryRef ref, String token, Instant now)
    {
        long repositoryId = ref.repositoryId().orElseThrow();
        RepoRef repository = ref.repository().orElseThrow();
        List<Commit> commits = git.log(context.repository(), ref.gitRef(), MAX_COMMITS);
        Map<String, CommitEnrichment> existing = store.findEnrichment(repositoryId, commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        int avatarLimit = avatarScanLimit(commits, existing, now);
        Map<String, CommitAvatar> identities;
        if (avatarLimit > 0) {
            CommitAvatarSnapshot avatarSnapshot = gitHub.fetchCommitAvatarSnapshot(token, repository, commits.getFirst().sha(), avatarLimit);
            checkState(avatarSnapshot.succeeded(), "commit avatar synchronization failed");
            identities = avatarSnapshot.avatars().stream()
                    .collect(toImmutableMap(CommitAvatar::sha, Function.identity()));
        }
        else {
            identities = ImmutableMap.of();
        }
        Set<String> checkedAvatarShas = commits.subList(0, avatarLimit).stream().map(Commit::sha).collect(toImmutableSet());
        List<String> dueAssociations = commits.stream()
                .filter(commit -> CommitEnrichment.associationDue(commit.committedAt(), Optional.ofNullable(existing.get(commit.sha())), now))
                .map(Commit::sha)
                .toList();
        PullRequestLookupResult associationSnapshot = pullRequests.lookUpResult(token, repository, dueAssociations, ref.providerRef(), new ArrayList<>());
        checkState(associationSnapshot.failedShas().isEmpty(), "commit pull-request synchronization failed");
        Map<String, PullRequestAssociation> associations = associationSnapshot.associations().stream()
                .collect(toImmutableMap(PullRequestLookupResult.Association::sha, PullRequestLookupResult.Association::pullRequest));
        return commits.stream()
                .map(commit -> enrichment(
                        repositoryId,
                        commit,
                        Optional.ofNullable(existing.get(commit.sha())),
                        Optional.ofNullable(identities.get(commit.sha())),
                        Optional.ofNullable(associations.get(commit.sha())),
                        checkedAvatarShas.contains(commit.sha()),
                        dueAssociations.contains(commit.sha()),
                        now))
                .toList();
    }

    private static int avatarScanLimit(List<Commit> commits, Map<String, CommitEnrichment> existing, Instant now)
    {
        // GitHub paginates from the branch tip; stop after the oldest identity that needs a refresh.
        for (int index = commits.size() - 1; index >= 0; index--) {
            Optional<Instant> checkedAt = Optional.ofNullable(existing.get(commits.get(index).sha())).flatMap(CommitEnrichment::avatarCheckedAt);
            if (checkedAt.isEmpty() || !checkedAt.orElseThrow().isAfter(now.minus(AVATAR_REFRESH_INTERVAL))) {
                return index + 1;
            }
        }
        return 0;
    }

    private CommitEnrichment enrichment(
            long repositoryId,
            Commit commit,
            Optional<CommitEnrichment> existing,
            Optional<CommitAvatar> identity,
            Optional<PullRequestAssociation> association,
            boolean avatarChecked,
            boolean associationChecked,
            Instant now)
    {
        CommitEnrichment observed = new CommitEnrichment(
                repositoryId,
                commit.sha(),
                identity.flatMap(CommitAvatar::authorLogin),
                identity.flatMap(CommitAvatar::authorSourceUrl).flatMap(avatars::cache),
                identity.flatMap(CommitAvatar::committerLogin),
                identity.flatMap(CommitAvatar::committerSourceUrl).flatMap(avatars::cache),
                association.map(PullRequestAssociation::number),
                association.map(PullRequestAssociation::title).filter(value -> !value.isBlank()),
                association.map(PullRequestAssociation::url).filter(value -> !value.isBlank()),
                association.flatMap(PullRequestAssociation::mergedTime),
                avatarChecked ? Optional.of(now) : Optional.empty(),
                association.isPresent() || associationChecked ? Optional.of(now) : Optional.empty(),
                now);
        return existing.map(previous -> merge(previous, observed)).orElse(observed);
    }

    private static CommitEnrichment merge(CommitEnrichment first, CommitEnrichment second)
    {
        return new CommitEnrichment(
                second.repositoryId(),
                second.sha(),
                second.authorLogin().or(first::authorLogin),
                second.avatarKey().or(first::avatarKey),
                second.committerLogin().or(first::committerLogin),
                second.committerAvatarKey().or(first::committerAvatarKey),
                second.pullRequestNumber().or(first::pullRequestNumber),
                second.pullRequestTitle().or(first::pullRequestTitle),
                second.pullRequestUrl().or(first::pullRequestUrl),
                second.pullRequestMergedAt().or(first::pullRequestMergedAt),
                second.avatarCheckedAt().or(first::avatarCheckedAt),
                second.associationCheckedAt().or(first::associationCheckedAt),
                second.lastSeenAt());
    }

    private CommitRefreshStatus fail(String workspaceId, Instant attempt, RuntimeException failure)
    {
        String reference = UUID.randomUUID().toString();
        log.warn("Commit history refresh failed for workspace {} [{}]: {}", workspaceId, reference, failure.getMessage());
        CommitRefreshMetadata current = store.findRefreshMetadata(workspaceId);
        CommitRefreshMetadata failed = new CommitRefreshMetadata(
                workspaceId,
                Optional.of(attempt),
                current.lastSucceededAt(),
                Optional.of("Commit refresh failed. Retry later. Reference: " + reference));
        try {
            store.saveRefreshMetadata(failed);
        }
        catch (RuntimeException statusFailure) {
            log.warn("Could not persist failed commit refresh metadata for workspace {}: {}", workspaceId, statusFailure.getMessage());
        }
        return status(failed);
    }

    private record EnrichmentKey(long repositoryId, String sha) {}
}
