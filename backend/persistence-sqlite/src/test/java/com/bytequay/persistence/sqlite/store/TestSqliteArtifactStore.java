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

import com.bytequay.core.domain.Artifact;
import com.bytequay.persistence.sqlite.repository.ArtifactJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteArtifactStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-artifact-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteArtifactStore store;

    @Autowired
    private ArtifactJpaRepository rows;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void listsTitlesNewestFirstAndReadsPagesOnlyInTheirWorkspace()
    {
        // A whole second and a fraction of it: their text does not sort by time.
        Artifact older = new Artifact("a1", "workspace", "Delete files", "<p>one</p>", Instant.parse("2026-10-19T10:00:00Z"));
        Artifact newer = new Artifact("a2", "workspace", "Snapshots", "<p>two</p>", Instant.parse("2026-10-19T10:00:00.500Z"));
        Artifact elsewhere = new Artifact("a3", "other", "Elsewhere", "<p>three</p>", Instant.parse("2026-10-19T11:00:00Z"));
        store.save(older);
        store.save(newer);
        store.save(elsewhere);
        entities.flush();
        entities.clear();

        assertThat(store.list("workspace")).containsExactly(newer.summary(), older.summary());
        assertThat(store.find("workspace", "a1")).contains(older);
        assertThat(store.find("workspace", "a3")).isEmpty();

        Artifact rewritten = new Artifact("a1", "workspace", "Delete files", "<p>rewritten</p>", Instant.parse("2026-10-19T12:00:00Z"));
        store.save(rewritten);
        entities.flush();
        entities.clear();
        assertThat(store.find("workspace", "a1")).contains(rewritten);
        assertThat(store.list("workspace")).containsExactly(rewritten.summary(), newer.summary());
        assertThat(rows.count()).isEqualTo(3);
    }
}
