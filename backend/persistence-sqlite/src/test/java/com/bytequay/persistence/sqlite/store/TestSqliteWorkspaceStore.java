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
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteWorkspaceStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-workspace-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqliteWorkspaceStore store;

    @Autowired
    private WorkspaceJpaRepository repository;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void roundTripsDirectAndForkRouting()
    {
        Workspace direct = store.createOrGet(direct("direct", 100, 0));
        Workspace fork = store.createOrGet(fork("fork", 200, 300, 1));

        assertThat(store.findById(direct.id())).contains(direct);
        assertThat(store.findById(fork.id())).contains(fork);
        assertThat(fork.upstreamRepository()).contains(RepoRef.of("upstream", "project"));
        assertThat(fork.monitoredRepositoryId()).isEqualTo(300);
        assertThat(fork.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
    }

    @Test
    void repeatedStableRepositoryIdReturnsExistingWorkspace()
    {
        Workspace first = store.createOrGet(direct("first", 100, 0));
        Workspace repeated = store.createOrGet(direct("second", 100, 1));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsDuplicateSelectedRepositoryId()
    {
        repository.saveAndFlush(WorkspaceEntity.fromDomain(direct("first", 100, 0)));

        assertThatThrownBy(() ->
                repository.saveAndFlush(WorkspaceEntity.fromDomain(direct("second", 100, 1))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void multipleForksCanShareOneNonUniqueUpstream()
    {
        Workspace first = store.createOrGet(fork("first", 200, 300, 0));
        Workspace second = store.createOrGet(fork("second", 201, 300, 1));

        assertThat(first.upstreamRepositoryId()).contains(300L);
        assertThat(second.upstreamRepositoryId()).contains(300L);
        assertThat(repository.count()).isEqualTo(2);
    }

    private static Workspace direct(String id, long selectedId, int displayOrder)
    {
        RepoRef repository = RepoRef.of("owner", "project");
        return workspace(
                id, selectedId, repository, Optional.empty(), Optional.empty(), selectedId,
                repository, Topology.DIRECT, Optional.empty(), displayOrder);
    }

    private static Workspace fork(String id, long selectedId, long upstreamId, int displayOrder)
    {
        RepoRef selected = RepoRef.of("contributor", "project");
        RepoRef upstream = RepoRef.of("upstream", "project");
        return workspace(
                id, selectedId, selected, Optional.of(upstreamId), Optional.of(upstream), upstreamId,
                upstream, Topology.FORK, Optional.of("https://github.com/upstream/project.git"), displayOrder);
    }

    private static Workspace workspace(
            String id,
            long selectedId,
            RepoRef selected,
            Optional<Long> upstreamId,
            Optional<RepoRef> upstream,
            long monitoredId,
            RepoRef monitored,
            Topology topology,
            Optional<String> upstreamCloneUrl,
            int displayOrder)
    {
        return new Workspace(
                id,
                selected.fullName(),
                selectedId,
                selected,
                upstreamId,
                upstream,
                monitoredId,
                monitored,
                topology,
                "main",
                "https://github.com/" + selected.fullName() + ".git",
                upstreamCloneUrl,
                "/tmp/workspaces/" + id + "/repository",
                CodeState.PENDING,
                MetadataState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                displayOrder,
                NOW,
                NOW);
    }
}
