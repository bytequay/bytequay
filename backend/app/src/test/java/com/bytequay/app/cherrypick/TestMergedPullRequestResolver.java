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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitSequencerDriver;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote.Role;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.CommitHistoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.git.GitTestSupport.commit;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestMergedPullRequestResolver
{
    private static final String WORKSPACE = "p2";
    private static final PullRequestRef PR = PullRequestRef.of("internal", "p3", 7);
    private static final String SOURCE_URL = "https://github.com/internal/p3.git";
    private static final String TARGET_URL = "https://github.com/internal/p2.git";
    private static final String API = "https://api.github.test/repos/internal/p3/pulls/7";
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;
    private Path source;
    private Path clone;
    private String seed;
    private List<String> original;
    private WorkspaceRepositoryService workspaces;
    private WorkspaceRemoteSourceService remotes;
    private CredentialService credentials;
    private MockRestServiceServer server;
    private GitHubPullRequestReadClient reader;
    private MergedPullRequestResolver resolver;

    @BeforeEach
    void setUp()
            throws IOException
    {
        source = initRepository(temporaryDirectory.resolve("p3"));
        write(source, "feature.txt", "seed\n");
        commit(source, "Seed");
        seed = head(source);
        Path target = temporaryDirectory.resolve("p2");
        git(temporaryDirectory, "clone", "--quiet", "--bare", source.toString(), target.toString());
        clone = initRepository(temporaryDirectory.resolve("managed"));
        git(clone, "remote", "add", "source", SOURCE_URL);
        git(clone, "remote", "add", "origin", TARGET_URL);
        git(clone, "config", "url." + source.toUri() + ".insteadOf", SOURCE_URL);
        git(clone, "config", "url." + target.toUri() + ".insteadOf", TARGET_URL);
        workspaces = mock(WorkspaceRepositoryService.class);
        when(workspaces.requireReadyRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE, clone, 2, RepoRef.of("internal", "p2"), OptionalLong.empty(), RepoRef.of("internal", "p2"),
                "origin", "origin", "main", ImmutableList.of(new RemoteSource(WORKSPACE, "source", SOURCE_URL).withRepositoryIdentity(3, RepoRef.of("internal", "p3")))));
        remotes = mock(WorkspaceRemoteSourceService.class);
        when(remotes.list(WORKSPACE)).thenReturn(ImmutableList.of(new WorkspaceRemote("source", SOURCE_URL, Role.CUSTOM), new WorkspaceRemote("origin", TARGET_URL, Role.ORIGIN)));
        credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken(WORKSPACE)).thenReturn("workspace-token");
        when(credentials.gitAuthenticationEnvironment(anyString(), anyCollection())).thenReturn(ImmutableMap.of());
        RestClient.Builder api = RestClient.builder().baseUrl("https://api.github.test");
        server = MockRestServiceServer.bindTo(api).build();
        reader = new GitHubPullRequestReadClient(api.build(), RestClient.create("https://api.github.test/graphql"));
        resolver = resolver(GIT);

        git(source, "checkout", "--quiet", "-b", "feature");
        write(source, "feature.txt", "<<<<<<< HEAD\nprivate change\n=======\nP4 change\n>>>>>>> source\n");
        commit(source, "Apply P4 change\n\n(cherry picked from commit " + "4".repeat(40) + ")");
        String applied = head(source);
        write(source, "feature.txt", "P3 adaptation of the P4 change\n");
        commit(source, "fixup! Resolve P3 conflict");
        original = ImmutableList.of(applied, head(source));
        git(source, "update-ref", "refs/pull/7/head", head(source));
        git(source, "checkout", "--quiet", "main");
    }

    @ParameterizedTest
    @CsvSource({"LINEAR,false", "SQUASH,false", "REBASE,false", "LINEAR,true", "SQUASH,true", "REBASE,true"})
    void resolvesLandedP3ChangesAndFeedsExistingP2Preview(Shape shape, boolean conflictSnapshot)
            throws Exception
    {
        if (!conflictSnapshot) {
            git(source, "checkout", "--quiet", "feature");
            git(source, "reset", "--hard", seed);
            write(source, "feature.txt", "P4 change\n");
            commit(source, "Apply P4 change\n\n(cherry picked from commit " + "4".repeat(40) + ")");
            String applied = head(source);
            write(source, "feature.txt", "P3 adaptation of the P4 change\n");
            commit(source, "fixup! Adapt P4 change");
            original = ImmutableList.of(applied, head(source));
            git(source, "update-ref", "refs/pull/7/head", head(source));
            git(source, "checkout", "--quiet", "main");
        }
        String base = seed;
        if (shape != Shape.LINEAR) {
            write(source, "unrelated-base.txt", "Base moved while the PR was open\n");
            commit(source, "Unrelated base change");
            base = head(source);
        }
        switch (shape) {
            case LINEAR -> git(source, "merge", "--ff-only", "feature");
            case SQUASH -> {
                git(source, "merge", "--squash", "feature");
                commit(source, "Squash PR");
            }
            case REBASE -> git(source, "cherry-pick", original.getFirst(), original.getLast());
        }
        String landed = head(source);
        List<String> expected = shape == Shape.SQUASH ? ImmutableList.of(landed)
                : ImmutableList.copyOf(git(source, "rev-list", "--reverse", base + ".." + landed).lines().toList());
        write(source, "later.txt", "Another PR landed afterwards\n");
        commit(source, "Later PR");
        expectEvidence(detail(landed), original);

        MergedPullRequestRange range = resolver.resolve(WORKSPACE, "source", PR);

        assertThat(range.sourceBaseSha()).isEqualTo(base);
        assertThat(range.commits()).containsExactlyElementsOf(expected);
        assertThat(range.originalCommits()).containsExactlyElementsOf(original);
        assertThat(range.pullRequest()).isEqualTo(PR);
        assertThat(range.repositoryId()).isEqualTo(3);
        assertThat(range.mergeSha()).isEqualTo(landed).isNotEqualTo(head(source));
        if (shape != Shape.LINEAR) {
            assertThat(range.commits()).doesNotContainAnyElementsOf(original);
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readValue(mapper.writeValueAsString(range), MergedPullRequestRange.class)).isEqualTo(range);

        CherryPickPreviewService previews = previews();
        CherryPickPreview preview = previews.preview(command(range.sourceBaseSha(), range.mergeSha()), range);
        assertThat(preview.commits()).extracting(CherryPickCommitPreview::sourceSha).containsExactlyElementsOf(expected);
        assertThat(preview.sourceRepository()).isEqualTo(RepoRef.of("internal", "p3"));
        assertThat(preview.repository()).isEqualTo(RepoRef.of("internal", "p2"));
        assertThat(preview.commits()).noneMatch(CherryPickCommitPreview::alreadyOnTarget);
        assertThat(git(clone, "status", "--porcelain")).isEmpty();
        assertThat(git(clone, "for-each-ref", "--format=%(refname)", "refs/bytequay/merged-pr")).isEmpty();
        assertThatThrownBy(() -> previews.preview(command(range.commits().getFirst(), range.mergeSha()), range))
                .isInstanceOf(CherryPickInvalidRequestException.class).hasMessageContaining("saved merged pull request");
        server.verify();
        executeLandedRange(previews, preview, conflictSnapshot && shape != Shape.SQUASH);
    }

    private void executeLandedRange(CherryPickPreviewService previews, CherryPickPreview preview, boolean containsConflictSnapshot)
            throws IOException
    {
        var paths = new WorkspacePaths(temporaryDirectory.resolve("executions").toString());
        Files.createDirectories(paths.worktrees(WORKSPACE));
        var worktrees = mock(WorktreeService.class);
        when(worktrees.provision(any())).thenAnswer(call -> {
            ProvisionWorktreeCommand command = call.getArgument(0);
            Path path = paths.canonicalTaskWorktree(WORKSPACE, command.taskId().toString());
            git(clone, "worktree", "add", "-b", command.generatedBranch(), path.toString(), command.startCommit());
            return new TaskWorktree(command.taskId(), WORKSPACE, command.physicalRepository(), path.toRealPath(), command.generatedBranch(), command.startCommit());
        });
        when(worktrees.inspect(any())).thenAnswer(call -> {
            TaskWorktree worktree = call.getArgument(0);
            boolean dirty = !git(worktree.canonicalWorktree(), "status", "--porcelain").isBlank();
            return new WorktreeSnapshot(worktree, head(worktree.canonicalWorktree()), dirty, false, dirty);
        });
        doAnswer(call -> {
            TaskWorktree worktree = call.getArgument(0);
            git(clone, "worktree", "remove", worktree.canonicalWorktree().toString());
            return null;
        }).when(worktrees).cleanup(any());
        var agent = new ScriptedCherryPickAgent(worktrees);
        var capabilities = new AgentCapabilities(false, false, false, ImmutableSet.of(AgentPermissionPolicy.CHERRY_PICK), ImmutableSet.of());
        var driver = mock(AgentDriver.class);
        when(driver.capabilities()).thenReturn(capabilities);
        when(driver.probe()).thenReturn(new AgentProviderStatus(AgentLaneId.CLAUDE_CLI, AgentProviderStatus.Readiness.READY, Optional.of("fixture"), "Ready", capabilities));
        var drivers = mock(AgentDriverRegistry.class);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(driver);
        when(credentials.resolveGitHubCredential()).thenReturn(new CredentialService.GitHubCredential("fixture", new UserProfile("test", "", "", "", 0, 0, 0, "", "", "", "")));
        var store = new InMemoryCherryPickExecutionStore();
        var service = new CherryPickExecutionService(previews, store, new GitSequencerDriver(GIT), GIT, agent, agent, agent, drivers,
                credentials, workspaces, paths, Clock.systemUTC());
        var execution = service.start(new StartCherryPickExecutionCommand(preview.previewId(), preview.sourceBaseSha(), preview.sourceTipSha(), preview.targetSha(),
                ImmutableList.of(), "claude", Optional.empty(), Optional.empty(), "landed-range",
                new CherryPickExecution.PullRequestDraft("Backport P3", "Use the landed P3 result")), WORKSPACE, clone, new RepositoryIdentity("internal", "p2"));
        for (int tick = 0; tick < 10 && execution.status() == CherryPickExecution.Status.RUNNING; tick++) {
            execution = service.advance(execution.id());
        }
        if (containsConflictSnapshot) {
            assertThat(execution.status()).isEqualTo(CherryPickExecution.Status.PAUSED);
            assertThat(execution.summary()).get().asString().contains("conflict marker");
            assertThat(execution.workspace().worktree()).exists();
            assertThat(agent.taskCompleted()).isFalse();
            return;
        }
        assertThat(execution.finishReason()).as(execution.summary().orElse("No summary")).contains(CherryPickExecution.FinishReason.COMPLETED);
        assertThat(git(clone, "show", execution.workspace().workBranch() + ":feature.txt")).isEqualTo("P3 adaptation of the P4 change");
        assertThat(git(clone, "diff", "--name-only", seed, execution.workspace().workBranch())).isEqualTo("feature.txt");
        assertThat(execution.items()).extracting(item -> item.sourceSha())
                .containsExactlyElementsOf(preview.commits().stream().map(CherryPickCommitPreview::sourceSha).toList());
        assertThat(execution.workspace().worktree()).doesNotExist();
        assertThat(agent.turnsStarted()).isZero();
    }

    @Test
    void holdsMergeCommitsInsteadOfReplayingTheirHead()
    {
        git(source, "merge", "--no-ff", "-m", "Merge PR", "feature");
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "merge-commit replay is unsupported");
        assertThat(git(clone, "for-each-ref", "--format=%(refname)", "refs/bytequay/merged-pr")).isEmpty();
    }

    @Test
    void provesBinaryChangesAndQuotedFileNames()
            throws IOException
    {
        git(source, "checkout", "--quiet", "feature");
        Files.write(source.resolve("binary-雪\nasset.bin"), new byte[] {0, (byte) 0xFF, 1});
        commit(source, "Binary change");
        original = ImmutableList.of(original.getFirst(), original.getLast(), head(source));
        git(source, "update-ref", "refs/pull/7/head", head(source));
        git(source, "checkout", "--quiet", "main");
        git(source, "merge", "--squash", "feature");
        commit(source, "Squash PR with binary content");
        expectEvidence(detail(head(source)), original);
        git(clone, "config", "core.quotePath", "false");
        assertThat(resolver.resolve(WORKSPACE, "source", PR).commits()).containsExactly(head(source));
        server.verify();
    }

    @Test
    void rejectsACommitCountThatWouldIncludeUnrelatedChanges()
    {
        write(source, "unrelated.txt", "Not part of this PR\n");
        commit(source, "Same count, different changes");
        write(source, "feature.txt", "P3 adaptation of the P4 change\n");
        write(source, "extra.txt", "Not in the PR\n");
        commit(source, "The feature looks right, but contains an unrelated change");
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "exact landed changes could not be proved");
    }

    @Test
    void refusesAnIncompleteOriginalSequenceEvenWithMatchingProviderCount()
    {
        git(source, "merge", "--ff-only", "feature");
        git(source, "checkout", "--quiet", "feature");
        write(source, "third.txt", "Third PR commit\n");
        commit(source, "Third change");
        String third = head(source);
        git(source, "update-ref", "refs/pull/7/head", third);
        original = ImmutableList.of(original.getFirst(), third);
        git(source, "checkout", "--quiet", "main");
        git(source, "merge", "--ff-only", "feature");
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "not one complete linear range");
    }

    @Test
    void refusesMissingHistoryAndMismatchedFetchedHeads()
    {
        git(source, "merge", "--ff-only", "feature");
        expectEvidence(detail(head(source)), original);
        git(source, "update-ref", "-d", "refs/pull/7/head");
        assertHold(resolver, "Cannot fetch");

        server.reset();
        git(source, "update-ref", "refs/pull/7/head", seed);
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "fetched pull-request head changed");
    }

    @Test
    void refusesALandedShaOutsideTheFetchedBase()
    {
        expectEvidence(detail(original.getLast()), original);
        assertHold(resolver, "missing from the fetched base branch");
    }

    @Test
    void holdsSameFileBaseEditsWithoutGuessingPatchEquivalence()
    {
        write(source, "feature.txt", "seed\nextra base line\n");
        commit(source, "Base edited the same file");
        write(source, "feature.txt", "P3 adaptation of the P4 change\nextra base line\n");
        commit(source, "Squash after base edit");
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "base changed the same files");
    }

    @Test
    void rejectsEmptyOriginalCommitsRatherThanGuessingDroppedRebaseBoundaries()
    {
        git(source, "checkout", "--quiet", "feature");
        git(source, "commit", "--allow-empty", "--quiet", "-m", "Empty PR commit");
        original = ImmutableList.of(original.getFirst(), original.getLast(), head(source));
        git(source, "update-ref", "refs/pull/7/head", head(source));
        git(source, "checkout", "--quiet", "main");
        git(source, "merge", "--ff-only", "feature");
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "empty commit");
    }

    @Test
    void rejectsRoutingChangesBeforeUsingCredentials()
    {
        git(clone, "remote", "set-url", "source", TARGET_URL);
        assertHold(resolver, "changed from saved workspace routing");
        verifyNoInteractions(credentials);
    }

    @Test
    void holdsWhenBothASquashAndARebasedSequenceMatch()
    {
        git(source, "checkout", "--quiet", "feature");
        write(source, "feature.txt", "seed\n");
        commit(source, "Undo the first changes");
        String reverted = head(source);
        write(source, "feature.txt", "P3 final change\n");
        commit(source, "Final change");
        original = ImmutableList.of(original.getFirst(), original.getLast(), reverted, head(source));
        git(source, "update-ref", "refs/pull/7/head", head(source));
        git(source, "checkout", "--quiet", "main");
        write(source, "base.txt", "Unrelated base change\n");
        commit(source, "Move base");
        git(source, ImmutableList.<String>builder().add("cherry-pick").addAll(original).build().toArray(String[]::new));
        expectEvidence(detail(head(source)), original);
        assertHold(resolver, "More than one landed range");
    }

    @Test
    void refusesShallowHistoryBeforeReadingProviderFacts()
    {
        git(source, "merge", "--ff-only", "feature");
        git(clone, "fetch", "--depth=1", "source", "main");
        assertHold(resolver, "repository is shallow");
        verifyNoInteractions(credentials);
    }

    @Test
    void previewRejectsAWidenedSavedRange()
    {
        git(source, "merge", "--ff-only", "feature");
        expectEvidence(detail(head(source)), original);
        MergedPullRequestRange resolved = resolver.resolve(WORKSPACE, "source", PR);
        MergedPullRequestRange incomplete = new MergedPullRequestRange(resolved.pullRequest(), resolved.repositoryId(), resolved.baseBranch(),
                resolved.mergedAt(), resolved.mergeSha(), resolved.originalCommits(), resolved.sourceBaseSha(), ImmutableList.of(resolved.mergeSha()));
        assertThatThrownBy(() -> previews().preview(command(incomplete.sourceBaseSha(), incomplete.mergeSha()), incomplete))
                .isInstanceOf(CherryPickInvalidRequestException.class).hasMessageContaining("does not match the saved landed commits");
        server.verify();
    }

    @Test
    void rejectsTruncatedGitEvidence()
    {
        git(source, "merge", "--ff-only", "feature");
        expectEvidence(detail(head(source)), original);
        MergedPullRequestResolver bounded = resolver(new GitCommandRunner()
        {
            @Override
            public Result runLocalBoundedStdout(Path repository, Duration timeout, int maximumOutputBytes, List<String> arguments)
            {
                if (arguments.contains("diff-tree")) {
                    return new Result(0, "", true);
                }
                return super.runLocalBoundedStdout(repository, timeout, maximumOutputBytes, arguments);
            }
        });
        assertHold(bounded, "exceeds the read limit");
    }

    private void assertHold(MergedPullRequestResolver service, String message)
    {
        assertThatThrownBy(() -> service.resolve(WORKSPACE, "source", PR)).isInstanceOf(CherryPickInvalidRequestException.class)
                .hasMessageContaining(message).hasMessageContaining("select an explicit range");
        server.verify();
    }

    private void expectEvidence(String detail, List<String> commits)
    {
        server.expect(requestTo(API)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer workspace-token"))
                .andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/commits?per_page=100&page=1")).andRespond(withSuccess(
                commits.stream().map(sha -> "{\"sha\":\"" + sha + "\"}").collect(joining(",", "[", "]")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API)).andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
    }

    private String detail(String landed)
    {
        return """
                {"number":7,"html_url":"https://github.com/internal/p3/pull/7","state":"closed","draft":false,
                 "merged":true,"merged_at":"2026-09-01T00:00:00Z","merge_commit_sha":"%s","commits":%d,"user":{"login":"alice"},
                 "head":{"ref":"feature","sha":"%s","repo":{"id":31,"full_name":"alice/p3"}},
                 "base":{"ref":"main","sha":"%s","repo":{"id":3,"full_name":"internal/p3"}}}
                """.formatted(landed, original.size(), original.getLast(), head(source));
    }

    private MergedPullRequestResolver resolver(GitCommandRunner runner)
    {
        return new MergedPullRequestResolver(workspaces, remotes, credentials, reader, runner);
    }

    private CherryPickPreviewService previews()
    {
        return new CherryPickPreviewService(GIT, new CherryPickConflictScout(GIT), mock(CommitHistoryStore.class),
                credentials, remotes, Clock.systemUTC(), mock(WorkspaceRelationships.class));
    }

    private PreviewCherryPickRangeCommand command(String base, String tip)
    {
        return new PreviewCherryPickRangeCommand(clone, WORKSPACE, OptionalLong.empty(), "source", base, tip, "origin", "main");
    }

    private enum Shape { LINEAR, SQUASH, REBASE }
}
