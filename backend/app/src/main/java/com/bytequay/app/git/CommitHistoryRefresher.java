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

import com.bytequay.app.cherrypick.CherryPickPullRequestLookup;
import com.bytequay.app.cherrypick.PullRequestAssociation;
import com.bytequay.app.cherrypick.PullRequestLookupResult;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryRouting.ResolvedCommitHistoryRef;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.repository.RepositoryEnrichmentService;
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
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
import java.util.function.Supplier;

import static com.bytequay.core.domain.CommitRefreshStatus.State.FAILED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/** Fetches and persists one commit refresh before returning. */
@Component
class CommitHistoryRefresher
{
    private static final Logger log = LoggerFactory.getLogger(CommitHistoryRefresher.class);
    private static final int MAX_COMMITS = 300;
    private static final Duration AVATAR_REFRESH_INTERVAL = Duration.ofDays(1);

    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final GitBrowseService git;
    private final CommitHistoryStore store;
    private final RepositoryEnrichmentService enrichment;
    private final CredentialService credentials;
    private final GitHubRepoClient gitHub;
    private final CherryPickPullRequestLookup pullRequests;
    private final AvatarCache avatars;
    private final Clock clock;

    CommitHistoryRefresher(
            WorkspaceRepositoryService workspaces,
            WorkspaceProvisioningService provisioning,
            GitBrowseService git,
            CommitHistoryStore store,
            RepositoryEnrichmentService enrichment,
            CredentialService credentials,
            GitHubRepoClient gitHub,
            CherryPickPullRequestLookup pullRequests,
            AvatarCache avatars,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.provisioning = requireNonNull(provisioning, "provisioning is null");
        this.git = requireNonNull(git, "git is null");
        this.store = requireNonNull(store, "store is null");
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.avatars = requireNonNull(avatars, "avatars is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    CommitRefreshStatus refresh(String workspaceId, Collection<CommitHistoryRef> refs)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(refs, "refs is null");
        return withWorkspaceLock(workspaceId, () -> refreshLocked(workspaceId, refs));
    }

    CommitRefreshStatus refreshIfDue(
            String workspaceId,
            Collection<CommitHistoryRef> refs,
            Instant staleBefore)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(refs, "refs is null");
        requireNonNull(staleBefore, "staleBefore is null");
        return withWorkspaceLock(workspaceId, () -> {
            CommitRefreshStatus current = status(workspaceId);
            return due(current, staleBefore) ? refreshLocked(workspaceId, refs) : current;
        });
    }

    CommitRefreshStatus status(String workspaceId)
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

    private CommitRefreshStatus withWorkspaceLock(String workspaceId, Supplier<CommitRefreshStatus> action)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            workspaces.getWorkspace(workspaceId);
            return action.get();
        });
    }

    private CommitRefreshStatus refreshLocked(String workspaceId, Collection<CommitHistoryRef> refs)
    {
        Instant attempt = clock.instant();
        try {
            synchronize(workspaceId, refs);
            CommitRefreshMetadata metadata = new CommitRefreshMetadata(
                    workspaceId, Optional.of(attempt), Optional.of(clock.instant()), Optional.empty());
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
        WorkspaceCodeSyncResult synchronization = refreshCode(workspaceId);
        validateRemoteSources(requestedRefs, synchronization);
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        List<ResolvedCommitHistoryRef> refs = resolveRefs(context, requestedRefs);
        enrichment.upsertCommits(enrichRefs(context, refs));
    }

    private WorkspaceCodeSyncResult refreshCode(String workspaceId)
    {
        WorkspaceCodeSyncResult synchronization = provisioning.synchronizeCode(workspaceId);
        if (!synchronization.successful()) {
            throw new IllegalStateException("managed repository synchronization failed");
        }
        return synchronization;
    }

    private static void validateRemoteSources(
            Collection<CommitHistoryRef> refs,
            WorkspaceCodeSyncResult synchronization)
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

    private static List<ResolvedCommitHistoryRef> resolveRefs(
            WorkspaceRepositoryContext context,
            Collection<CommitHistoryRef> refs)
    {
        return refs.stream()
                .map(ref -> CommitHistoryRouting.resolve(context, ref))
                .toList();
    }

    private Collection<CommitEnrichment> enrichRefs(
            WorkspaceRepositoryContext context,
            Collection<ResolvedCommitHistoryRef> refs)
    {
        String token = credentials.resolveGitHubToken();
        Instant now = clock.instant();
        Map<EnrichmentKey, CommitEnrichment> updates = new LinkedHashMap<>();
        for (ResolvedCommitHistoryRef ref : refs) {
            if (ref.repository().isEmpty()) {
                // A source without provider identity stays Git-only, but reading
                // the requested ref proves the synchronized branch exists.
                git.log(context.repository(), ref.gitRef(), MAX_COMMITS);
                continue;
            }
            enrich(context, ref, token, now).forEach(value -> updates.merge(
                    new EnrichmentKey(value.repositoryId(), value.sha()), value, CommitHistoryRefresher::merge));
        }
        return updates.values();
    }

    private List<CommitEnrichment> enrich(
            WorkspaceRepositoryContext context,
            ResolvedCommitHistoryRef ref,
            String token,
            Instant now)
    {
        long repositoryId = ref.repositoryId().orElseThrow();
        RepoRef repository = ref.repository().orElseThrow();
        List<Commit> commits = git.log(context.repository(), ref.gitRef(), MAX_COMMITS);
        Map<String, CommitEnrichment> existing = enrichment.findCommits(
                        repositoryId, commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        int avatarLimit = avatarScanLimit(commits, existing, now);
        Map<String, CommitAvatar> identities;
        if (avatarLimit > 0) {
            CommitAvatarSnapshot avatarSnapshot = gitHub.fetchCommitAvatarSnapshot(token, repository, commits.getFirst().sha(), avatarLimit);
            if (!avatarSnapshot.succeeded()) {
                throw new IllegalStateException("commit avatar synchronization failed");
            }
            identities = avatarSnapshot.avatars().stream()
                    .collect(toImmutableMap(CommitAvatar::sha, Function.identity()));
        }
        else {
            identities = ImmutableMap.of();
        }
        Set<String> checkedAvatarShas = commits.subList(0, avatarLimit).stream().map(Commit::sha).collect(toImmutableSet());
        List<String> dueAssociations = commits.stream()
                .filter(commit -> RepositoryEnrichmentService.associationDue(commit.committedAt(), Optional.ofNullable(existing.get(commit.sha())), now))
                .map(Commit::sha)
                .toList();
        PullRequestLookupResult associationSnapshot = pullRequests.lookUpResult(
                repository, dueAssociations, ref.providerRef(), new ArrayList<>());
        if (!associationSnapshot.failedShas().isEmpty()) {
            throw new IllegalStateException("commit pull-request synchronization failed");
        }
        Map<String, PullRequestAssociation> associations = associationSnapshot.associations().stream()
                .collect(toImmutableMap(
                        PullRequestLookupResult.Association::sha,
                        PullRequestLookupResult.Association::pullRequest));
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
        Optional<String> authorLogin = identity.flatMap(CommitAvatar::authorLogin)
                .or(() -> existing.flatMap(CommitEnrichment::authorLogin));
        Optional<String> avatarKey = identity.flatMap(CommitAvatar::authorSourceUrl).flatMap(avatars::cache)
                .or(() -> existing.flatMap(CommitEnrichment::avatarKey));
        Optional<String> committerLogin = identity.flatMap(CommitAvatar::committerLogin)
                .or(() -> existing.flatMap(CommitEnrichment::committerLogin));
        Optional<String> committerAvatarKey = identity.flatMap(CommitAvatar::committerSourceUrl).flatMap(avatars::cache)
                .or(() -> existing.flatMap(CommitEnrichment::committerAvatarKey));
        Optional<Integer> pullRequestNumber = association.map(PullRequestAssociation::number)
                .or(() -> existing.flatMap(CommitEnrichment::pullRequestNumber));
        Optional<String> pullRequestTitle = association.map(PullRequestAssociation::title)
                .filter(value -> !value.isBlank())
                .or(() -> existing.flatMap(CommitEnrichment::pullRequestTitle));
        Optional<String> pullRequestUrl = association.map(PullRequestAssociation::url)
                .filter(value -> !value.isBlank())
                .or(() -> existing.flatMap(CommitEnrichment::pullRequestUrl));
        Optional<Instant> associationCheckedAt = association.isPresent() || associationChecked
                ? Optional.of(now)
                : existing.flatMap(CommitEnrichment::associationCheckedAt);
        return new CommitEnrichment(
                repositoryId,
                commit.sha(),
                authorLogin,
                avatarKey,
                committerLogin,
                committerAvatarKey,
                pullRequestNumber,
                pullRequestTitle,
                pullRequestUrl,
                avatarChecked ? Optional.of(now) : existing.flatMap(CommitEnrichment::avatarCheckedAt),
                associationCheckedAt,
                now);
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
                second.avatarCheckedAt().or(first::avatarCheckedAt),
                second.associationCheckedAt().or(first::associationCheckedAt),
                second.lastSeenAt());
    }

    private CommitRefreshStatus fail(String workspaceId, Instant attempt, RuntimeException failure)
    {
        String reference = UUID.randomUUID().toString();
        log.warn("Commit history refresh failed for workspace {} [{}]: {}",
                workspaceId, reference, failure.getMessage());
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
            log.warn("Could not persist failed commit refresh metadata for workspace {}: {}",
                    workspaceId, statusFailure.getMessage());
        }
        return status(failed);
    }

    private record EnrichmentKey(long repositoryId, String sha) {}
}
