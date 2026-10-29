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
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteWorkspaceStoreUpgrade
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_URL = newDatabaseUrl("bytequay-workspace-upgrade-") + "?foreign_keys=ON";
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final HostingServer GITLAB = new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.example.test"), URI.create("https://gitlab.example.test/api/v4"));

    @Autowired
    private SqliteWorkspaceStore store;

    @Autowired
    private WorkspaceJpaRepository repository;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createV052Table();
        registerSqliteProperties(registry, DATABASE_URL, "update");
    }

    @Test
    void retainsExistingWorkspaceAndAllowsTheSameNumberOnAnotherServer()
    {
        Workspace existing = workspace("existing", Optional.empty());
        assertThat(store.findById(existing.id())).contains(existing);

        Workspace hosted = store.createOrGet(workspace("hosted", Optional.of(GITLAB)));
        entities.flush();
        entities.clear();

        assertThat(store.findAllRetained()).containsExactlyInAnyOrder(existing, hosted);
        assertThat(store.findBySelectedRepositoryId(100)).contains(existing);
    }

    @Test
    void keepsServerScopedRepositoryKeysUnique()
    {
        store.createOrGet(workspace("first", Optional.of(GITLAB)));

        assertThatThrownBy(() -> repository.saveAndFlush(WorkspaceEntity.fromDomain(workspace("duplicate", Optional.of(GITLAB)))))
                .isInstanceOf(JpaSystemException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("SQLITE_CONSTRAINT_UNIQUE")
                .hasMessageContaining("watched_repo_workspaces.repository_key");
    }

    @Test
    void preservesIndexesWithOtherPurposes()
    {
        assertThat(entities.unwrap(Session.class).createNativeQuery("SELECT name FROM pragma_index_list('watched_repo_workspaces')", String.class).getResultList())
                .contains("selected_repository_lookup", "repository_and_name", "partial_repository", "sqlite_autoindex_watched_repo_workspaces_1")
                .doesNotContain("legacy_repository_number");
    }

    private static Workspace workspace(String id, Optional<HostingServer> server)
    {
        RepoRef repository = RepoRef.of("owner", "project");
        String baseUrl = server.map(value -> value.webBaseUrl().toString()).orElse("https://github.com");
        return new Workspace(id, id, 100, repository, Optional.empty(), Optional.empty(), 100, repository, Workspace.Topology.DIRECT,
                "main", baseUrl + "/owner/project.git", Optional.empty(), Optional.empty(), "/tmp/" + id, Optional.empty(), Optional.empty(), 0, NOW, NOW, server);
    }

    private static void createV052Table()
    {
        try (var connection = DriverManager.getConnection(DATABASE_URL);
                var statement = connection.createStatement()) {
            // v0.5.2 has no hosting columns and the dialect creates a separate unique index for the repository number.
            statement.execute("""
                    CREATE TABLE watched_repo_workspaces (
                        id varchar(255) NOT NULL PRIMARY KEY, name varchar(255) NOT NULL, watched boolean DEFAULT true NOT NULL,
                        selected_repository_id bigint NOT NULL, selected_owner varchar(255) NOT NULL, selected_repo varchar(255) NOT NULL,
                        upstream_repository_id bigint, upstream_owner varchar(255), upstream_repo varchar(255), monitored_repository_id bigint NOT NULL,
                        monitored_owner varchar(255) NOT NULL, monitored_repo varchar(255) NOT NULL,
                        topology varchar(255) NOT NULL CHECK (topology IN ('DIRECT', 'FORK')), default_branch varchar(255) NOT NULL,
                        origin_clone_url varchar(255) NOT NULL, upstream_default_branch varchar(255), upstream_clone_url varchar(255),
                        local_repository_path varchar(255) NOT NULL, last_code_sync_at timestamp, last_metadata_sync_at timestamp,
                        display_order integer NOT NULL, created_at timestamp NOT NULL, updated_at timestamp NOT NULL)
                    """);
            statement.execute("CREATE UNIQUE INDEX legacy_repository_number ON watched_repo_workspaces(selected_repository_id)");
            statement.execute("CREATE INDEX selected_repository_lookup ON watched_repo_workspaces(selected_repository_id)");
            statement.execute("CREATE UNIQUE INDEX repository_and_name ON watched_repo_workspaces(selected_repository_id, name)");
            statement.execute("CREATE UNIQUE INDEX partial_repository ON watched_repo_workspaces(selected_repository_id) WHERE selected_repo = 'partial-only'");
            try (var insert = connection.prepareStatement("""
                    INSERT INTO watched_repo_workspaces (id, name, selected_repository_id, selected_owner, selected_repo, monitored_repository_id,
                        monitored_owner, monitored_repo, topology, default_branch, origin_clone_url, local_repository_path, display_order, created_at, updated_at)
                    VALUES ('existing', 'existing', 100, 'owner', 'project', 100, 'owner', 'project', 'DIRECT', 'main',
                        'https://github.com/owner/project.git', '/tmp/existing', 0, ?, ?)
                    """)) {
                insert.setTimestamp(1, Timestamp.from(NOW));
                insert.setTimestamp(2, Timestamp.from(NOW));
                insert.executeUpdate();
            }
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
