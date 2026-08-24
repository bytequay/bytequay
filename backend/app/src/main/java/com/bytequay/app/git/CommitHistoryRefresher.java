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
import com.bytequay.app.repository.github.CommitAvatarSnapshot;
import com.bytequay.app.repository.github.CommitAvatarSnapshot.CommitAvatar;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitRefreshMetadata;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.CommitHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.bytequay.core.domain.CommitRefreshStatus.State.FAILED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Fetches and persists one commit refresh before returning. */
@Component
class CommitHistoryRefresher
{
    private static final Logger log = LoggerFactory.getLogger(CommitHistoryRefresher.class);
    private static final int MAX_COMMITS = 300;
    private static final Duration NEGATIVE_RETRY_INTERVAL = Duration.ofDays(1);
    private static final Duration NEGATIVE_RETRY_WINDOW = Duration.ofDays(7);

    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceProvisioningService provisioning;
    private final GitBrowseService git;
    private final CommitHistoryStore store;
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

    private void synchronize(String workspaceId, Collection<CommitHistoryRef> requestedRefs)
    {
        WorkspaceCodeSyncResult synchronization = refreshCode(workspaceId);
        validateRemoteSources(requestedRefs, synchronization);
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        List<ResolvedCommitHistoryRef> refs = resolveRefs(context, requestedRefs);
        store.upsertEnrichment(enrichRefs(context, refs));
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
                // Extra remote sources have no provider identity, but reading
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
        Map<String, CommitEnrichment> existing = store.findEnrichment(
                        repositoryId, commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        CommitAvatarSnapshot avatarSnapshot = gitHub.fetchCommitAvatarSnapshot(
                token, repository, ref.providerRef(), MAX_COMMITS);
        if (!avatarSnapshot.succeeded()) {
            throw new IllegalStateException("commit avatar synchronization failed");
        }
        Map<String, String> avatarSources = avatarSnapshot.avatars().stream()
                .collect(toImmutableMap(CommitAvatar::sha, CommitAvatar::sourceUrl));
        List<String> dueAssociations = commits.stream()
                .filter(commit -> associationDue(commit, Optional.ofNullable(existing.get(commit.sha())), now))
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
                        Optional.ofNullable(avatarSources.get(commit.sha())),
                        Optional.ofNullable(associations.get(commit.sha())),
                        dueAssociations.contains(commit.sha()),
                        now))
                .toList();
    }

    private CommitEnrichment enrichment(
            long repositoryId,
            Commit commit,
            Optional<CommitEnrichment> existing,
            Optional<String> avatarSource,
            Optional<PullRequestAssociation> association,
            boolean associationChecked,
            Instant now)
    {
        Optional<String> avatarKey = avatarSource.flatMap(avatars::cache)
                .or(() -> existing.flatMap(CommitEnrichment::avatarKey));
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
                avatarKey,
                pullRequestNumber,
                pullRequestTitle,
                pullRequestUrl,
                Optional.of(now),
                associationCheckedAt,
                now);
    }

    private static boolean associationDue(Commit commit, Optional<CommitEnrichment> existing, Instant now)
    {
        if (existing.isEmpty() || existing.orElseThrow().associationCheckedAt().isEmpty()) {
            return true;
        }
        CommitEnrichment enrichment = existing.orElseThrow();
        if (enrichment.pullRequestNumber().isPresent()) {
            return false;
        }
        Instant authoredAt;
        try {
            authoredAt = Instant.parse(commit.authoredAt());
        }
        catch (DateTimeParseException ignored) {
            authoredAt = now;
        }
        return !authoredAt.isBefore(now.minus(NEGATIVE_RETRY_WINDOW))
                && !enrichment.associationCheckedAt().orElseThrow().isAfter(now.minus(NEGATIVE_RETRY_INTERVAL));
    }

    private static CommitEnrichment merge(CommitEnrichment first, CommitEnrichment second)
    {
        return new CommitEnrichment(
                second.repositoryId(),
                second.sha(),
                second.avatarKey().or(first::avatarKey),
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
