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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubAccountClient.UserIdentity;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
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
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubAccountClient github = mock(GitHubAccountClient.class);
    private final RepositoryMetadataStore metadata = mock(RepositoryMetadataStore.class);
    private final TeamService service = new TeamService(store, mapper, credentials, github, metadata);

    @BeforeEach
    void setup()
    {
        account("alice", "token-a", 1);
    }

    @Test
    void persistsNormalizedPrivateCirclesAndMemberIdentity()
    {
        TeamCircle circle = service.create(" OSS friends ", "", Avatar.DEFAULT, ImmutableList.of(" Apache/Trino ", "apache/trino"), ImmutableList.of()).circles().getFirst();
        when(github.fetchUserIdentity("token-a", Optional.of("old-name"))).thenReturn(identity(42, "old-name"));
        assertThat(service.addMember(circle.id(), new TeamMemberInput(42, "OLD-NAME")).circles().getFirst().members()).extracting(TeamMember::githubId).containsExactly(42L);
        when(github.fetchUserIdentity("token-a", Optional.of("new-name"))).thenReturn(identity(42, "new-name"));
        TeamDirectory saved = service.addMember(circle.id(), new TeamMemberInput(42, "new-name"));
        assertThat(saved.circles().getFirst().name()).isEqualTo("OSS friends");
        assertThat(saved.circles().getFirst().repositories()).containsExactly("apache/trino");
        assertThat(saved.circles().getFirst().members()).extracting(TeamMember::login).containsExactly("new-name");
        assertThat(new TeamService(store, mapper, credentials, github, metadata).directory()).isEqualTo(saved);
        assertThat(service.update(circle.id(), "Maintainers", "", Avatar.DEFAULT, ImmutableList.of()).circles().getFirst().members()).hasSize(1);
        assertThat(service.removeMember(circle.id(), 42).circles().getFirst().members()).isEmpty();
        assertThat(service.remove(circle.id()).circles()).isEmpty();
    }

    @Test
    void createsDescriptionAvatarAndSelectedPeopleTogether()
    {
        when(github.fetchUserIdentity("token-a", Optional.of("bob"))).thenReturn(identity(42, "bob"));
        when(github.fetchUserIdentity("token-a", Optional.of("old-bob"))).thenReturn(identity(42, "bob"));
        when(github.fetchUserIdentity("token-a", Optional.of("cara"))).thenReturn(identity(43, "cara"));
        TeamCircle circle = service.create(" Friends ", " Trusted maintainers \n", new Avatar("teal", "circle"), ImmutableList.of(), ImmutableList.of(
                new TeamMemberInput(42, " BOB "), new TeamMemberInput(42, "bob"), new TeamMemberInput(42, "old-bob"), new TeamMemberInput(43, "cara")))
                .circles().getFirst();
        assertThat(circle.name()).isEqualTo("Friends");
        assertThat(circle.description()).isEqualTo("Trusted maintainers");
        assertThat(circle.avatar()).isEqualTo(new Avatar("teal", "circle"));
        assertThat(circle.members()).extracting(TeamMember::githubId).containsExactly(42L, 43L);
        verify(github, times(1)).fetchUserIdentity("token-a", Optional.of("bob"));
        assertThat(new TeamService(store, mapper, credentials, github, metadata).directory().circles()).containsExactly(circle);

        TeamCircle updated = service.update(circle.id(), "Maintainers", "Working together", new Avatar("pink", "square"), ImmutableList.of()).circles().getFirst();
        assertThat(updated.description()).isEqualTo("Working together");
        assertThat(updated.avatar()).isEqualTo(new Avatar("pink", "square"));
        assertThat(updated.members()).isEqualTo(circle.members());
        assertThat(service.removeMember(circle.id(), 42).circles().getFirst().description()).isEqualTo("Working together");
        assertThat(service.addMember(circle.id(), new TeamMemberInput(42, "bob")).circles().getFirst().avatar()).isEqualTo(new Avatar("pink", "square"));
        assertThatThrownBy(() -> new Avatar("chartreuse", "square")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Avatar("teal", "hexagon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedInitialMemberResolutionDoesNotCreateAPartialCircle()
    {
        TeamDirectory saved = service.create("Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        when(github.fetchUserIdentity("token-a", Optional.of("bob"))).thenReturn(identity(42, "bob"));
        when(github.fetchUserIdentity("token-a", Optional.of("missing"))).thenThrow(new IllegalStateException("GitHub user not found"));
        var selected = ImmutableList.of(new TeamMemberInput(42, "bob"), new TeamMemberInput(43, "missing"));
        assertThatThrownBy(() -> service.create("Friends", "Work", Avatar.DEFAULT, ImmutableList.of(), selected))
                .isInstanceOf(IllegalStateException.class).hasMessage("GitHub user not found");
        assertThat(service.directory()).isEqualTo(saved);
    }

    @Test
    void reusedLoginCannotReplaceTheSelectedContributorIdentity()
    {
        TeamDirectory saved = service.create("Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        when(github.fetchUserIdentity("token-a", Optional.of("bob"))).thenReturn(identity(42, "bob"));
        when(github.fetchUserIdentity("token-a", Optional.of("cara"))).thenReturn(identity(99, "cara"));
        var selected = ImmutableList.of(new TeamMemberInput(42, "bob"), new TeamMemberInput(43, "cara"));

        assertThatThrownBy(() -> service.create("Friends", "Work", Avatar.DEFAULT, ImmutableList.of(), selected))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cara couldn't be verified");
        assertThat(service.directory()).isEqualTo(saved);
        assertThatThrownBy(() -> service.addMember(saved.circles().getFirst().id(), new TeamMemberInput(43, "cara")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cara couldn't be verified");
        assertThat(service.directory()).isEqualTo(saved);
    }

    @Test
    void readsSavedCirclesWithNoDescriptionWithoutChangingMembership()
    {
        store.put("teams.directory.1", """
                {"accountLogin":"alice","circles":[{"id":"friends","name":"Friends","repositories":[],
                "members":[{"githubId":42,"login":"bob","name":"Bob","avatarUrl":""}]}]}
                """);
        TeamCircle circle = service.directory().circles().getFirst();
        assertThat(circle.description()).isEmpty();
        assertThat(circle.avatar()).isEqualTo(Avatar.DEFAULT);
        assertThat(circle.members()).containsExactly(new TeamMember(42, "bob", "Bob", ""));
    }

    @Test
    void contributorSuggestionsOnlyReadLocalMetadata()
    {
        var contributors = ImmutableList.of(new RepositoryContributor(42, "bob", "", false));
        when(metadata.findAllContributors()).thenReturn(contributors);
        assertThat(service.contributors()).isEqualTo(contributors);
        verifyNoInteractions(github, credentials);
    }

    @Test
    void accountIdentitySurvivesRenameAndSeparatesOtherAccounts()
    {
        TeamCircle circle = service.create("My circle", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        assertThat(service.accountId()).isEqualTo(1);
        service.directory();
        verify(github, times(1)).fetchUserIdentity("token-a", Optional.empty());
        account("bob", "token-b", 2);
        assertThat(service.directory().circles()).isEmpty();
        account("alice-renamed", "replacement-token", 1);
        assertThat(service.directory().circles()).containsExactly(circle);
        assertThat(service.directory().accountLogin()).isEqualTo("alice-renamed");
    }

    @Test
    void warmedDirectoryReadsDoNotCallGitHub()
    {
        TeamDirectory directory = service.create("Local", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        clearInvocations(github, credentials);
        assertThat(service.directory()).isEqualTo(directory);
        verifyNoInteractions(github);
        verify(credentials).resolveGitHubCredential();
    }

    @Test
    void rejectsInvalidInputWithoutChangingSavedCircles()
    {
        TeamDirectory saved = service.create("Saved", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of());
        String id = saved.circles().getFirst().id();
        assertThatThrownBy(() -> service.create(" ", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(id, "x".repeat(101), "", Avatar.DEFAULT, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(id, "Name", "", Avatar.DEFAULT, ImmutableList.of("owner/repo/extra"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(id, "Name", "x".repeat(2001), Avatar.DEFAULT, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("Name", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of(new TeamMemberInput(42, "https://github.com/person"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(id, new TeamMemberInput(42, "https://github.com/person"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(id, new TeamMemberInput(0, "person"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember(id, new TeamMemberInput(42, " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addMember("missing", new TeamMemberInput(42, "person"))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.directory()).isEqualTo(saved);
    }

    @Test
    void concurrentEditsDoNotLoseCircles()
            throws Exception
    {
        service.directory();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 40)
                    .mapToObj(index -> executor.submit(() -> service.create("Circle " + index, "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())))
                    .collect(toImmutableList());
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(service.directory().circles()).hasSize(40);
    }

    @Test
    void hasNoMemberCountCapAndPreservesUnreadableStorage()
    {
        TeamCircle circle = service.create("People", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        for (int id = 1; id <= 101; id++) {
            when(github.fetchUserIdentity("token-a", Optional.of("member-" + id))).thenReturn(identity(id, "member-" + id));
            service.addMember(circle.id(), new TeamMemberInput(id, "member-" + id));
        }
        assertThat(service.directory().circles().getFirst().members()).hasSize(101);
        store.put("teams.directory.1", "invalid JSON");
        assertThatThrownBy(() -> service.create("Another", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("teams.directory.1")).contains("invalid JSON");
    }

    @Test
    void cachedCirclesNeverAskGitHub()
    {
        assertThat(service.cachedCircles()).isEmpty();
        verify(credentials, never()).resolveGitHubCredential();
        verifyNoInteractions(github);

        TeamCircle circle = service.create("Local", "", Avatar.DEFAULT, ImmutableList.of(), ImmutableList.of()).circles().getFirst();
        clearInvocations(github, credentials);
        assertThat(service.cachedCircles()).containsExactly(circle);
        // Another token may belong to another account, which only GitHub can name.
        account("bob", "token-b", 2);
        assertThat(service.cachedCircles()).isEmpty();
        verify(credentials, never()).resolveGitHubCredential();
        verifyNoInteractions(github);
    }

    private void account(String login, String token, long id)
    {
        UserProfile profile = new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", "");
        GitHubCredential credential = new GitHubCredential(token, profile);
        when(credentials.resolveGitHubCredential()).thenReturn(credential);
        when(credentials.cachedCredential()).thenReturn(Optional.of(credential));
        when(github.fetchUserIdentity(token, Optional.empty())).thenReturn(identity(id, login));
    }

    private static UserIdentity identity(long id, String login)
    {
        return new UserIdentity(id, login, Optional.empty(), Optional.empty());
    }
}
