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

import com.bytequay.core.domain.Epic;
import com.bytequay.persistence.sqlite.repository.EpicJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteEpicStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-epic-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteEpicStore store;

    @Autowired
    private EpicJpaRepository rows;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void savesAndDeletesOnlyTheEpicWithTheMatchingId()
    {
        Epic first = Epic.of("github:1", "workspace");
        Epic other = Epic.of("github:2", "workspace");
        store.save(first);
        store.save(other);
        entities.flush();
        entities.clear();

        Epic updated = first.withConcluded("Delivered", Instant.parse("2026-10-01T00:00:00Z"));
        store.save(updated);
        store.save(updated);
        entities.flush();
        entities.clear();
        assertThat(store.find(first.id())).contains(updated);
        assertThat(store.list("workspace")).containsExactlyInAnyOrder(updated, other);
        assertThat(rows.count()).isEqualTo(2);

        store.delete(first.id());
        entities.flush();
        entities.clear();
        assertThat(store.find(first.id())).isEmpty();
        assertThat(store.list("workspace")).containsExactly(other);
    }
}
