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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.persistence.sqlite.credential.SqliteHostingCredentialStore;
import com.bytequay.persistence.sqlite.entity.HostingRepositoryEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.entity.RepositoryIssueSyncEntity;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TestSqliteWorkspaceHostingBinding
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final HostingServer GITHUB = new HostingServer(HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));

    @Autowired
    private SqliteWorkspaceStore store;

    @Autowired
    private SqliteHostingIdentityStore identities;

    @Autowired
    private SqliteHostingCredentialStore credentials;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void attachesRenamedRepositoryWithoutChangingWorkspaceData()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "renamed/project");

        WorkspaceHostingBinding binding = store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty());
        entities.flush();
        entities.clear();

        assertThat(binding).isEqualTo(new WorkspaceHostingBinding(workspace.id(), account.id(), selected.id(), Optional.empty()));
        assertThat(binding.monitoredRepositoryId()).isEqualTo(selected.id());
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
        assertThat(store.findById(workspace.id())).contains(workspace);
        assertThat(store.findBySelectedRepository(HostingServer.GITHUB_COM, 100)).contains(workspace);
        Workspace renamed = store.save(workspace.withName("Local work retained", NOW.plusSeconds(1)));
        assertThat(renamed.localRepositoryPath()).isEqualTo(workspace.localRepositoryPath());
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
    }

    @Test
    void keepsBindingAcrossRepositoryRenameAndUnwatch()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");
        WorkspaceHostingBinding binding = store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty());
        repository(instance, "100", "new-owner/renamed");
        RepoRef renamed = RepoRef.of("new-owner", "renamed");
        Workspace updated = store.save(workspace.withRepositoryRouting(renamed, Optional.empty(), Optional.empty(), 100, renamed, "main",
                "https://github.com/new-owner/renamed.git", Optional.empty(), Optional.empty(), NOW.plusSeconds(1)));
        store.setWatched(workspace.id(), false);
        entities.flush();
        entities.clear();

        assertThat(store.findAll()).isEmpty();
        assertThat(store.findAllRetained()).containsExactly(updated);
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
        store.setWatched(workspace.id(), true);
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
    }

    @Test
    void invalidatesBindingWhenUpstreamChanges()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");
        HostingRepository upstream = repository(instance, "200", "upstream/project");
        store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty());

        store.save(withUpstream(workspace));
        entities.flush();
        entities.clear();
        assertThat(store.findHostingBinding(workspace.id())).isEmpty();
        WorkspaceHostingBinding linked = store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.of(upstream.id()));
        assertThat(linked.monitoredRepositoryId()).isEqualTo(upstream.id());

        store.save(workspace);
        assertThat(store.findHostingBinding(workspace.id())).isEmpty();
    }

    @Test
    void rejectsMismatchedRepositoryAndUpstreamWithoutChangingBinding()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");
        HostingRepository wrong = repository(instance, "999", "owner/project");
        WorkspaceHostingBinding binding = store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty());

        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), wrong.id(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.of(wrong.id())));
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
        store.save(withUpstream(workspace));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.of(wrong.id())));
        assertThat(store.findHostingBinding(workspace.id())).isEmpty();
    }

    @Test
    void rejectsRepositoryFromAnotherInstanceWithTheSameRemoteId()
    {
        Workspace workspace = create(workspace());
        WorkspaceHostingBinding binding = store.findHostingBinding(workspace.id()).orElseThrow();
        HostingAccount account = account(identities.upsertInstance(GITHUB), "1");
        HostingInstance other = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        HostingRepository selected = repository(other, "100", "owner/project");

        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty()))
                .withMessage("Hosting repository belongs to another instance");
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
    }

    @ParameterizedTest
    @MethodSource("unsupportedServers")
    void rejectsServersOutsideTheLegacyGithubComBoundary(HostingServer server)
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(server);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");

        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty()))
                .withMessage("Hosting account belongs to another workspace server");
    }

    @Test
    void retainsDisconnectedIdentitiesAndDoesNotDeleteThemWithTheWorkspace()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");
        credentials.save(account.id(), "workspace-token", Source.TOKEN);
        credentials.delete(account.id());
        WorkspaceHostingBinding binding = store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.empty());
        assertThat(store.findHostingBinding(workspace.id())).contains(binding);
        assertThat(credentials.find(account.id())).isEmpty();

        store.delete(workspace.id());
        entities.flush();
        entities.clear();
        assertThat(store.findById(workspace.id())).isEmpty();
        assertThat(store.findHostingBinding(workspace.id())).isEmpty();
        assertThat(identities.findAccount(account.id())).contains(account);
        assertThat(identities.findRepository(selected.id())).contains(selected);
    }

    @Test
    void rejectsUnknownAndInvalidIdentities()
    {
        Workspace workspace = create(workspace());
        HostingInstance instance = identities.upsertInstance(GITHUB);
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");

        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting("missing", account.id(), selected.id(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), 999, selected.id(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), 999, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), 0, selected.id(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), 0, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), account.id(), selected.id(), Optional.of(0L)));
    }

    @Test
    void keepsIdenticalRemoteIdsOnDifferentServersSeparate()
    {
        Workspace github = create(workspace());
        HostingInstance publicInstance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        HostingInstance privateInstance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://git.company.test:8443/gitlab"), URI.create("https://git.company.test:8443/gitlab/api/v4")));
        HostingAccount publicAccount = account(publicInstance, "1");
        HostingAccount privateAccount = account(privateInstance, "1");
        HostingRepository publicRepository = repository(publicInstance, "100", "group/subgroup/project");
        HostingRepository privateRepository = repository(privateInstance, "100", "group/subgroup/project");

        Workspace publicWorkspace = store.createHosted(hosted("public", publicInstance, publicRepository), publicAccount.id(), publicRepository.id(), Optional.empty());
        Workspace privateWorkspace = store.createHosted(hosted("private", privateInstance, privateRepository), privateAccount.id(), privateRepository.id(), Optional.empty());
        entities.flush();
        entities.clear();

        assertThat(store.findAllRetained()).containsExactlyInAnyOrder(github, publicWorkspace, privateWorkspace);
        assertThat(store.findBySelectedRepository(HostingServer.GITHUB_COM, 100)).contains(github);
        assertThat(store.findBySelectedHostingRepositoryId(publicRepository.id())).contains(publicWorkspace);
        assertThat(store.findBySelectedHostingRepositoryId(privateRepository.id())).contains(privateWorkspace);
        assertThat(store.findHostingBinding(publicWorkspace.id())).contains(new WorkspaceHostingBinding("public", publicAccount.id(), publicRepository.id(), Optional.empty()));
        assertThat(publicWorkspace.withName("Renamed", NOW).server()).isEqualTo(publicWorkspace.server());
    }

    @Test
    void restoresHostedWorkspaceWithoutDuplicatingItAndCanSelectAnotherAccount()
    {
        HostingInstance instance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        HostingAccount first = account(instance, "1");
        HostingAccount second = account(instance, "2");
        HostingRepository selected = repository(instance, "100", "group/project");
        Workspace workspace = store.createHosted(hosted("original", instance, selected), first.id(), selected.id(), Optional.empty());
        store.setWatched(workspace.id(), false);

        Workspace restored = store.createHosted(hosted("another", instance, selected), second.id(), selected.id(), Optional.empty());
        entities.flush();
        entities.clear();

        assertThat(restored).isEqualTo(workspace);
        assertThat(store.findAll()).containsExactly(workspace);
        assertThat(store.findHostingBinding(workspace.id()).orElseThrow().accountId()).isEqualTo(second.id());
        assertThat(store.findBySelectedRepository(HostingServer.GITHUB_COM, 100)).isEmpty();
    }

    @Test
    void rejectsHostedBindingFromAnotherServerWithoutReplacingTheAccount()
    {
        HostingInstance instance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        HostingInstance other = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://other.test"), URI.create("https://other.test/api/v4")));
        HostingAccount first = account(instance, "1");
        HostingAccount second = account(other, "1");
        HostingRepository selected = repository(instance, "100", "group/project");
        HostingRepository otherSelected = repository(other, "100", "group/project");
        Workspace workspace = store.createHosted(hosted("original", instance, selected), first.id(), selected.id(), Optional.empty());

        assertThatIllegalArgumentException().isThrownBy(() -> store.bindHosting(workspace.id(), second.id(), otherSelected.id(), Optional.empty()))
                .withMessage("Hosting account belongs to another workspace server");
        assertThat(store.findHostingBinding(workspace.id()).orElseThrow().accountId()).isEqualTo(first.id());
    }

    @Test
    void deletingAWorkspaceRemovesItsRepositoryRequestsButKeepsAccountRowsAndOtherServers()
    {
        HostingInstance instance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        HostingInstance gitHub = identities.upsertInstance(new HostingServer(HostingProvider.GITHUB,
                URI.create("https://github.com"), URI.create("https://api.github.com")));
        HostingAccount account = account(instance, "1");
        HostingRepository selected = repository(instance, "100", "owner/project");
        HostingRepository samePath = repository(gitHub, "100", "owner/project");
        Workspace workspace = store.createHosted(hosted("gitlab", instance, selected), account.id(), selected.id(), Optional.empty());
        RepositoryIssueSyncEntity issueSync = new RepositoryIssueSyncEntity();
        issueSync.setRepositoryId(100L);
        issueSync.setSyncedAt(NOW);
        entities.persist(issueSync);
        long owned = request(selected, 7, false, false);
        long dashboard = request(selected, 8, true, false);
        long monitored = request(selected, 9, false, true);
        long otherServer = request(samePath, 7, false, false);
        entities.flush();
        jdbc.update("INSERT INTO pr_view_state (pr_id, viewed_at) VALUES (?, ?)", owned, NOW.toString());
        jdbc.update("""
                INSERT INTO recent_touch (id, item_type, item_key, touch_day, touched_at, reason, reason_count, item_number, title, repo, mine)
                VALUES (?, 'PULL_REQUEST', ?, '2026-08-21', ?, 'COMMENTED', 1, 7, 'Title', 'owner/project', 1)
                """, "pr:" + owned + "@2026-08-21", "pr:" + owned, NOW.toString());

        store.delete(workspace.id());
        entities.clear();

        assertThat(store.findById(workspace.id())).isEmpty();
        assertThat(entities.find(RepositoryIssueSyncEntity.class, 100L)).isNotNull();
        assertThat(jdbc.queryForList("SELECT id FROM pull_requests ORDER BY id", Long.class)).containsExactly(dashboard, monitored, otherServer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pr_view_state", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recent_touch", Integer.class)).isZero();
    }

    private long request(HostingRepository repository, int number, boolean dashboard, boolean monitored)
    {
        PullRequestEntity request = new PullRequestEntity();
        request.setHostingRepository(entities.getReference(HostingRepositoryEntity.class, repository.id()));
        request.setRepo(repository.fullPath());
        request.setNumber(number);
        request.setTitle("Request " + number);
        request.setOrigin("OTHER");
        request.setLabels(ImmutableList.of());
        request.setSyncedAt(NOW);
        request.setDashboardVisible(dashboard);
        request.setMonitoredAt(monitored ? NOW : null);
        entities.persist(request);
        return request.getId();
    }

    private static Workspace hosted(String id, HostingInstance instance, HostingRepository repository)
    {
        int slash = repository.fullPath().lastIndexOf('/');
        RepoRef coordinates = RepoRef.of(repository.fullPath().substring(0, slash), repository.fullPath().substring(slash + 1));
        long remoteId = Long.parseLong(repository.remoteId());
        return new Workspace(id, repository.fullPath(), remoteId, coordinates, Optional.empty(), Optional.empty(), remoteId, coordinates, Workspace.Topology.DIRECT,
                "main", repository.httpCloneUrl().toString(), Optional.empty(), Optional.empty(), "/tmp/" + id, Optional.empty(), Optional.empty(), 0, NOW, NOW,
                new HostingServer(instance.provider(), instance.webBaseUrl(), instance.apiBaseUrl()));
    }

    private static Stream<HostingServer> unsupportedServers()
    {
        return Stream.of(
                new HostingServer(HostingProvider.GITLAB, URI.create("https://github.com"), URI.create("https://api.github.com")),
                new HostingServer(HostingProvider.GITHUB, URI.create("https://git.company.com"), URI.create("https://git.company.com/api/v3")),
                new HostingServer(HostingProvider.GITHUB, URI.create("https://github.com/company"), URI.create("https://api.github.com")),
                new HostingServer(HostingProvider.GITHUB, URI.create("https://github.com:8443"), URI.create("https://api.github.com")));
    }

    private HostingAccount account(HostingInstance instance, String remoteId)
    {
        return identities.upsertAccount(instance.id(), new RemoteHostingAccount(remoteId, "user-" + remoteId));
    }

    private HostingRepository repository(HostingInstance instance, String remoteId, String path)
    {
        return identities.upsertRepository(instance.id(), new RemoteHostingRepository(remoteId, path, URI.create(instance.webBaseUrl() + "/" + path),
                URI.create(instance.webBaseUrl() + "/" + path + ".git"), Optional.empty(), Optional.of("main")));
    }

    private Workspace create(Workspace workspace)
    {
        HostingInstance instance = identities.upsertInstance(workspace.server());
        HostingRepository selected = repository(instance, Long.toString(workspace.selectedRepositoryId()), workspace.selectedRepository().fullName());
        return store.createHosted(workspace, account(instance, "1").id(), selected.id(), Optional.empty());
    }

    private static Workspace workspace()
    {
        RepoRef repository = RepoRef.of("owner", "project");
        return new Workspace("workspace", "Project", 100, repository, Optional.empty(), Optional.empty(), 100, repository, Workspace.Topology.DIRECT,
                "main", "https://github.com/owner/project.git", Optional.empty(), Optional.empty(), "/retained/workspace", Optional.empty(), Optional.empty(), 0, NOW, NOW,
                HostingServer.GITHUB_COM);
    }

    private static Workspace withUpstream(Workspace workspace)
    {
        RepoRef upstream = RepoRef.of("upstream", "project");
        return workspace.withRepositoryRouting(workspace.selectedRepository(), Optional.of(200L), Optional.of(upstream), 200, upstream, "main", workspace.originCloneUrl(),
                Optional.of("main"), Optional.of("https://github.com/upstream/project.git"), NOW.plusSeconds(1));
    }
}
