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

import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactNext;
import com.bytequay.core.domain.ArtifactSession;
import com.bytequay.persistence.sqlite.repository.ArtifactJpaRepository;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
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
    void keepsPagesApartFromRecordsAndListsNewestChangeFirst()
    {
        // A whole second and a fraction of it: their text does not sort by time.
        Artifact older = Artifact.asked("a1", "workspace", "Teach me delete files", Instant.parse("2026-10-19T10:00:00Z"));
        Artifact newer = Artifact.asked("a2", "workspace", "Explain snapshots", Instant.parse("2026-10-19T10:00:00.500Z"));
        Artifact elsewhere = Artifact.asked("a3", "other", "Elsewhere", Instant.parse("2026-10-19T11:00:00Z"));
        store.save(older);
        store.save(newer);
        store.save(elsewhere);
        flush();

        assertThat(store.list("workspace")).containsExactly(newer, older);
        assertThat(store.writing()).containsExactlyInAnyOrder(older, newer, elsewhere);
        assertThat(store.page("workspace", "a1")).isEmpty();
        assertThat(store.find("workspace", "a3")).isEmpty();

        ArtifactNext card = new ArtifactNext("c1", "Equality deletes", "Explain equality deletes", ArtifactNext.State.SHOWN, Optional.empty());
        Artifact saved = older.saved("Delete files", "abc123", ImmutableList.of(card), Instant.parse("2026-10-19T12:00:00Z"));
        store.save(saved, "<p>Readers merge deletes.</p>");
        Artifact ready = saved.ended(true, "", Optional.of(new ArtifactSession("claude", new DiscussionSession(1, "session-1"))), Instant.parse("2026-10-19T12:01:00Z"));
        // Saving the record alone keeps the page.
        store.save(ready);
        flush();

        assertThat(store.find("workspace", "a1")).contains(ready);
        assertThat(store.page("workspace", "a1")).contains("<p>Readers merge deletes.</p>");
        assertThat(store.page("other", "a1")).isEmpty();
        assertThat(store.list("workspace")).containsExactly(ready, newer);
        assertThat(store.writing()).containsExactlyInAnyOrder(newer, elsewhere);

        store.deleteWorkspace("workspace");
        flush();
        assertThat(store.list("workspace")).isEmpty();
        assertThat(rows.count()).isEqualTo(1);
    }

    private void flush()
    {
        entities.flush();
        entities.clear();
    }
}
