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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.DevelopmentBaseline;
import com.bytequay.app.agent.DevelopmentGitInspector;
import com.bytequay.app.agent.DevelopmentLocalFacts;
import com.bytequay.app.agent.DevelopmentPublicationFacts;
import com.bytequay.app.agent.DevelopmentPublicationRoute;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity.State;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentFactsResponse
{
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final RepoRef HEAD_REPOSITORY = new RepoRef("author", "project");
    private static final RepoRef BASE_REPOSITORY = new RepoRef("upstream", "project");
    private static final TaskId TASK_ID = TaskId.random();
    private static final String BRANCH = "bytequay/task/" + TASK_ID;
    private static final TaskWorktree WORKTREE = new TaskWorktree(
            TASK_ID, "workspace", new RepositoryIdentity("author", "project"), Path.of("/tmp/tasks").resolve(TASK_ID.toString()), BRANCH, BASE);
    private static final DevelopmentPublicationRoute ROUTE = new DevelopmentPublicationRoute(
            HEAD_REPOSITORY, BASE_REPOSITORY, "https://github.com/author/project.git", "upstream", "main", BASE, BRANCH);
    private static final DevelopmentLocalFacts LOCAL = new DevelopmentLocalFacts(BRANCH, HEAD, BASE, "fingerprint", 1, 2, 3, 5, 2, true, false);
    private static final DevelopmentBaseline BASELINE = new DevelopmentBaseline(WORKTREE, ROUTE, LOCAL);

    private final AgentTask task = AgentTask.created(TASK_ID, true, "Development facts", TitleSource.USER, AgentLaneId.CODEX_CLI,
            WORKTREE, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.parse("2026-08-16T00:00:00Z"));
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);

    @ParameterizedTest
    @EnumSource(State.class)
    void showsExactOpenClosedAndMergedPullRequestFacts(State state)
    {
        DevelopmentFactsResponse response = inspect(new DevelopmentPublicationFacts(Optional.of(HEAD), ImmutableList.of(identity(state))));

        assertThat(response.local()).contains(new DevelopmentFactsResponse.Local(BRANCH, HEAD, 1, 2, 3, 5, 2));
        assertThat(response.localError()).isEmpty();
        assertThat(response.remoteError()).isEmpty();
        assertThat(response.remote().orElseThrow().pullRequest().orElseThrow().state()).isEqualTo(state.name().toLowerCase(Locale.ROOT));
        assertThat(response.remote().orElseThrow().headSha()).contains(HEAD);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"CLOSED", "MERGED"})
    void preservesHistoricalPullRequestsAfterConfirmedBranchDeletion(State state)
    {
        DevelopmentFactsResponse response = inspect(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of(identity(state))));

        assertThat(response.remoteError()).isEmpty();
        assertThat(response.remote().orElseThrow().headSha()).isEmpty();
        assertThat(response.remote().orElseThrow().pullRequest()).isPresent();
    }

    @Test
    void distinguishesConfirmedRemoteAbsenceFromAnUnavailableRemoteRead()
    {
        DevelopmentFactsResponse absent = inspect(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of()));
        assertThat(absent.remote()).contains(new DevelopmentFactsResponse.Remote(Optional.empty(), Optional.empty()));
        assertThat(absent.remoteError()).isEmpty();

        when(git.inspectPublication(WORKTREE, ROUTE)).thenThrow(new IllegalStateException("GitHub access unavailable"));
        DevelopmentFactsResponse unknown = DevelopmentFactsResponse.inspect(task, git);
        assertThat(unknown.local()).isEqualTo(absent.local());
        assertThat(unknown.localError()).isEmpty();
        assertThat(unknown.remote()).isEmpty();
        assertThat(unknown.remoteError()).contains("GitHub access unavailable");
    }

    @Test
    void localFailureLeavesBothLocalAndRemoteFactsUnknown()
    {
        when(git.capture(task)).thenThrow(new IllegalStateException("Managed worktree ownership changed"));

        DevelopmentFactsResponse response = DevelopmentFactsResponse.inspect(task, git);

        assertThat(response.local()).isEmpty();
        assertThat(response.localError()).contains("Managed worktree ownership changed");
        assertThat(response.remote()).isEmpty();
        assertThat(response.remoteError()).isEmpty();
        verify(git).capture(task);
        verifyNoMoreInteractions(git);
    }

    @ParameterizedTest
    @MethodSource("mismatchedIdentities")
    void rejectsPullRequestsWithTheWrongDestination(GitHubPullRequestIdentity identity)
    {
        DevelopmentFactsResponse response = inspect(new DevelopmentPublicationFacts(Optional.of(HEAD), ImmutableList.of(identity)));

        assertThat(response.local()).isPresent();
        assertThat(response.remote()).isEmpty();
        assertThat(response.remoteError()).contains("Pull request destination does not match this task");
    }

    @Test
    void ambiguousOrIncompleteRemoteEvidenceDoesNotBecomeNoPullRequest()
    {
        for (DevelopmentPublicationFacts publication : ImmutableList.of(
                new DevelopmentPublicationFacts(Optional.of(HEAD), ImmutableList.of(identity(State.OPEN), identity(State.OPEN))),
                new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of(identity(State.OPEN))),
                new DevelopmentPublicationFacts(Optional.of(BASE), ImmutableList.of(identity(State.OPEN))),
                new DevelopmentPublicationFacts(Optional.of(BASE), ImmutableList.of(identity(State.CLOSED))))) {
            DevelopmentFactsResponse response = inspect(publication);
            assertThat(response.local()).isPresent();
            assertThat(response.remote()).isEmpty();
            assertThat(response.remoteError()).isPresent();
        }
    }

    private DevelopmentFactsResponse inspect(DevelopmentPublicationFacts publication)
    {
        when(git.capture(task)).thenReturn(BASELINE);
        when(git.inspectPublication(WORKTREE, ROUTE)).thenReturn(publication);
        return DevelopmentFactsResponse.inspect(task, git);
    }

    private static Stream<GitHubPullRequestIdentity> mismatchedIdentities()
    {
        return Stream.of(
                identity(new RepoRef("another-author", "project"), BRANCH, BASE_REPOSITORY, "main", State.OPEN),
                identity(HEAD_REPOSITORY, "another-branch", BASE_REPOSITORY, "main", State.OPEN),
                identity(HEAD_REPOSITORY, BRANCH, new RepoRef("another-base", "project"), "main", State.OPEN),
                identity(HEAD_REPOSITORY, BRANCH, BASE_REPOSITORY, "another-base", State.OPEN),
                new GitHubPullRequestIdentity(new PullRequestRef("another-base", "project", 42), "https://github.com/another-base/project/pull/42",
                        HEAD_REPOSITORY, BRANCH, HEAD, BASE_REPOSITORY, "main", BASE, State.OPEN, true, "author"));
    }

    private static GitHubPullRequestIdentity identity(State state)
    {
        return identity(HEAD_REPOSITORY, BRANCH, BASE_REPOSITORY, "main", state);
    }

    private static GitHubPullRequestIdentity identity(RepoRef headRepository, String headBranch, RepoRef baseRepository, String baseBranch, State state)
    {
        return new GitHubPullRequestIdentity(
                new PullRequestRef(BASE_REPOSITORY.owner(), BASE_REPOSITORY.repo(), 42), "https://github.com/upstream/project/pull/42",
                headRepository, headBranch, HEAD, baseRepository, baseBranch, BASE, state, state == State.OPEN, "author");
    }
}
