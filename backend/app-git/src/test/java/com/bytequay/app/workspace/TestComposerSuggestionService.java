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
package com.bytequay.app.workspace;

import com.bytequay.app.service.hosting.HostingIntegrationFixtures;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.IssueSyncPage;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.PullRequestReader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.bytequay.app.workspace.ComposerSuggestionService.Command.BACKPORT;
import static com.bytequay.app.workspace.ComposerSuggestionService.Command.FIX;
import static com.bytequay.app.workspace.ComposerSuggestionService.Command.REVIEW;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestComposerSuggestionService
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final HostingIntegrations integrations = mock(HostingIntegrations.class);
    private final PullRequestReader requests = mock(PullRequestReader.class);
    private final IssueClient issues = mock(IssueClient.class);
    private final ComposerSuggestionService service = new ComposerSuggestionService(hosting, integrations);

    @BeforeEach
    void setUp()
    {
        when(hosting.storedRead("workspace")).thenReturn(route(1, "account", 10, "alice"));
        HostingIntegration base = HostingIntegrationFixtures.issues(GITHUB, issues);
        HostingIntegration integration = new HostingIntegration(base.provider(), base.since(), base.capabilities(), base.accounts(), base.repositories(), requests,
                base.contents(), base.writer(), issues, base.metadata(), base.discovery(), base.ci(), base.team());
        when(integrations.of(any(HostingTarget.class))).thenReturn(integration);
    }

    @Test
    void reviewUsesCreationOrderAndExcludesMineBeforeLimiting()
    {
        List<FetchedPullRequest> first = ImmutableList.<FetchedPullRequest>builder()
                .addAll(IntStream.rangeClosed(1, 12).mapToObj(number -> pr(number, "Change " + number, "bob", "open", number, 0)).collect(toImmutableList()))
                .add(pr(20, "My change", "ALICE", "open", 30, 0), pr(21, "Closed change", "bob", "closed", 31, 0))
                .build();
        when(requests.list(any(), eq(true), eq(1), eq(100))).thenReturn(new PullRequestListPage(first, true));
        when(requests.list(any(), eq(true), eq(2), eq(100))).thenReturn(new PullRequestListPage(
                ImmutableList.of(pr(123, "Improve transactional handling", "bob", "open", -10, 0)), false));

        assertThat(service.list("workspace", REVIEW, "")).extracting(ComposerSuggestion::number).containsExactly(12, 11, 10, 9, 8, 7, 6, 5, 4, 3);
        assertThat(service.list("workspace", REVIEW, "  SACT  ")).extracting(ComposerSuggestion::number).containsExactly(123);
        assertThat(service.list("workspace", REVIEW, "#123")).extracting(ComposerSuggestion::number).containsExactly(123);
        assertThat(service.list("workspace", REVIEW, "23")).extracting(ComposerSuggestion::number).containsExactly(123);
        assertThat(service.list("workspace", REVIEW, "%")).isEmpty();
        verify(requests, times(2)).list(any(), eq(true), anyInt(), eq(100));
        verify(hosting, times(5)).storedRead("workspace");
        verifyNoInteractions(issues);
    }

    @Test
    void backportUsesMergeTimeAndIncludesOldMatchesFromLaterPages()
    {
        when(requests.list(any(), eq(false), eq(1), eq(100))).thenReturn(new PullRequestListPage(ImmutableList.of(
                pr(1, "Merged first", "bob", "merged", 100, 1),
                pr(2, "Merged later", "alice", "merged", 1, 3),
                pr(3, "Closed without merging", "bob", "closed", 200, 100),
                pr(4, "Still open", "bob", "open", 200, 100)), true));
        when(requests.list(any(), eq(false), eq(2), eq(100))).thenReturn(new PullRequestListPage(ImmutableList.of(
                pr(5, "Ancient transactional fix", "bob", "merged", -200, -100),
                pr(6, "Merged second", "bob", "merged", 50, 2)), false));

        assertThat(service.list("workspace", BACKPORT, "")).extracting(ComposerSuggestion::number).containsExactly(2, 6, 1, 5);
        assertThat(service.list("workspace", BACKPORT, "sact")).extracting(ComposerSuggestion::number).containsExactly(5);
        verify(requests, times(2)).list(any(), eq(false), anyInt(), eq(100));
    }

    @Test
    void stopsAfterProvenNewestTenButReadsTimestampTiesAndLaterSearchMatches()
    {
        List<FetchedPullRequest> first = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> pr(11 - index, "Change " + index, "bob", "open", (11 - index) * 10, 0, (11 - index) * 10))
                .collect(toImmutableList());
        when(requests.list(any(), eq(true), eq(1), eq(100))).thenReturn(new PullRequestListPage(first, true));
        when(requests.list(any(), eq(true), eq(2), eq(100))).thenReturn(new PullRequestListPage(ImmutableList.of(
                pr(100, "Same creation time", "bob", "open", 10, 0, 10), pr(50, "Older change", "bob", "open", 9, 0, 9)), true));
        when(requests.list(any(), eq(true), eq(3), eq(100))).thenReturn(new PullRequestListPage(
                ImmutableList.of(pr(77, "Hidden transactional change", "bob", "open", 1, 0, 1)), false));

        assertThat(service.list("workspace", REVIEW, "")).extracting(ComposerSuggestion::number).containsExactly(10, 9, 8, 7, 6, 5, 4, 3, 2, 100);
        verify(requests, never()).list(any(), eq(true), eq(3), eq(100));
        assertThat(service.list("workspace", REVIEW, "sact")).extracting(ComposerSuggestion::number).containsExactly(77);
        verify(requests, times(3)).list(any(), eq(true), anyInt(), eq(100));
    }

    @Test
    void fixReturnsNewestTenWithoutLoadingOlderPagesAndSearchesFurtherWhenNeeded()
    {
        List<RepositoryIssue> first = IntStream.rangeClosed(1, 12).map(index -> 13 - index)
                .mapToObj(number -> issue(number, "Issue " + number, number)).collect(toImmutableList());
        when(issues.list(any(), eq(Optional.empty()), eq(1))).thenReturn(new IssueSyncPage(first, ImmutableList.of(), true));
        when(issues.list(any(), eq(Optional.empty()), eq(2))).thenReturn(new IssueSyncPage(
                ImmutableList.of(issue(123, "Older transactional bug", -100)), ImmutableList.of(1000), false));

        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(12, 11, 10, 9, 8, 7, 6, 5, 4, 3);
        verify(issues, never()).list(any(), eq(Optional.empty()), eq(2));
        assertThat(service.list("workspace", FIX, "SACT")).extracting(ComposerSuggestion::number).containsExactly(123);
        assertThat(service.list("workspace", FIX, "#123")).extracting(ComposerSuggestion::number).containsExactly(123);
        verify(issues, times(2)).list(any(), eq(Optional.empty()), anyInt());
        verifyNoInteractions(requests);
    }

    @Test
    void cacheKeepsAccountsRepositoriesAndCredentialScopesSeparate()
    {
        when(issues.list(any(), eq(Optional.empty()), eq(1))).thenReturn(
                new IssueSyncPage(ImmutableList.of(issue(1, "Account one", 1)), ImmutableList.of(), false),
                new IssueSyncPage(ImmutableList.of(issue(2, "Account two", 1)), ImmutableList.of(), false),
                new IssueSyncPage(ImmutableList.of(issue(3, "Other credential scope", 1)), ImmutableList.of(), false),
                new IssueSyncPage(ImmutableList.of(issue(4, "Other repository", 1)), ImmutableList.of(), false));
        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(1);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "account", 10, "alice"));
        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(2);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "other-scope", 10, "alice"));
        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(3);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "other-scope", 11, "alice"));
        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(4);
        when(hosting.storedRead("workspace")).thenReturn(route(1, "account", 10, "alice"));
        assertThat(service.list("workspace", FIX, "")).extracting(ComposerSuggestion::number).containsExactly(1);
        verify(issues, times(4)).list(any(), eq(Optional.empty()), eq(1));
    }

    @Test
    void failedLoadsKeepTheirOriginalFailureAndAreRetried()
    {
        HostingFailure failure = new HostingFailure(FORBIDDEN, "Repository is inaccessible");
        when(requests.list(any(), anyBoolean(), anyInt(), anyInt()))
                .thenThrow(failure).thenReturn(new PullRequestListPage(ImmutableList.of(pr(1, "Change", "bob", "open", 1, 0)), false));

        assertThatThrownBy(() -> service.list("workspace", REVIEW, "")).isSameAs(failure);
        assertThat(service.list("workspace", REVIEW, "")).extracting(ComposerSuggestion::number).containsExactly(1);
        verify(requests, times(2)).list(any(), eq(true), eq(1), eq(100));
    }

    @Test
    void rejectsUnboundedSearchBeforeReading()
    {
        assertThatThrownBy(() -> service.list("workspace", FIX, "x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(hosting, integrations);
    }

    private static WorkspaceHostingRead route(long account, String scope, long repository, String login)
    {
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        HostingRepository repo = new HostingRepository(repository, 1, "100", "owner/project", URI.create("https://github.com/owner/project"),
                URI.create("https://github.com/owner/project.git"), Optional.empty(), Optional.of("main"));
        return new WorkspaceHostingRead(new HostingReadSession(account, scope, new HostingAccess(instance, "token")), repo, login);
    }

    private static FetchedPullRequest pr(int number, String title, String author, String state, int created, int merged)
    {
        return pr(number, title, author, state, created, merged, Math.max(created, merged) + 1000);
    }

    private static FetchedPullRequest pr(int number, String title, String author, String state, int created, int merged, int updated)
    {
        return new FetchedPullRequest((long) number, "owner/project", number, title, author, "https://github.com/owner/project/pull/" + number,
                NOW.plusSeconds(created), NOW.plusSeconds(updated), OTHER, ImmutableList.of(), ImmutableMap.of(), false, state, null,
                "merged".equals(state) ? NOW.plusSeconds(merged) : null, ImmutableList.of(), null, null, null);
    }

    private static RepositoryIssue issue(int number, String title, int created)
    {
        return new RepositoryIssue(number, number, title, "body", "alice", "", "https://github.com/owner/project/issues/" + number,
                ImmutableList.of(), ImmutableList.of(), 0, NOW.plusSeconds(created), NOW.plusSeconds(Math.max(1000 - created, created)));
    }
}
