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
package com.bytequay.app.context;

import com.bytequay.app.context.ContextHistoryDiscovery.Candidate;
import com.bytequay.app.context.ContextHistoryReader.SourceKind;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubSearchResponse;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextHistoryDiscovery
{
    private static final RepoRef REPO = RepoRef.of("owner", "repo");
    private static final String SHA = "a".repeat(40);
    private static final WorkspaceRepositoryContext WORKSPACE = new WorkspaceRepositoryContext("workspace", Path.of("/tmp/repo"), 1, RepoRef.of("me", "repo"),
            OptionalLong.of(7), REPO, "upstream", "origin", "main", ImmutableList.of());
    private final GitBrowseService git = mock(GitBrowseService.class);
    private final RepositoryEnrichmentService enrichment = mock(RepositoryEnrichmentService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final GitHubRepoClient remote = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final ContextHistoryDiscovery discovery = new ContextHistoryDiscovery(git, enrichment, pullRequests, details, remote, credentials);

    @BeforeEach
    void setup()
    {
        when(git.localLog(any(), anyString(), anyList(), anyInt())).thenReturn(ImmutableList.of());
        when(pullRequests.listPullRequests(REPO, 0, 20, "")).thenReturn(page(ImmutableList.of()));
        when(pullRequests.findStored(anyString(), anyInt())).thenReturn(Optional.empty());
        when(details.find(anyLong())).thenReturn(Optional.empty());
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(remote.searchHistory(anyString(), eq(REPO), eq(true), anyString())).thenReturn(new GitHubSearchResponse(0, ImmutableList.of(), false));
        when(remote.searchHistory(anyString(), eq(REPO), eq(false), anyString())).thenReturn(new GitHubSearchResponse(0, ImmutableList.of(), false));
    }

    @Test
    void localDiscoveryUsesStoredUpstreamAssociationsWithoutAnyRemoteRead()
    {
        ContextScope scope = scope(ImmutableList.of("engine/"), ImmutableList.of());
        GitBrowseService.Commit commit = new GitBrowseService.Commit(SHA, "Engine behavior", "Historical decision", "a", "c", "time", "time", Optional.empty());
        when(git.localLog(WORKSPACE.repository(), SHA, ImmutableList.of("engine/"), 50)).thenReturn(ImmutableList.of(commit));
        CommitEnrichment mapped = new CommitEnrichment(7, SHA, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(9),
                Optional.of("Engine decision"), Optional.empty(), Optional.empty(), Optional.of(Instant.EPOCH), Instant.EPOCH);
        when(enrichment.findCommits(eq(7L), any())).thenReturn(new CommitEnrichmentSnapshot(ImmutableList.of(mapped)));
        when(pullRequests.findStored(REPO.fullName(), 9)).thenReturn(Optional.of(pr(9, "Historical decision", true)));

        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(scope), "", false);

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.kind()).isEqualTo(SourceKind.PR);
            assertThat(candidate.number()).isEqualTo(9);
            assertThat(candidate.reasons()).anySatisfy(reason -> assertThat(reason).contains("touching selected paths"));
            assertThat(candidate.provenance()).contains("Local stored PR");
        });
        assertThat(result.inspectedPullRequests()).isEqualTo(1);
        assertThat(result.inspectedIssues()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("no closed-issue candidate list"));
        verifyNoInteractions(remote, credentials);
    }

    @Test
    void onlyActuallyMergedRowsAreCandidatesAndPlainQueryWordsRestrictThem()
    {
        when(pullRequests.listPullRequests(REPO, 0, 20, "")).thenReturn(page(ImmutableList.of(
                pr(1, "Retry handles timeout", true), pr(2, "Retry timeout", false), pr(3, "Unrelated change", true))));
        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "retry timeout", false);
        assertThat(result.candidates()).extracting(Candidate::number).containsExactly(1);
        assertThat(result.inspectedPullRequests()).isEqualTo(3);
        assertThat(result.candidates().getFirst().reasons()).anySatisfy(reason -> assertThat(reason).contains("requested words"));
        verifyNoInteractions(remote, credentials);
    }

    @Test
    void newerCachedMergeRemainsUsableWhenTheListStillShowsAnOpenPr()
    {
        PullRequest row = pr(9, "Recently merged", "open", null);
        Instant updatedAt = Instant.EPOCH.plusSeconds(20);
        PrRawDetail raw = new PrRawDetail(row.title(), row.htmlUrl(), "closed", "author", null, Instant.EPOCH, updatedAt, updatedAt,
                "Merged after the list refresh", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null,
                0, 0, 0, 0, ImmutableList.of(), SHA, "head", "main");
        when(pullRequests.listPullRequests(REPO, 0, 20, "")).thenReturn(page(ImmutableList.of(row)));
        when(details.find(row.id())).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of())));

        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "", false);

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.number()).isEqualTo(9);
            assertThat(candidate.state()).isEqualTo("MERGED");
            assertThat(candidate.updatedAt()).isEqualTo(updatedAt);
        });
        verifyNoInteractions(remote, credentials);
    }

    @Test
    void explicitRemoteSearchDistinguishesClosureReasonsAndRejectsWrongRepositoryHostAndKind()
    {
        GitHubSearchResponse.Item complete = item(1, false, "completed", "https://api.github.com/repos/owner/repo", "https://github.com/owner/repo/issues/1");
        GitHubSearchResponse.Item rejected = item(2, false, "not_planned", "https://api.github.com/repos/owner/repo", "https://github.com/owner/repo/issues/2");
        GitHubSearchResponse.Item unknown = item(3, false, null, "https://api.github.com/repos/owner/repo", "https://github.com/owner/repo/issues/3");
        GitHubSearchResponse.Item wrongRepo = item(4, false, "completed", "https://api.github.com/repos/other/repo", "https://github.com/owner/repo/issues/4");
        GitHubSearchResponse.Item wrongHost = item(5, false, "completed", "https://api.github.com/repos/owner/repo", "https://evil.example/owner/repo/issues/5");
        GitHubSearchResponse.Item wrongKind = item(6, true, null, "https://api.github.com/repos/owner/repo", "https://github.com/owner/repo/pull/6");
        when(remote.searchHistory("token", REPO, false, "")).thenReturn(new GitHubSearchResponse(100,
                ImmutableList.of(complete, rejected, unknown, wrongRepo, wrongHost, wrongKind), true));

        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "", true);

        assertThat(result.candidates()).extracting(Candidate::outcome).containsExactly("UNKNOWN", "NOT_PLANNED", "COMPLETED");
        assertThat(result.candidates()).allSatisfy(candidate -> assertThat(candidate.state()).isEqualTo("CLOSED"));
        assertThat(result.inspectedIssues()).isEqualTo(6);
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("incomplete"));
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("first 20-result"));
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("excluded"));
        verify(remote).searchHistory("token", REPO, true, "");
        verify(remote).searchHistory("token", REPO, false, "");
    }

    @Test
    void capsEachKindAndSkipsPrSearchWhenFiveUsefulLocalCandidatesExist()
    {
        when(pullRequests.listPullRequests(REPO, 0, 20, "")).thenReturn(page(IntStream.rangeClosed(1, 20).mapToObj(i -> pr(i, "PR " + i, true))
                .collect(ImmutableList.toImmutableList())));
        when(remote.searchHistory("token", REPO, false, "")).thenReturn(new GitHubSearchResponse(20, IntStream.rangeClosed(1, 20)
                .mapToObj(i -> item(i, false, "completed", "https://api.github.com/repos/owner/repo", "https://github.com/owner/repo/issues/" + i))
                .collect(ImmutableList.toImmutableList()), false));
        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "", true);
        assertThat(result.candidates()).hasSize(10);
        assertThat(result.candidates().stream().filter(candidate -> candidate.kind() == SourceKind.PR).count()).isEqualTo(5);
        assertThat(result.candidates().stream().filter(candidate -> candidate.kind() == SourceKind.ISSUE).count()).isEqualTo(5);
        assertThat(result.candidates().getFirst().number()).isEqualTo(20);
        verify(remote, never()).searchHistory(anyString(), any(), eq(true), anyString());
    }

    @Test
    void remoteFailureKeepsLocalResultsAndDoesNotBecomeAnEmptyHistoryClaim()
    {
        when(pullRequests.listPullRequests(REPO, 0, 20, "")).thenReturn(page(ImmutableList.of(pr(1, "Recent PR", true))));
        when(remote.searchHistory("token", REPO, true, "")).thenThrow(new IllegalStateException("rate limited"));
        when(remote.searchHistory("token", REPO, false, "")).thenThrow(new IllegalStateException("rate limited"));
        var result = discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "", true);
        assertThat(result.candidates()).extracting(Candidate::number).containsExactly(1);
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("PR remote discovery failed"));
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap).contains("ISSUE remote discovery failed"));
    }

    @Test
    void rejectsQualifierSyntaxBeforeAnyLocalOrRemoteWork()
    {
        assertThatThrownBy(() -> discovery.discover(WORKSPACE, SHA, ImmutableList.of(ContextScope.project()), "repo:other/repo OR is:open", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain words");
        verifyNoInteractions(git, enrichment, pullRequests, details, remote, credentials);
    }

    private static ContextScope scope(List<String> paths, List<String> terms)
    {
        return new ContextScope("engine", "Engine", new ContextMatch.Selected(paths, terms), true, Optional.empty(), Optional.empty());
    }

    private static PullRequestPage page(List<PullRequest> rows)
    {
        return new PullRequestPage(rows, 0, 20, rows.size(), 0, false);
    }

    private static PullRequest pr(int number, String title, boolean merged)
    {
        return pr(number, title, merged ? "merged" : "closed", merged ? Instant.EPOCH : null);
    }

    private static PullRequest pr(int number, String title, String state, Instant mergedAt)
    {
        return new PullRequest(number, REPO.fullName(), number, title, "author", "https://github.com/owner/repo/pull/" + number, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(number), PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null,
                0, 0, 0, state, Instant.EPOCH, mergedAt, null, null, null, null, null, null, null, null);
    }

    private static GitHubSearchResponse.Item item(int number, boolean pr, String reason, String repositoryUrl, String url)
    {
        return new GitHubSearchResponse.Item(number, number, "History " + number, url, Instant.EPOCH, Instant.EPOCH.plusSeconds(number), Instant.EPOCH,
                "closed", repositoryUrl, null, ImmutableList.of(), false, pr ? new GitHubSearchResponse.PullRequestLink(Instant.EPOCH) : null, "Historical description", reason);
    }
}
