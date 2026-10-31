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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingPerson;
import com.bytequay.core.hosting.TeamActivityReader;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.integrations;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.team;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestTeamService
{
    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamAccounts accounts = mock(TeamAccounts.class);
    private final TeamActivityReader reader = mock(TeamActivityReader.class);
    private final RepositoryMetadataStore metadata = mock(RepositoryMetadataStore.class);
    private final TeamService service = new TeamService(store, mapper, accounts, integrations(team(GITHUB, reader)), metadata);

    @BeforeEach
    void setup()
    {
        signIn(account(1, "alice"));
    }

    @Test
    void persistsNormalizedPrivateCirclesAndMemberIdentity()
    {
        TeamCircle circle = service.create(1, " OSS friends ", "", Avatar.DEFAULT, ImmutableList.of(" Apache/Repository ", "apache/repository"), ImmutableList.of())
                .circles().getFirst();
        person("old-name", 42, "old-name");
        assertThat(service.addMember(1, circle.id(), new TeamMemberInput(42, "OLD-NAME")).circles().getFirst().members()).extracting(TeamMember::remoteId).containsExactly(42L);
        person("new-name", 42, "new-name");
        TeamDirectory saved = service.addMember(1, circle.id(), new TeamMemberInput(42, "new-name"));
        assertThat(saved.circles().getFirst().name()).isEqualTo("OSS friends");
        assertThat(saved.circles().getFirst().repositories()).containsExactly("apache/repository");
        assertThat(saved.circles().getFirst().members()).extracting(TeamMember::login).containsExactly("new-name");
        assertThat(saved.accountId()).isEqualTo(1);
        assertThat(saved.server()).isEqualTo(GITHUB_COM);
        assertThat(new TeamService(store, mapper, accounts, integrations(team(GITHUB, reader)), metadata).directories()).containsExactly(saved);
        assertThat(service.update(1, circle.id(), "Maintainers", "", Avatar.DEFAULT, ImmutableList.of()).circles().getFirst().members()).hasSize(1);
        assertThat(service.removeMember(1, circle.id(), 42).circles().getFirst().members()).isEmpty();
        assertThat(service.remove(1, circle.id()).circles()).isEmpty();
    }

    @Test
    void createsDescriptionAvatarAndSelectedPeopleTogether()
    {
        person("bob", 42, "bob");
        person("old-bob", 42, "bob");
        person("cara", 43, "cara");
        TeamCircle circle = service.create(1, " Friends ", " Trusted maintainers \n", new Avatar("teal", "circle"), ImmutableList.of(), ImmutableList.of(
                new TeamMemberInput(42, " BOB "), new TeamMemberInput(42, "bob"), new TeamMemberInput(42, "old-bob"), new TeamMemberInput(43, "cara")))
                .circles().getFirst();
        assertThat(circle.name()).isEqualTo("Friends");
        assertThat(circle.description()).isEqualTo("Trusted maintainers");
        assertThat(circle.avatar()).isEqualTo(new Avatar("teal", "circle"));
        assertThat(circle.members()).extracting(TeamMember::remoteId).containsExactly(42L, 43L);
        verify(reader, times(1)).person(any(), eq("bob"));
        assertThat(directory().circles()).containsExactly(circle);

        TeamCircle updated = service.update(1, circle.id(), "Maintainers", "Working together", new Avatar("pink", "square"), ImmutableList.of()).circles().getFirst();
        assertThat(updated.description()).isEqualTo("Working together");
        assertThat(updated.avatar()).isEqualTo(new Avatar("pink", "square"));
        assertThat(updated.members()).isEqualTo(circle.members());
        assertThat(service.removeMember(1, circle.id(), 42).circles().getFirst().description()).isEqualTo("Working together");
        assertThat(service.addMember(1, circle.id(), new TeamMemberInput(42, "bob")).circles().getFirst().avatar()).isEqualTo(new Avatar("pink", "square"));
        assertThatThrownBy(() -> new Avatar("chartreuse", "square")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Avatar("teal", "hexagon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesPeopleWithTheOwningAccount()
    {
        TeamAccount owner = account(1, "alice");
        signIn(owner, account(2, "bob"));
        person("cara", 43, "cara");

        service.create(1, "Friends", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(new TeamMemberInput(43, "cara")));

        verify(reader).person(owner.session().access(), "cara");
    }

    @Test
    void acceptsNestedRepositoryPathsAndUsernamesWithDots()
    {
        person("first.last", 44, "first.last");

        TeamCircle circle = service.create(1, "Platform", "", Avatar.DEFAULT, ImmutableList.of("Group/Sub/Project"), ImmutableList.of(new TeamMemberInput(44, "First.Last")))
                .circles().getFirst();

        assertThat(circle.repositories()).containsExactly("group/sub/project");
        assertThat(circle.members()).extracting(TeamMember::login).containsExactly("first.last");
    }

    @Test
    void failedInitialMemberResolutionDoesNotCreateAPartialCircle()
    {
        TeamDirectory saved = service.create(1, "Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        person("bob", 42, "bob");
        when(reader.person(any(), eq("missing"))).thenThrow(new IllegalStateException("User not found"));
        var selected = ImmutableList.of(new TeamMemberInput(42, "bob"), new TeamMemberInput(43, "missing"));
        assertThatThrownBy(() -> service.create(1, "Friends", "Work", Avatar.DEFAULT, ImmutableList.of(), selected))
                .isInstanceOf(IllegalStateException.class).hasMessage("User not found");
        assertThat(directory()).isEqualTo(saved);
    }

    @Test
    void reusedLoginCannotReplaceTheSelectedContributorIdentity()
    {
        TeamDirectory saved = service.create(1, "Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        person("bob", 42, "bob");
        person("cara", 99, "cara");
        var selected = ImmutableList.of(new TeamMemberInput(42, "bob"), new TeamMemberInput(43, "cara"));

        assertThatThrownBy(() -> service.create(1, "Friends", "Work", Avatar.DEFAULT, ImmutableList.of(), selected))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cara couldn't be verified");
        assertThat(directory()).isEqualTo(saved);
        assertThatThrownBy(() -> service.addMember(1, saved.circles().getFirst().id(), new TeamMemberInput(43, "cara")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cara couldn't be verified");
        assertThat(directory()).isEqualTo(saved);
    }

    @Test
    void readsSavedCirclesWithNoDescriptionWithoutChangingMembership()
    {
        store.put("teams.directory.1", """
                {"circles":[{"id":"friends","name":"Friends","repositories":[],
                "members":[{"remoteId":42,"login":"bob","name":"Bob","avatarUrl":""}]}]}
                """);
        TeamCircle circle = directory().circles().getFirst();
        assertThat(circle.description()).isEmpty();
        assertThat(circle.avatar()).isEqualTo(Avatar.DEFAULT);
        assertThat(circle.members()).containsExactly(new TeamMember(42, "bob", "Bob", ""));
    }

    @Test
    void contributorSuggestionsOnlyReadLocalMetadata()
    {
        var contributors = ImmutableList.of(new RepositoryContributor(42, "bob", "", false));
        when(metadata.findAllContributors()).thenReturn(contributors);
        assertThat(service.contributors(1)).isEqualTo(contributors);
        verifyNoInteractions(reader);
    }

    @Test
    void eachAccountKeepsItsOwnCirclesAcrossARename()
    {
        TeamCircle circle = service.create(1, "My circle", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        signIn(account(1, "alice"), account(2, "bob"));
        assertThat(service.directories()).extracting(TeamDirectory::accountLogin).containsExactly("alice", "bob");
        assertThat(service.directories().get(1).circles()).isEmpty();
        signIn(account(1, "alice-renamed"));
        assertThat(directory().circles()).containsExactly(circle);
        assertThat(directory().accountLogin()).isEqualTo("alice-renamed");
    }

    @Test
    void directoryReadsDoNotCallTheProvider()
    {
        TeamDirectory directory = service.create(1, "Local", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        assertThat(directory()).isEqualTo(directory);
        verifyNoInteractions(reader);
    }

    @Test
    void rejectsInvalidInputWithoutChangingSavedCircles()
    {
        TeamDirectory saved = service.create(1, "Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        String id = saved.circles().getFirst().id();
        assertThatThrownBy(() -> service.create(1, " ", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, id, "x".repeat(101), "", Avatar.DEFAULT, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, id, "Name", "", Avatar.DEFAULT, ImmutableList.of("owner"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, id, "Name", "", Avatar.DEFAULT, ImmutableList.of("owner/../repo"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, id, "Name", "x".repeat(2001), Avatar.DEFAULT, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(1, "Name", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(new TeamMemberInput(42, "https://example.com/person"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(1, id, new TeamMemberInput(42, "https://example.com/person"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(1, id, new TeamMemberInput(0, "person"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(1, id, new TeamMemberInput(42, " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(1, "missing", new TeamMemberInput(42, "person"))).isInstanceOf(ResponseStatusException.class);
        assertThat(directory()).isEqualTo(saved);
    }

    @Test
    void concurrentEditsDoNotLoseCircles()
            throws Exception
    {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 40)
                    .mapToObj(index -> executor.submit(() -> service.create(1, "Circle " + index, "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())))
                    .collect(toImmutableList());
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(directory().circles()).hasSize(40);
    }

    @Test
    void hasNoMemberCountCapAndPreservesUnreadableStorage()
    {
        TeamCircle circle = service.create(1, "People", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        for (int id = 1; id <= 101; id++) {
            person("member-" + id, id, "member-" + id);
            service.addMember(1, circle.id(), new TeamMemberInput(id, "member-" + id));
        }
        assertThat(directory().circles().getFirst().members()).hasSize(101);
        store.put("teams.directory.1", "invalid JSON");
        assertThatThrownBy(() -> service.create(1, "Another", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("teams.directory.1")).contains("invalid JSON");
    }

    @Test
    void cachedCirclesReadOnlyAccountsKnownWithoutTheProvider()
    {
        TeamCircle circle = service.create(1, "Local", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        assertThat(service.cachedCircles()).containsExactly(circle);
        when(accounts.cached()).thenReturn(ImmutableList.of());
        assertThat(service.cachedCircles()).isEmpty();
        verify(accounts, never()).all();
        verifyNoInteractions(reader);
    }

    private TeamDirectory directory()
    {
        return service.directories().getFirst();
    }

    private void person(String login, long id, String canonical)
    {
        when(reader.person(any(), eq(login))).thenReturn(new HostingPerson(id, canonical, canonical, ""));
    }

    private void signIn(TeamAccount... signedIn)
    {
        ImmutableList<TeamAccount> all = ImmutableList.copyOf(signedIn);
        when(accounts.all()).thenReturn(all);
        when(accounts.cached()).thenReturn(all);
        for (TeamAccount account : all) {
            when(accounts.require(account.id())).thenReturn(account);
        }
    }

    private static TeamAccount account(long id, String login)
    {
        HostingAccess access = new HostingAccess(new HostingInstance(1, GITHUB, GITHUB_COM.webBaseUrl(), GITHUB_COM.apiBaseUrl()), login + "-token");
        return new TeamAccount(new HostingReadSession(id, "github-global", access), new HostingAccount(id, 1, Long.toString(id), login));
    }
}
