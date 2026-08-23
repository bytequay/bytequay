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
import com.bytequay.app.cherrypick.PullRequestLookupResult.Association;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.repository.github.CommitAvatarSnapshot;
import com.bytequay.app.repository.github.CommitAvatarSnapshot.CommitAvatar;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.CommitRefreshStatus.State;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.git.CommitRefreshCause.MANUAL;
import static com.bytequay.core.domain.CommitRefreshStatus.State.FAILED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.IDLE;
import static com.bytequay.core.domain.CommitRefreshStatus.State.QUEUED;
import static com.bytequay.core.domain.CommitRefreshStatus.State.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCommitHistoryRefreshWorker
{
    private static final String WORKSPACE_ID = "workspace-id";
    private static final Path REPOSITORY = Path.of("/managed/example-repository");
    private static final RepoRef SELECTED = RepoRef.of("example-owner", "example-repository");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String AVATAR_KEY = "a".repeat(64);

    private WorkspaceRepositoryService workspaces;
    private WorkspaceProvisioningService provisioning;
    private GitBrowseService git;
    private RecordingStore store;
    private CredentialService credentials;
    private GitHubRepoClient gitHub;
    private CherryPickPullRequestLookup pullRequests;
    private PullRequestSyncJob pullRequestSync;
    private AvatarCache avatars;
    private ControlledExecutor executor;
    private DefaultCommitHistoryService service;

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
        pullRequestSync = mock(PullRequestSyncJob.class);
        avatars = mock(AvatarCache.class);
        executor = new ControlledExecutor();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CommitHistoryRefreshWorker worker = new CommitHistoryRefreshWorker(
                workspaces,
                provisioning,
                git,
                store,
                credentials,
                gitHub,
                pullRequests,
                pullRequestSync,
                avatars,
                clock,
                executor);
        service = new DefaultCommitHistoryService(workspaces, git, store, worker, clock, 53123);

        readyWorkspace();
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, CodeState.READY, Optional.of(NOW)));
        when(credentials.resolveGitHubToken()).thenReturn("test-token");
        when(pullRequestSync.synchronizeRepository(SELECTED)).thenReturn(true);
    }

    @Test
    void serviceRequestBuildsADurableSnapshotAndTransitionsInOrder()
    {
        Commit commit = commit("abc123", NOW.minusSeconds(60));
        when(git.log(REPOSITORY, "main", 300)).thenReturn(List.of(commit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, "main", 300))
                .thenReturn(new CommitAvatarSnapshot(
                        List.of(new CommitAvatar(commit.sha(), "https://avatars.example.invalid/example.png")), true));
        when(avatars.cache("https://avatars.example.invalid/example.png")).thenReturn(Optional.of(AVATAR_KEY));
        PullRequestAssociation pullRequest = new PullRequestAssociation(
                42,
                "Example pull request",
                "https://example.invalid/pull/42",
                "main",
                "2026-08-22T00:00:00Z");
        when(pullRequests.lookUpResult(eq(SELECTED), eq(List.of(commit.sha())), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(List.of(new Association(commit.sha(), pullRequest)), java.util.Set.of()));

        CommitRefreshStatus queued = service.requestRefresh(
                WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);

        assertThat(queued.state()).isEqualTo(QUEUED);
        assertThat(store.states).containsExactly(QUEUED);
        verify(provisioning, never()).synchronizeCode(WORKSPACE_ID);
        executor.runNext();

        assertThat(store.states).containsExactly(QUEUED, RUNNING, IDLE);
        assertThat(store.findRefreshStatus(WORKSPACE_ID).lastSucceededAt()).contains(NOW);
        assertThat(store.values()).singleElement().satisfies(value -> {
            assertThat(value.avatarKey()).contains(AVATAR_KEY);
            assertThat(value.pullRequestNumber()).contains(42);
            assertThat(value.pullRequestTitle()).contains("Example pull request");
        });
        InOrder order = inOrder(provisioning, git, gitHub, pullRequests, pullRequestSync);
        order.verify(provisioning).synchronizeCode(WORKSPACE_ID);
        order.verify(git).log(REPOSITORY, "main", 300);
        order.verify(gitHub).fetchCommitAvatarSnapshot("test-token", SELECTED, "main", 300);
        order.verify(pullRequests).lookUpResult(eq(SELECTED), eq(List.of(commit.sha())), eq("main"), anyList());
        order.verify(pullRequestSync).synchronizeRepository(SELECTED);
    }

    @Test
    void duplicateRequestsCoalesceWithoutConcurrentRuns()
    {
        successfulEmptyBranch("main");

        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);

        assertThat(executor.size()).isEqualTo(1);
        executor.runNext();
        verify(provisioning, times(1)).synchronizeCode(WORKSPACE_ID);
        verify(git, times(1)).log(REPOSITORY, "main", 300);
        assertThat(executor.size()).isZero();
    }

    @Test
    void requestReceivedDuringExecutionRunsInTheNextDrainPass()
    {
        successfulEmptyBranch("main");
        successfulEmptyBranch("release");
        AtomicBoolean requested = new AtomicBoolean();
        doAnswer(invocation -> {
            if (requested.compareAndSet(false, true)) {
                service.requestRefresh(
                        WORKSPACE_ID,
                        List.of(new CommitHistoryRef(Source.WORKSPACE, "release")),
                        MANUAL);
            }
            return List.of();
        }).when(git).log(REPOSITORY, "main", 300);

        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        executor.runNext();

        verify(provisioning, times(2)).synchronizeCode(WORKSPACE_ID);
        verify(git).log(REPOSITORY, "main", 300);
        verify(git).log(REPOSITORY, "release", 300);
        assertThat(executor.size()).isZero();
    }

    @Test
    void providerFailureRetainsTheLastSnapshotAndSuccessTime()
    {
        Instant previousSuccess = NOW.minusSeconds(300);
        CommitEnrichment previous = negative("abc123", NOW.minusSeconds(120), NOW.minusSeconds(60));
        store.upsertEnrichment(List.of(previous));
        store.saveRefreshStatus(new CommitRefreshStatus(
                WORKSPACE_ID,
                IDLE,
                Optional.of(previousSuccess),
                Optional.of(previousSuccess),
                Optional.empty()));
        Commit commit = commit(previous.sha(), NOW.minusSeconds(60));
        when(git.log(REPOSITORY, "main", 300)).thenReturn(List.of(commit));
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, "main", 300))
                .thenReturn(new CommitAvatarSnapshot(List.of(), false));

        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        executor.runNext();

        assertThat(store.findRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);
        assertThat(store.findRefreshStatus(WORKSPACE_ID).lastSucceededAt()).contains(previousSuccess);
        assertThat(store.values()).containsExactly(previous);
        verify(pullRequestSync, never()).synchronizeRepository(SELECTED);
    }

    @Test
    void failedGitFetchAndPersistenceProduceRetryableFailedState()
    {
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, CodeState.FAILED, Optional.empty()));
        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        executor.runNext();
        assertThat(store.findRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);

        store.failUpsert = true;
        when(provisioning.synchronizeCode(WORKSPACE_ID))
                .thenReturn(new WorkspaceCodeSyncResult(WORKSPACE_ID, CodeState.READY, Optional.of(NOW)));
        successfulEmptyBranch("main");
        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        executor.runNext();
        assertThat(store.findRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);
    }

    @Test
    void selectedCustomRemoteFetchFailureMarksRefreshFailed()
    {
        readyWorkspace("oss");
        when(provisioning.synchronizeCode(WORKSPACE_ID)).thenReturn(new WorkspaceCodeSyncResult(
                WORKSPACE_ID,
                CodeState.READY,
                Optional.of(NOW),
                ImmutableSet.of("oss")));

        service.requestRefresh(
                WORKSPACE_ID,
                ImmutableList.of(new CommitHistoryRef(Source.REMOTE, "main", Optional.of("oss"))),
                MANUAL);
        executor.runNext();

        assertThat(store.findRefreshStatus(WORKSPACE_ID).state()).isEqualTo(FAILED);
        verify(git, never()).log(REPOSITORY, "oss/main", 300);
    }

    @Test
    void customRemoteRefreshReadsTheFetchedBranchWithoutProviderEnrichment()
    {
        readyWorkspace("oss");
        when(git.log(REPOSITORY, "oss/main", 300)).thenReturn(ImmutableList.of());

        service.requestRefresh(
                WORKSPACE_ID,
                ImmutableList.of(new CommitHistoryRef(Source.REMOTE, "main", Optional.of("oss"))),
                MANUAL);
        executor.runNext();

        assertThat(store.findRefreshStatus(WORKSPACE_ID).state()).isEqualTo(IDLE);
        verify(git).log(REPOSITORY, "oss/main", 300);
        verifyNoInteractions(gitHub, pullRequests, pullRequestSync);
    }

    @Test
    void reusesPositiveAndFreshNegativeAssociationsAndRetriesOnlyDueRecentNegatives()
    {
        Commit positive = commit("positive", NOW.minusSeconds(60));
        Commit freshNegative = commit("fresh-negative", NOW.minusSeconds(60));
        Commit dueNegative = commit("due-negative", NOW.minusSeconds(60));
        Commit expiredNegative = commit("expired-negative", NOW.minusSeconds(8 * 86_400));
        List<Commit> commits = List.of(positive, freshNegative, dueNegative, expiredNegative);
        store.upsertEnrichment(List.of(
                positive(positive.sha()),
                negative(freshNegative.sha(), NOW.minusSeconds(2 * 3_600), NOW),
                negative(dueNegative.sha(), NOW.minusSeconds(2 * 86_400), NOW),
                negative(expiredNegative.sha(), NOW.minusSeconds(2 * 86_400), NOW)));
        when(git.log(REPOSITORY, "main", 300)).thenReturn(commits);
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, "main", 300))
                .thenReturn(new CommitAvatarSnapshot(List.of(), true));
        when(pullRequests.lookUpResult(
                eq(SELECTED), eq(List.of(dueNegative.sha())), eq("main"), anyList()))
                .thenReturn(new PullRequestLookupResult(List.of(), java.util.Set.of()));

        service.requestRefresh(WORKSPACE_ID, List.of(new CommitHistoryRef(Source.WORKSPACE, "main")), MANUAL);
        executor.runNext();

        verify(pullRequests).lookUpResult(
                eq(SELECTED), eq(List.of(dueNegative.sha())), eq("main"), anyList());
    }

    private void successfulEmptyBranch(String branch)
    {
        when(git.log(REPOSITORY, branch, 300)).thenReturn(List.of());
        when(gitHub.fetchCommitAvatarSnapshot("test-token", SELECTED, branch, 300))
                .thenReturn(new CommitAvatarSnapshot(List.of(), true));
        when(pullRequests.lookUpResult(eq(SELECTED), eq(List.of()), eq(branch), anyList()))
                .thenReturn(new PullRequestLookupResult(List.of(), java.util.Set.of()));
    }

    private void readyWorkspace(String... extraRemotes)
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

    private static Commit commit(String sha, Instant authoredAt)
    {
        return new Commit(
                sha,
                "Example change",
                "Example body",
                "Example Author",
                authoredAt.toString(),
                Optional.empty());
    }

    private static CommitEnrichment positive(String sha)
    {
        return new CommitEnrichment(
                101,
                sha,
                Optional.empty(),
                Optional.of(42),
                Optional.of("Example pull request"),
                Optional.of("https://example.invalid/pull/42"),
                Optional.of(NOW),
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
                Optional.of(checkedAt),
                Optional.of(checkedAt),
                lastSeenAt);
    }

    private static final class ControlledExecutor
            implements Executor
    {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command)
        {
            tasks.addLast(command);
        }

        void runNext()
        {
            tasks.removeFirst().run();
        }

        int size()
        {
            return tasks.size();
        }
    }

    private static final class RecordingStore
            implements CommitHistoryStore
    {
        private final Map<String, CommitEnrichment> enrichment = new LinkedHashMap<>();
        private final Map<String, CommitRefreshStatus> status = new LinkedHashMap<>();
        private final List<State> states = new ArrayList<>();
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
        public CommitRefreshStatus findRefreshStatus(String workspaceId)
        {
            return status.getOrDefault(workspaceId, CommitRefreshStatus.idle(workspaceId));
        }

        @Override
        public void saveRefreshStatus(CommitRefreshStatus value)
        {
            status.put(value.workspaceId(), value);
            states.add(value.state());
        }

        List<CommitEnrichment> values()
        {
            return List.copyOf(enrichment.values());
        }
    }
}
