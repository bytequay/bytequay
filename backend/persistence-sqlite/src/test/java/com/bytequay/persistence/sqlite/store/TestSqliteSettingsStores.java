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

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteSettingsStores
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-settings-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteRemoteSourceStore remoteSources;

    @Autowired
    private SqliteAppSettingsStore settings;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "com.bytequay.persistence.sqlite.config.SqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Test
    void remoteSourcesRoundTripPerWorkspace()
    {
        remoteSources.save(new RemoteSource("ws-1", "oss", "https://github.com/mirror/repository.git"));
        remoteSources.save(new RemoteSource("ws-1", "myoss", "https://github.com/sam/mirror.git"));
        remoteSources.save(new RemoteSource("ws-2", "oss", "https://github.com/other/repository.git"));

        assertThat(remoteSources.findByWorkspace("ws-1"))
                .extracting(RemoteSource::name)
                .containsExactly("myoss", "oss");

        remoteSources.save(new RemoteSource("ws-1", "oss", "https://github.com/renamed/repository.git"));
        assertThat(remoteSources.findByWorkspace("ws-1"))
                .filteredOn(source -> source.name().equals("oss"))
                .extracting(RemoteSource::url)
                .containsExactly("https://github.com/renamed/repository.git");

        remoteSources.delete("ws-1", "oss");
        assertThat(remoteSources.findByWorkspace("ws-1")).extracting(RemoteSource::name).containsExactly("myoss");
    }

    @Test
    void remoteSourceProviderIdentityRoundTripsUpdatesAndClears()
    {
        RemoteSource identified = new RemoteSource(
                "ws-identity",
                "oss",
                "https://github.com/owner/repository.git",
                java.util.Optional.of(166_515_022L),
                java.util.Optional.of(RepoRef.of("owner", "repository")));

        remoteSources.save(identified);
        assertThat(remoteSources.findByWorkspace("ws-identity")).containsExactly(identified);

        RemoteSource updated = new RemoteSource(
                "ws-identity",
                "oss",
                "https://github.com/apache/iceberg.git",
                java.util.Optional.of(172_455_077L),
                java.util.Optional.of(RepoRef.of("apache", "iceberg")));
        remoteSources.save(updated);
        assertThat(remoteSources.findByWorkspace("ws-identity")).containsExactly(updated);

        RemoteSource cleared = new RemoteSource("ws-identity", "oss", "https://git.example.com/team/project.git");
        remoteSources.save(cleared);
        assertThat(remoteSources.findByWorkspace("ws-identity")).containsExactly(cleared);
    }

    @Test
    void appSettingsRoundTrip()
    {
        settings.put("github.quietHours", "{\"enabled\":true}");
        settings.put("other", "keep");
        entities.flush();
        entities.clear();

        assertThat(settings.get("github.quietHours")).contains("{\"enabled\":true}");
        settings.put("github.quietHours", "{\"enabled\":false}");
        entities.flush();
        entities.clear();
        assertThat(settings.get("github.quietHours")).contains("{\"enabled\":false}");
        assertThat(entities.createQuery("SELECT count(s) FROM AppSettingEntity s", Long.class).getSingleResult()).isEqualTo(2);

        settings.delete("github.quietHours");
        entities.flush();
        entities.clear();
        assertThat(settings.get("github.quietHours")).isEmpty();
        assertThat(settings.get("other")).contains("keep");
    }

    @Test
    void listsOnlyKeysWithLiteralPrefix()
    {
        settings.put("jira.a_b.issue.1", "ticket");
        settings.put("jira.axb.issue.2", "other ticket");
        settings.put("other", "keep");
        entities.flush();
        entities.clear();

        assertThat(settings.keys("jira.")).containsExactlyInAnyOrder("jira.a_b.issue.1", "jira.axb.issue.2");
        assertThat(settings.keys("jira.a_b.")).containsExactly("jira.a_b.issue.1");
        assertThat(settings.keys("jira.%")).isEmpty();
    }
}
