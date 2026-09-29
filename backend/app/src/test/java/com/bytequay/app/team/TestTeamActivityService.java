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
package com.bytequay.app.team;

import com.bytequay.app.repository.github.GitHubTeamActivityClient;
import com.bytequay.app.repository.github.GitHubTeamActivityClient.Observation;
import com.bytequay.app.scheduler.TeamActivitySyncJob;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.team.TeamActivitySnapshot.Statistics;
import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.TeamActivityStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTeamActivityService
{
    private static final TeamMember ALICE = new TeamMember(2, "alice", "Alice", "");
    private static final TeamMember BOB = new TeamMember(3, "bob", "Bob", "");

    @Test
    void statisticsSeparateAuthorshipMergingReviewsAndDirectVersusSharedWork()
    {
        Fixture fixture = fixture();
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(
                new TeamCircle("all", "All", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(ALICE, BOB)))));
        when(fixture.store().findSince(anyLong(), any())).thenReturn(ImmutableList.of(
                event("opened", "PR_OPENED", 2, 2, 7, "owner/repo", ImmutableList.of()),
                event("merged", "PR_MERGED", 3, 2, 7, "owner/repo", ImmutableList.of()),
                event("review1", "PR_REVIEWED", 2, 1, 8, "owner/repo", ImmutableList.of(2L)),
                event("review2", "PR_REVIEWED", 2, 1, 8, "owner/repo", ImmutableList.of(2L)),
                event("comment", "PR_COMMENT", 1, 2, 7, "owner/repo", ImmutableList.of())));
        TeamActivitySnapshot snapshot = fixture.service().snapshot(Optional.empty(), Optional.empty(), 7, 100, "all");
        assertThat(snapshot.people().getFirst().statistics()).satisfies(person -> {
            assertThat(person.prsOpened()).isEqualTo(1);
            assertThat(person.authoredPrsMerged()).isEqualTo(1);
            assertThat(person.mergesPerformed()).isZero();
            assertThat(person.prsReviewed()).isEqualTo(1);
            assertThat(person.directInteractions()).isEqualTo(2);
            assertThat(person.sharedPullRequests()).isEqualTo(2);
        });
        assertThat(snapshot.people().get(1).statistics().mergesPerformed()).isEqualTo(1);
        assertThat(snapshot.people().get(1).statistics().authoredPrsMerged()).isZero();
        assertThat(snapshot.people().get(1).statistics().sharedPullRequests()).isEqualTo(1);
        // Both people share #7, so the scope counts it once.
        assertThat(snapshot.totals()).isEqualTo(new Statistics(1, 1, 1, 1, 0, 0, 0, 2, 2, 0));
        assertThat(snapshot.people().getFirst().lastActiveAt()).isPresent();
    }

    @Test
    void computesStatisticsBeforeDisplayLimitAndKeepsMemberRepositoryScopesIndependent()
    {
        Fixture fixture = fixture();
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(
                new TeamCircle("alice", "Alice scope", "", Avatar.DEFAULT, ImmutableList.of("owner/repo"), ImmutableList.of(ALICE)),
                new TeamCircle("bob", "Bob everywhere", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(BOB)))));
        when(fixture.store().findSince(anyLong(), any())).thenReturn(ImmutableList.of(
                event("one", "PR_OPENED", 2, 2, 7, "owner/repo", ImmutableList.of()),
                event("two", "PR_OPENED", 2, 2, 8, "owner/repo", ImmutableList.of()),
                event("wrong-scope", "PR_OPENED", 2, 2, 9, "other/repo", ImmutableList.of()),
                event("other-circle-merger", "PR_MERGED", 3, 2, 9, "other/repo", ImmutableList.of())));
        TeamActivitySnapshot snapshot = fixture.service().snapshot(Optional.empty(), Optional.empty(), 7, 1, "all");
        assertThat(snapshot.events()).hasSize(1);
        // Bob's merge in his wider scope is his update; alice's own PR there is outside hers.
        assertThat(snapshot.updates()).isEqualTo(3);
        assertThat(snapshot.workItems()).isEqualTo(3);
        assertThat(snapshot.people().getFirst().statistics().prsOpened()).isEqualTo(2);
        assertThat(snapshot.coverage().partial()).isTrue();
        verify(fixture.store()).findSince(eq(1L), any());
        assertThat(fixture.service().snapshot(Optional.empty(), Optional.empty(), 7, 100, "authored-merges").events()).isEmpty();
        assertThatThrownBy(() -> fixture.service().snapshot(Optional.empty(), Optional.empty(), 91, 100, "all")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void savedCursorContinuesRoundRobinAfterAServiceRestart()
    {
        Fixture fixture = fixture();
        List<TeamMember> members = ImmutableList.of(ALICE, BOB, new TeamMember(4, "carol", "", ""), new TeamMember(5, "dan", "", ""),
                new TeamMember(6, "eve", "", ""), new TeamMember(7, "frank", "", ""));
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(new TeamCircle("all", "All", "", Avatar.DEFAULT, ImmutableList.of(), members))));
        when(fixture.github().publicEvents(anyString(), anyString(), any())).thenReturn(new Observation(ImmutableList.of(), ImmutableList.of(), false));
        fixture.service().collect();
        TeamActivityService restarted = new TeamActivityService(fixture.teams(), fixture.credentials(), fixture.workspaces(), fixture.github(), fixture.store(),
                fixture.settings(), mapper(), mock(PullRequestStore.class), mock(PrDetailStore.class));
        restarted.collect();
        ArgumentCaptor<String> logins = ArgumentCaptor.forClass(String.class);
        verify(fixture.github(), times(8)).publicEvents(anyString(), logins.capture(), any());
        assertThat(logins.getAllValues()).containsExactly("alice", "bob", "carol", "dan", "eve", "frank", "alice", "bob");
    }

    @Test
    void metricFiltersSelectMatchingFactsBeforeLimitWithoutChangingStatistics()
    {
        Fixture fixture = fixture();
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(
                new TeamCircle("all", "All", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(ALICE, BOB)))));
        TeamActivityEvent authoredMerge = new TeamActivityEvent("authored", "PR_MERGED", 3, "bob", "owner/repo", 7, "Title",
                "https://github.com/owner/repo/pull/7", Instant.now().minusSeconds(3600), Optional.of(2L), Optional.of("alice"), "REPOSITORY", ImmutableList.of());
        when(fixture.store().findSince(anyLong(), any())).thenReturn(ImmutableList.of(
                authoredMerge,
                event("opened-pr", "PR_OPENED", 2, 2, 12, "owner/repo", ImmutableList.of()),
                event("performed-merge", "PR_MERGED", 2, 3, 8, "owner/repo", ImmutableList.of()),
                event("performed-review", "PR_REVIEWED", 2, 1, 9, "owner/repo", ImmutableList.of()),
                event("received-review", "PR_REVIEWED", 3, 2, 7, "owner/repo", ImmutableList.of()),
                event("opened-issue", "ISSUE_OPENED", 2, 2, 10, "owner/repo", ImmutableList.of()),
                event("closed-issue", "ISSUE_CLOSED", 2, 2, 11, "owner/repo", ImmutableList.of()),
                event("own-comment", "ISSUE_COMMENT", 2, 3, 13, "owner/repo", ImmutableList.of()),
                event("other-interaction", "PR_COMMENT", 1, 2, 7, "owner/repo", ImmutableList.of(3L)),
                event("direct-interaction", "PR_COMMENT", 1, 3, 8, "owner/repo", ImmutableList.of(2L))));

        TeamActivitySnapshot all = fixture.service().snapshot(Optional.empty(), Optional.of(2L), 7, 1, "all");
        assertThat(all.events()).doesNotContain(authoredMerge);
        assertThat(all.updates()).isGreaterThan(all.events().size());
        Map<String, List<String>> metricFacts = ImmutableMap.<String, List<String>>builder()
                .put("prs-opened", ImmutableList.of("opened-pr"))
                .put("authored-merges", ImmutableList.of("authored"))
                .put("merges-performed", ImmutableList.of("performed-merge"))
                .put("reviews-performed", ImmutableList.of("performed-review"))
                .put("issues-opened", ImmutableList.of("opened-issue"))
                .put("issues-closed", ImmutableList.of("closed-issue"))
                .put("comments", ImmutableList.of("own-comment"))
                .put("interactions", ImmutableList.of("direct-interaction"))
                // The viewer reviewed or commented on #7, #8 and #9; everything alice did there is shared work.
                .put("shared", ImmutableList.of("authored", "performed-merge", "performed-review", "received-review", "other-interaction", "direct-interaction"))
                .buildOrThrow();
        metricFacts.forEach((filter, ids) -> {
            TeamActivitySnapshot filtered = fixture.service().snapshot(Optional.empty(), Optional.of(2L), 7, 100, filter);
            assertThat(filtered.events()).extracting(TeamActivityEvent::id).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(filtered.updates()).isEqualTo(ids.size());
            assertThat(filtered.people()).isEqualTo(all.people());
        });
        assertThat(all.people().getFirst().statistics()).isEqualTo(new Statistics(1, 1, 1, 1, 1, 1, 1, 1, 3, 0));
        ImmutableMap.of("merged", 2, "reviews", 2, "issues", 3).forEach((filter, count) ->
                assertThat(fixture.service().snapshot(Optional.empty(), Optional.of(2L), 7, 100, filter).events()).hasSize(count));
    }

    @Test
    void daysOutsideTheCollectedWindowAreUnknownRatherThanQuiet()
    {
        Fixture fixture = fixture();
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(
                new TeamCircle("all", "All", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(ALICE)))));
        when(fixture.store().findSince(anyLong(), any())).thenReturn(ImmutableList.of(event("today", "PR_OPENED", 2, 2, 7, "owner/repo", ImmutableList.of())));
        assertThat(fixture.service().snapshot(Optional.empty(), Optional.empty(), 7, 100, "all").days())
                .hasSize(7).allSatisfy(day -> assertThat(day.collected()).isFalse());

        when(fixture.github().publicEvents(anyString(), anyString(), any())).thenReturn(new Observation(ImmutableList.of(), ImmutableList.of(), false));
        fixture.service().collect();
        TeamActivitySnapshot collected = fixture.service().snapshot(Optional.empty(), Optional.empty(), 90, 100, "all");
        assertThat(collected.days()).hasSize(90);
        assertThat(collected.days().getLast()).satisfies(day -> {
            assertThat(day.date()).isEqualTo(LocalDate.now());
            assertThat(day.updates()).isEqualTo(1);
            assertThat(day.collected()).isTrue();
        });
        // The first pass looks back 30 days; anything older was never observed.
        assertThat(collected.days().get(90 - 31).collected()).isTrue();
        assertThat(collected.days().get(90 - 32).collected()).isFalse();
        assertThat(collected.coverage()).satisfies(coverage -> {
            assertThat(coverage.partial()).isFalse();
            assertThat(coverage.membersScanned()).isEqualTo(1);
        });
    }

    @Test
    void invalidActivityFilterIsRejectedBeforeCollection()
    {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.service().refresh(Optional.empty(), Optional.empty(), 7, 100, "unknown"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(fixture.credentials(), never()).resolveGitHubCredential();
        verify(fixture.github(), never()).publicEvents(anyString(), anyString(), any());
    }

    @Test
    void accountSwitchDuringCollectionRefusesObservationAndCoverageWrites()
    {
        Fixture fixture = fixture();
        when(fixture.teams().directory()).thenReturn(new TeamDirectory("viewer", ImmutableList.of(
                new TeamCircle("all", "All", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(ALICE)))));
        when(fixture.github().publicEvents(anyString(), anyString(), any())).thenAnswer(invocation -> {
            when(fixture.credentials().resolveGitHubCredential()).thenReturn(new GitHubCredential("other", new UserProfile("other", "", "", "", 0, 0, 0, "", "", "", "")));
            return new Observation(ImmutableList.of(event("one", "PR_OPENED", 2, 2, 7, "owner/repo", ImmutableList.of())), ImmutableList.of(), false);
        });
        assertThatThrownBy(() -> fixture.service().collect()).isInstanceOf(ResponseStatusException.class).hasMessageContaining("account changed");
        verify(fixture.store(), never()).save(anyLong(), any());
        verify(fixture.settings(), never()).put(anyString(), anyString());
    }

    @Test
    void automaticCollectionRespectsSharedQuietHours()
    {
        TeamActivityService activity = mock(TeamActivityService.class);
        CredentialService credentials = mock(CredentialService.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        when(settings.inQuietHours()).thenReturn(true);
        new TeamActivitySyncJob(activity, credentials, settings).tick();
        verify(activity, never()).collect();
    }

    private static ObjectMapper mapper()
    {
        return new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    }

    private static Fixture fixture()
    {
        TeamService teams = mock(TeamService.class);
        CredentialService credentials = mock(CredentialService.class);
        TeamActivityStore store = mock(TeamActivityStore.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        AppSettingsStore settings = mock(AppSettingsStore.class);
        Map<String, String> saved = new LinkedHashMap<>();
        when(settings.get(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            saved.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        when(workspaces.findAll()).thenReturn(ImmutableList.of());
        when(teams.accountId()).thenReturn(1L);
        GitHubCredential credential = new GitHubCredential("token", new UserProfile("viewer", "", "", "", 0, 0, 0, "", "", "", ""));
        when(credentials.resolveGitHubCredential()).thenReturn(credential);
        GitHubTeamActivityClient github = mock(GitHubTeamActivityClient.class);
        TeamActivityService service = new TeamActivityService(teams, credentials, workspaces, github, store, settings,
                mapper(), mock(PullRequestStore.class), mock(PrDetailStore.class));
        return new Fixture(teams, store, service, credentials, github, settings, workspaces);
    }

    private static TeamActivityEvent event(String id, String kind, long actor, long author, int number, String repository, List<Long> direct)
    {
        return new TeamActivityEvent(id, kind, actor, "actor", repository, number, "Title", "https://github.com/" + repository + "/pull/" + number,
                Instant.now(), Optional.of(author), Optional.of("author"), "REPOSITORY", direct);
    }

    private record Fixture(TeamService teams, TeamActivityStore store, TeamActivityService service, CredentialService credentials,
            GitHubTeamActivityClient github, AppSettingsStore settings, WorkspaceStore workspaces) {}
}
