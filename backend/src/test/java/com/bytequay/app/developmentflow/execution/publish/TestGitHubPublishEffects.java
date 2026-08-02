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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.BaseMovedException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.MissingEffectException;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRequest;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.Route;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.SubjectRejectedException;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubPublishEffects
{
    private static final String HEAD = "head-sha";
    private static final String BASE = "base-sha";
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    private GitRunner git;
    private CodeFingerprints fingerprints;
    private PullRequestRepository pullRequests;
    private PatResolver pats;
    private GitHubPublishEffects effects;

    @BeforeEach
    void setUp()
    {
        git = mock(GitRunner.class);
        fingerprints = mock(CodeFingerprints.class);
        pullRequests = mock(PullRequestRepository.class);
        pats = mock(PatResolver.class);
        effects = new GitHubPublishEffects(git, fingerprints, pullRequests, pats);
    }

    @Test
    void verifiesCleanExactSubjectAndFrozenRemoteBase()
            throws Exception
    {
        PublishRequest request = directRequest();
        Path worktree = Path.of(request.worktreePath());
        Files.createDirectories(worktree);
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.branchName());
        when(git.headSha(worktree)).thenReturn(HEAD);
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint");
        when(git.commitCountUniqueTo(worktree, "HEAD", BASE)).thenReturn(2);
        when(git.listRemotes(worktree)).thenReturn(List.of(
                new GitRunner.Remote("origin", "git@example.test:acme/widget.git")));
        when(git.remoteSlug(worktree, "origin"))
                .thenReturn(Optional.of(RepoRef.parse("acme/widget")));
        when(git.remoteHeadSha(worktree, "origin", "main"))
                .thenReturn(Optional.of(BASE));

        assertThat(effects.verifySubject(request).kind())
                .isEqualTo(EffectKind.VERIFY_SUBJECT);
        assertThat(effects.verifyBranchBase(request).kind())
                .isEqualTo(EffectKind.RECONCILE_BRANCH_BASE);
    }

    @Test
    void reportsTheObservedBaseWhenTheFrozenRemoteBaseMoved()
            throws Exception
    {
        PublishRequest request = directRequest();
        Path worktree = Path.of(request.worktreePath());
        stubOrigin(worktree);
        when(git.remoteHeadSha(worktree, "origin", "main"))
                .thenReturn(Optional.of("new-base-sha"));

        assertThatThrownBy(() -> effects.verifyBranchBase(request))
                .isInstanceOfSatisfying(BaseMovedException.class, moved ->
                        assertThat(moved.observedBaseSha())
                                .isEqualTo("new-base-sha"));
        verify(git, never()).push(any());
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void pushFailureAdoptsTheExactRemoteHeadWithoutReplaying()
            throws Exception
    {
        PublishRequest request = directRequest();
        Path worktree = Path.of(request.worktreePath());
        stubOrigin(worktree);
        when(git.remoteHeadSha(worktree, "origin", request.branchName()))
                .thenReturn(Optional.empty(), Optional.of(HEAD));
        doThrow(new IOException("connection reset"))
                .when(git).push(worktree);

        var evidence = effects.pushOrAdoptBranch(request, fence(request));

        assertThat(evidence.kind()).isEqualTo(EffectKind.PUSH_BRANCH);
        verify(git).push(worktree);
    }

    @Test
    void forkCreationUsesOnlyFrozenHeadBaseTitleAndBody()
    {
        PublishRequest request = forkRequest();
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        when(pats.resolve(repository.fullName())).thenReturn("pat");
        when(pullRequests.listPullRequests(eq("pat"), eq(repository), any()))
                .thenReturn(List.of());
        PullRequest created = pullRequest(request, repository.fullName(), 17);
        when(pullRequests.createPullRequest(eq("pat"), eq(repository), any()))
                .thenReturn(created);
        when(pullRequests.fetchPrDetail(eq("pat"), any()))
                .thenReturn(rawDetail(request));

        var result = effects.createOrAdoptDraftPullRequest(
                request, fence(request));

        ArgumentCaptor<CreatePullRequestCommand> command =
                ArgumentCaptor.forClass(CreatePullRequestCommand.class);
        verify(pullRequests).createPullRequest(
                eq("pat"), eq(repository), command.capture());
        assertThat(command.getValue().head()).isEqualTo("forker:dev/task-1");
        assertThat(command.getValue().base()).isEqualTo("main");
        assertThat(command.getValue().title()).isEqualTo("Fix widget");
        assertThat(command.getValue().body()).contains("Approved body only");
        assertThat(command.getValue().draft()).contains(true);
        assertThat(result.remote().repositoryId()).isEqualTo("acme/widget");
        assertThat(result.remote().headSha()).isEqualTo(HEAD);

        ArgumentCaptor<ListPullRequestsQuery> query =
                ArgumentCaptor.forClass(ListPullRequestsQuery.class);
        verify(pullRequests).listPullRequests(
                eq("pat"), eq(repository), query.capture());
        assertThat(query.getValue().head()).contains("forker:dev/task-1");
        assertThat(query.getValue().base()).contains("main");
    }

    @Test
    void ambiguousCreateAdoptsOneExactExistingDraft()
    {
        PublishRequest request = directRequest();
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        PullRequest existing = pullRequest(request, repository.fullName(), 17);
        when(pats.resolve(repository.fullName())).thenReturn("pat");
        when(pullRequests.listPullRequests(eq("pat"), eq(repository), any()))
                .thenReturn(List.of(), List.of(existing));
        when(pullRequests.createPullRequest(eq("pat"), eq(repository), any()))
                .thenThrow(new IllegalStateException("timeout"));
        when(pullRequests.fetchPrDetail(eq("pat"), any()))
                .thenReturn(rawDetail(request));

        var result = effects.createOrAdoptDraftPullRequest(
                request, fence(request));

        assertThat(result.detail()).contains("ambiguous create");
        assertThat(result.remote().number()).isEqualTo(17);
        verify(pullRequests).createPullRequest(eq("pat"), eq(repository), any());
    }

    @Test
    void conflictingExistingPrIsNeverOverwrittenOrDuplicated()
    {
        PublishRequest request = directRequest();
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        PullRequest conflicting = pullRequest(
                request, repository.fullName(), 17, "Different title");
        when(pats.resolve(repository.fullName())).thenReturn("pat");
        when(pullRequests.listPullRequests(eq("pat"), eq(repository), any()))
                .thenReturn(List.of(conflicting));

        assertThatThrownBy(() -> effects.createOrAdoptDraftPullRequest(
                request, fence(request)))
                .isInstanceOf(SubjectRejectedException.class)
                .hasMessageContaining("metadata differs");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void reconciliationProbeReportsDefiniteAbsenceWithoutCreating()
    {
        PublishRequest request = directRequest();
        RepoRef repository = RepoRef.parse(request.baseRepositoryId());
        when(pats.resolve(repository.fullName())).thenReturn("pat");
        when(pullRequests.listPullRequests(eq("pat"), eq(repository), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> effects.probeDraftPullRequest(request))
                .isInstanceOf(MissingEffectException.class);
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    private void stubOrigin(Path worktree)
            throws Exception
    {
        when(git.listRemotes(worktree)).thenReturn(List.of(
                new GitRunner.Remote("origin", "git@example.test:acme/widget.git")));
        when(git.remoteSlug(worktree, "origin"))
                .thenReturn(Optional.of(RepoRef.parse("acme/widget")));
    }

    private WorktreeWriterLeaseManager.MutationFence fence(PublishRequest request)
    {
        WorktreeWriterLeaseManager.MutationFence fence =
                mock(WorktreeWriterLeaseManager.MutationFence.class);
        when(fence.worktreePath()).thenReturn(request.worktreePath());
        when(fence.taskId()).thenReturn(request.taskId());
        when(fence.operationId()).thenReturn(request.operationId());
        when(fence.taskEpoch()).thenReturn(request.taskEpoch());
        return fence;
    }

    private PublishRequest directRequest()
    {
        return request(
                Route.DIRECT,
                "acme/widget",
                "acme/widget",
                "dev/task-1");
    }

    private PublishRequest forkRequest()
    {
        return request(
                Route.FORK,
                "acme/widget",
                "forker/widget",
                "forker:dev/task-1");
    }

    private PublishRequest request(
            Route route,
            String baseRepository,
            String headRepository,
            String headRef)
    {
        return new PublishRequest(
                "publish-1", "operation-1", "stage-1", "task-1",
                "trunk-1", "workspace-1", 1, 1, 1,
                "DISPATCHED", "V2", "ACTIVE", 1, "stage-1", 1,
                "PUBLISHING", true, "manifest-1", "pr-1",
                "fingerprint", HEAD, BASE, route,
                baseRepository, headRepository, headRepository,
                "dev/task-1", headRef, "main", "Fix widget",
                "Approved body only", 1, "content-digest",
                tempDir.resolve("worktree").toAbsolutePath().normalize().toString());
    }

    private static PullRequest pullRequest(
            PublishRequest request, String repo, int number)
    {
        return pullRequest(request, repo, number, request.prTitle());
    }

    private static PullRequest pullRequest(
            PublishRequest request, String repo, int number, String title)
    {
        return new PullRequest(
                number, repo, number, title, "alice",
                "https://github.com/" + repo + "/pull/" + number,
                NOW, NOW, PullRequest.Origin.AUTHORED,
                List.of(), Map.of(), true, null, null, null, List.of(),
                null, 0, 0, 0, null, "open", null, null, null, null,
                null, Map.of(), null, null, request.branchName());
    }

    private static PrRawDetail rawDetail(PublishRequest request)
    {
        return new PrRawDetail(
                request.prBody(), List.of(), true, true, "clean",
                1, 1, 1, 0, List.of(),
                HEAD, request.branchName(), request.headRepositoryId(),
                request.baseBranch(), request.baseRepositoryId(),
                "open", false, BASE, null);
    }
}
