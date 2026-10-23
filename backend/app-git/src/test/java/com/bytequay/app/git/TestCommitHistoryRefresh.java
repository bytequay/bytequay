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
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.git.PullRequestLookupResult.Association;
import com.bytequay.app.repository.github.CommitAvatarSnapshot;
import com.bytequay.app.repository.github.CommitAvatarSnapshot.CommitAvatar;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.CommitRefreshMetadata;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.core.domain.CommitRefreshStatus.State.FAILED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCommitHistoryRefresh
{
    private static final String WORKSPACE_ID = "workspace-id";
    private static final Path REPOSITORY = Path.of("/managed/example-repository");
    private static final RepoRef SELECTED = RepoRef.of("example-owner", "example-repository");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String AVATAR_KEY = "a".repeat(64);
    private static final String COMMITTER_AVATAR_KEY = "b".repeat(64);

    private WorkspaceRepositoryService workspaces;
    private WorkspaceProvisioningService provisioning;
    private GitBrowseService git;
    private RecordingStore store;
    private CredentialService credentials;
    private GitHubRepoClient gitHub;
    private CherryPickPullRequestLookup pullRequests;
    private AvatarCache avatars;
    private Clock clock;
    private CommitHistoryService service;

    @BeforeEach
    void setUp()
    {
        workspaces = mock(WorkspaceRepositoryService.class);
        provisioning = mock(WorkspaceProvisioningService.class);
        git = mock(GitBrowseService.class);
        store = new RecordingStore();
        credentials = mock(CredentialService.class);
        gitHub = mock(GitHubRepoClient.class);
        pullRequests = mock(CherryPickPullRequestLookup.class);
        avatars = mock(AvatarCache.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CommitHistoryService(workspaces, provisioning, git, store, credentials, gitHub, pullRequests, avatars, clock, mock(ApplicationContext.class));

        readyWorkspace();
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, true, Optional.of(NOW), ImmutableSet.of()));
        when(credentials.resolveGitHubToken()).thenReturn("test-token");
    }

    @Test
    void deletedWorkspaceDoesNotRecreateRefreshMetadata()
    {
        when(workspaces.getWorkspace(WORKSPACE_ID)).thenThrow(WorkspaceException.notFound(WORKSPACE_ID));

        assertThatThrownBy(() -> service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")))).isInstanceOf(WorkspaceException.class);

        assertThat(store.metadata).isEmpty();
        assertThat(store.enrichment).isEmpty();
        verifyNoInteractions(provisioning, gitHub);
    }

    @Test
    void serviceRefreshCompletesBeforeReturning()
    {
        Commit commit = commit("abc123", NOW.minusSeconds(60));
        when(git.log(REPOSITORY, "origin/main", 300)).thenReturn(ImmutableList.of(commit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, commit.sha(), 1))
                .thenReturn(new CommitAvatarSnapshot(
                        ImmutableList.of(new CommitAvatar(
                                commit.sha(),
                                Optional.of("percy-r"),
                                Optional.of("https://avatars.example.invalid/percy-r.png"),
                                Optional.of("ezra-b"),
                                Optional.of("https://avatars.example.invalid/ezra-b.png"))), true));
        when(avatars.cache("https://avatars.example.invalid/percy-r.png")).thenReturn(Optional.of(AVATAR_KEY));
        when(avatars.cache("https://avatars.example.invalid/ezra-b.png")).thenReturn(Optional.of(COMMITTER_AVATAR_KEY));
        PullRequestAssociation pullRequest = new PullRequestAssociation(42, "Example pull request", "https://example.invalid/pull/42", "main", "2026-08-22T00:00:00Z");
        when(pullRequests.lookUpResult(eq(SELECTED), eq(ImmutableList.of(commit.sha())), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(new Association(commit.sha(), pullRequest)), ImmutableSet.of()));

        CommitRefreshStatus refreshed = service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));

        assertThat(refreshed.state()).isEqualTo(IDLE);
        assertThat(service.getRefreshStatus(WORKSPACE_ID).state()).isEqualTo(IDLE);
        assertThat(store.findRefreshMetadata(WORKSPACE_ID).lastSucceededAt()).contains(NOW);
        assertThat(store.values()).singleElement().satisfies(value -> {
            assertThat(value.authorLogin()).contains("percy-r");
            assertThat(value.avatarKey()).contains(AVATAR_KEY);
            assertThat(value.committerLogin()).contains("ezra-b");
            assertThat(value.committerAvatarKey()).contains(COMMITTER_AVATAR_KEY);
            assertThat(value.pullRequestNumber()).contains(42);
            assertThat(value.pullRequestTitle()).contains("Example pull request");
            assertThat(value.pullRequestMergedAt()).contains(Instant.parse("2026-08-22T00:00:00Z"));
        });
        InOrder order = inOrder(provisioning, git, gitHub, pullRequests);
        order.verify(provisioning).synchronizeCode(WORKSPACE_ID);
        order.verify(git).log(REPOSITORY, "origin/main", 300);
        order.verify(gitHub).fetchCommitAvatarSnapshot("test-token", SELECTED, commit.sha(), 1);
        order.verify(pullRequests).lookUpResult(eq(SELECTED), eq(ImmutableList.of(commit.sha())), eq("main"), anyList());
    }

    @Test
    void refreshReusesFreshIdentitiesWithoutExtendingTheirFreshness()
    {
        Instant checkedAt = NOW.minusSeconds(300);
        Commit commit = commit("cached", NOW.minusSeconds(600));
        store.upsertEnrichment(ImmutableList.of(positive(101, commit.sha(), checkedAt)));
        when(git.log(REPOSITORY, "origin/main", 300)).thenReturn(ImmutableList.of(commit));
        when(pullRequests.lookUpResult(eq(SELECTED), eq(ImmutableList.of()), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of()));

        CommitRefreshStatus status = service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));

        assertThat(status.state()).isEqualTo(IDLE);
        verify(provisioning).synchronizeCode(WORKSPACE_ID);
        verifyNoInteractions(gitHub, avatars);
        assertThat(store.values()).singleElement().satisfies(value -> {
            assertThat(value.avatarCheckedAt()).contains(checkedAt);
            assertThat(value.pullRequestNumber()).contains(42);
        });
    }

    @Test
    void fetchesIdentitiesOnlyThroughTheOldestCommitDueForRefresh()
    {
        Commit newCommit = commit("new", NOW.minusSeconds(60));
        Commit freshCommit = commit("fresh", NOW.minusSeconds(600));
        Commit staleCommit = commit("stale", NOW.minusSeconds(3_600));
        Commit oldCommit = commit("old", NOW.minusSeconds(7_200));
        Instant freshCheckedAt = NOW.minusSeconds(300);
        store.upsertEnrichment(ImmutableList.of(
                positive(101, freshCommit.sha(), freshCheckedAt),
                positive(101, staleCommit.sha(), NOW.minusSeconds(86_400)),
                positive(101, oldCommit.sha(), freshCheckedAt)));
        when(git.log(REPOSITORY, "origin/main", 300)).thenReturn(ImmutableList.of(newCommit, freshCommit, staleCommit, oldCommit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, newCommit.sha(), 3))
                .thenReturn(new CommitAvatarSnapshot(ImmutableList.of(), true));
        when(pullRequests.lookUpResult(eq(SELECTED), eq(ImmutableList.of(newCommit.sha())), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of()));

        CommitRefreshStatus status = service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));

        assertThat(status.state()).isEqualTo(IDLE);
        verify(gitHub).fetchCommitAvatarSnapshot("test-token", SELECTED, newCommit.sha(), 3);
        assertThat(store.findEnrichment(101, ImmutableList.of(newCommit.sha(), freshCommit.sha(), staleCommit.sha())).commits())
                .allSatisfy(value -> assertThat(value.avatarCheckedAt()).contains(NOW));
        assertThat(store.findEnrichment(101, ImmutableList.of(oldCommit.sha())).commits())
                .singleElement().satisfies(value -> assertThat(value.avatarCheckedAt()).contains(freshCheckedAt));
    }

    @Test
    void sameWorkspaceRefreshesDoNotOverlap()
            throws Exception
    {
        successfulEmptyBranch("main");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(provisioning.synchronizeCode(WORKSPACE_ID)).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                if (!releaseFirst.await(5, SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release the first refresh");
                }
            }
            else {
                secondStarted.countDown();
            }
            return new WorkspaceCodeSyncResult(WORKSPACE_ID, true, Optional.of(NOW), ImmutableSet.of());
        });
        List<CommitHistoryRef> refs = ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CommitRefreshStatus> first = executor.submit(() -> service.refresh(WORKSPACE_ID, refs));
            assertThat(firstStarted.await(5, SECONDS)).isTrue();
            Future<CommitRefreshStatus> second = executor.submit(() -> service.refresh(WORKSPACE_ID, refs));

            assertThat(secondStarted.await(250, MILLISECONDS)).isFalse();
            releaseFirst.countDown();

            assertThat(first.get(5, SECONDS).state()).isEqualTo(IDLE);
            assertThat(second.get(5, SECONDS).state()).isEqualTo(IDLE);
            assertThat(calls).hasValue(2);
        }
        finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void conditionalRefreshRechecksFreshnessAfterAcquiringTheWorkspaceLock()
    {
        store.saveRefreshMetadata(new CommitRefreshMetadata(WORKSPACE_ID, Optional.of(NOW), Optional.of(NOW), Optional.empty()));

        CommitRefreshStatus current = service.refreshIfDue(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")), NOW.minusSeconds(300));

        assertThat(current.lastSucceededAt()).contains(NOW);
        verify(provisioning, never()).synchronizeCode(WORKSPACE_ID);
        verifyNoInteractions(git, gitHub, pullRequests);
    }

    @Test
    void providerFailureRetainsTheLastSnapshotAndSuccessTime()
    {
        Instant previousSuccess = NOW.minusSeconds(300);
        CommitEnrichment previous = negative("abc123", NOW.minusSeconds(86_400), NOW.minusSeconds(60));
        store.upsertEnrichment(ImmutableList.of(previous));
        store.saveRefreshMetadata(new CommitRefreshMetadata(WORKSPACE_ID, Optional.of(previousSuccess), Optional.of(previousSuccess), Optional.empty()));
        Commit commit = commit(previous.sha(), NOW.minusSeconds(60));
        when(git.log(REPOSITORY, "origin/main", 300)).thenReturn(ImmutableList.of(commit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, commit.sha(), 1))
                .thenReturn(new CommitAvatarSnapshot(ImmutableList.of(), false));

        CommitRefreshStatus status = service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));

        assertThat(status.state()).isEqualTo(FAILED);
        assertThat(store.findRefreshMetadata(WORKSPACE_ID).lastSucceededAt()).contains(previousSuccess);
        assertThat(store.values()).containsExactly(previous);
    }

    @Test
    void failedGitFetchAndPersistenceProduceRetryableFailedState()
    {
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, false, Optional.empty(), ImmutableSet.of()));
        service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        assertThat(service.getRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);

        store.failUpsert = true;
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, true, Optional.of(NOW), ImmutableSet.of()));
        successfulEmptyBranch("main");
        service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));
        assertThat(service.getRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);
    }

    @Test
    void selectedCustomRemoteFetchFailureMarksRefreshFailed()
    {
        readyWorkspace(unidentifiedRemote("oss"));
        when(provisioning.synchronizeCode(WORKSPACE_ID)).thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, true, Optional.of(NOW), ImmutableSet.of("oss")));

        service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.REMOTE, "main", Optional.of("oss"))));

        assertThat(service.getRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);
        verify(git, never()).log(REPOSITORY, "oss/main", 300);
    }

    @Test
    void customRemoteRefreshReadsTheFetchedBranchWithoutProviderEnrichment()
    {
        readyWorkspace(unidentifiedRemote("oss"));
        when(git.log(REPOSITORY, "oss/main", 300)).thenReturn(ImmutableList.of());

        service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.REMOTE, "main", Optional.of("oss"))));

        assertThat(service.getRefreshStatus(WORKSPACE_ID).state()).isEqualTo(IDLE);
        verify(git).log(REPOSITORY, "oss/main", 300);
        verifyNoInteractions(gitHub, pullRequests);
    }

    @Test
    void identifiedCustomRemoteRefreshesAndReusesProviderEnrichment()
    {
        RepoRef repository = RepoRef.of("owner", "repository");
        readyWorkspace(new RemoteSource(WORKSPACE_ID, "oss", "https://github.com/owner/repository.git", Optional.of(202L), Optional.of(repository)));
        Commit commit = commit("remote123", NOW.minusSeconds(60));
        Instant previousCheck = NOW.minusSeconds(86_400);
        store.upsertEnrichment(ImmutableList.of(new CommitEnrichment(202, commit.sha(), Optional.of("old-author"), Optional.of(AVATAR_KEY),
                Optional.of("old-committer"), Optional.of(COMMITTER_AVATAR_KEY), Optional.of(42), Optional.of("Retained title"), Optional.of("https://example.invalid/pull/42"),
                Optional.of(previousCheck), Optional.of(previousCheck), Optional.of(previousCheck), previousCheck)));
        when(git.log(REPOSITORY, "oss/main", 300)).thenReturn(ImmutableList.of(commit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", repository, commit.sha(), 1))
                .thenReturn(new CommitAvatarSnapshot(
                        ImmutableList.of(new CommitAvatar(
                                commit.sha(),
                                Optional.of("percy-r"),
                                Optional.of("https://avatars.example.invalid/percy-r.png"),
                                Optional.empty(),
                                Optional.empty())),
                        true));
        when(avatars.cache("https://avatars.example.invalid/percy-r.png")).thenReturn(Optional.of(AVATAR_KEY));
        when(pullRequests.lookUpResult(eq(repository), eq(ImmutableList.of()), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of()));

        CommitRefreshStatus status = service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.REMOTE, "main", Optional.of("oss"))));

        assertThat(status.state()).isEqualTo(IDLE);
        assertThat(store.values()).singleElement().satisfies(value -> {
            assertThat(value.repositoryId()).isEqualTo(202);
            assertThat(value.authorLogin()).contains("percy-r");
            assertThat(value.avatarKey()).contains(AVATAR_KEY);
            assertThat(value.committerLogin()).contains("old-committer");
            assertThat(value.committerAvatarKey()).contains(COMMITTER_AVATAR_KEY);
            assertThat(value.pullRequestNumber()).contains(42);
            assertThat(value.pullRequestTitle()).contains("Retained title");
            assertThat(value.pullRequestUrl()).contains("https://example.invalid/pull/42");
            assertThat(value.avatarCheckedAt()).contains(NOW);
            assertThat(value.associationCheckedAt()).contains(previousCheck);
            assertThat(value.lastSeenAt()).isEqualTo(NOW);
        });
        verify(gitHub).fetchCommitAvatarSnapshot("test-token", repository, commit.sha(), 1);
        verify(pullRequests).lookUpResult(eq(repository), eq(ImmutableList.of()), eq("main"), anyList());
    }

    @Test
    void reusesPositiveAndFreshNegativeAssociationsAndRetriesOnlyDueRecentNegatives()
    {
        Commit positive = commit("positive", NOW.minusSeconds(60));
        Commit freshNegative = commit("fresh-negative", NOW.minusSeconds(60));
        Commit dueNegative = commit("due-negative", NOW.minusSeconds(60));
        Commit expiredNegative = commit("expired-negative", NOW.minusSeconds(8 * 86_400));
        List<Commit> commits = ImmutableList.of(positive, freshNegative, dueNegative, expiredNegative);
        store.upsertEnrichment(ImmutableList.of(
                positive(positive.sha()),
                negative(freshNegative.sha(), NOW.minusSeconds(2 * 3_600), NOW),
                negative(dueNegative.sha(), NOW.minusSeconds(2 * 86_400), NOW),
                negative(expiredNegative.sha(), NOW.minusSeconds(2 * 86_400), NOW)));
        when(git.log(REPOSITORY, "origin/main", 300)).thenReturn(commits);
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, positive.sha(), 4))
                .thenReturn(new CommitAvatarSnapshot(ImmutableList.of(), true));
        when(pullRequests.lookUpResult(eq(SELECTED), eq(ImmutableList.of(dueNegative.sha())), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of()));

        service.refresh(WORKSPACE_ID, ImmutableList.of(new CommitHistoryRef(Source.WORKSPACE, "main")));

        verify(pullRequests).lookUpResult(eq(SELECTED), eq(ImmutableList.of(dueNegative.sha())), eq("main"), anyList());
    }

    private void successfulEmptyBranch(String branch)
    {
        when(git.log(REPOSITORY, "origin/" + branch, 300)).thenReturn(ImmutableList.of());
        when(pullRequests.lookUpResult(eq(SELECTED), eq(ImmutableList.of()), eq(branch), anyList()))
                .thenReturn(new PullRequestLookupResult(ImmutableList.of(), ImmutableSet.of()));
    }

    private void readyWorkspace(RemoteSource... extraRemotes)
    {
        when(workspaces.requireReadyRepository(WORKSPACE_ID)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE_ID,
                REPOSITORY,
                101,
                SELECTED,
                OptionalLong.empty(),
                SELECTED,
                "origin",
                "origin",
                "main",
                ImmutableList.copyOf(extraRemotes)));
    }

    private static RemoteSource unidentifiedRemote(String name)
    {
        return new RemoteSource(WORKSPACE_ID, name, "https://example.invalid/" + name + ".git");
    }

    private static Commit commit(String sha, Instant committedAt)
    {
        return new Commit(sha, "Example change", "Example body", "Example Author", "Example Committer", committedAt.toString(), committedAt.toString(), Optional.empty());
    }

    private static CommitEnrichment positive(String sha)
    {
        return positive(101, sha);
    }

    private static CommitEnrichment positive(long repositoryId, String sha)
    {
        return positive(repositoryId, sha, NOW);
    }

    private static CommitEnrichment positive(long repositoryId, String sha, Instant avatarCheckedAt)
    {
        return new CommitEnrichment(
                repositoryId,
                sha,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(42),
                Optional.of("Example pull request"),
                Optional.of("https://example.invalid/pull/42"),
                Optional.of(NOW.minusSeconds(86_400)),
                Optional.of(avatarCheckedAt),
                Optional.of(NOW.minusSeconds(86_400)),
                NOW);
    }

    private static CommitEnrichment negative(String sha, Instant checkedAt, Instant lastSeenAt)
    {
        return new CommitEnrichment(
                101,
                sha,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(checkedAt),
                Optional.of(checkedAt),
                lastSeenAt);
    }

    private static final class RecordingStore
            implements CommitHistoryStore
    {
        private final Map<String, CommitEnrichment> enrichment = new LinkedHashMap<>();
        private final Map<String, CommitRefreshMetadata> metadata = new LinkedHashMap<>();
        private boolean failUpsert;

        @Override
        public CommitEnrichmentSnapshot findEnrichment(long repositoryId, Collection<String> shas)
        {
            return new CommitEnrichmentSnapshot(enrichment.values().stream()
                    .filter(value -> value.repositoryId() == repositoryId && shas.contains(value.sha()))
                    .toList());
        }

        @Override
        public void upsertEnrichment(Collection<CommitEnrichment> values)
        {
            if (failUpsert) {
                throw new IllegalStateException("persistence unavailable");
            }
            values.forEach(value -> enrichment.put(value.repositoryId() + ":" + value.sha(), value));
        }

        @Override
        public CommitRefreshMetadata findRefreshMetadata(String workspaceId)
        {
            return metadata.getOrDefault(workspaceId, CommitRefreshMetadata.empty(workspaceId));
        }

        @Override
        public void saveRefreshMetadata(CommitRefreshMetadata value)
        {
            CommitRefreshMetadata previous = findRefreshMetadata(value.workspaceId());
            metadata.put(value.workspaceId(), new CommitRefreshMetadata(
                    value.workspaceId(),
                    value.lastAttemptAt(),
                    value.lastSucceededAt().or(previous::lastSucceededAt),
                    value.error()));
        }

        List<CommitEnrichment> values()
        {
            return ImmutableList.copyOf(enrichment.values());
        }
    }
}
