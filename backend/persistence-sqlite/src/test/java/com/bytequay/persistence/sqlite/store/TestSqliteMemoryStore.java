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

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.memory.MemorySource;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteMemoryStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-memory-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123Z");

    @Autowired
    private SqliteMemoryStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void savesSourcesReportsAndPreviousVersionsAndCountsWithoutRewriting()
    {
        MemorySource review = MemorySource.pullRequest("https://github.com/trinodb/trino/pull/31201#discussion_r1", "Please document the property.", NOW,
                new MemorySource.PullRequestPart(31201, MemorySource.Part.REVIEW_COMMENT, "1", "alice", true, true, true, false));
        MemorySource page = MemorySource.page("https://github.com/delta-io/delta/blob/master/PROTOCOL.md", "", NOW, Optional.of("Checkpoints"));
        Memory rule = new Memory("m1", "workspace-1", Memory.Kind.RULE, "Adding table properties", "When adding a table property, document it on the connector's page.",
                ImmutableList.of("plugin/trino-delta-lake/"), ImmutableList.of(review), new Memory.Origin(false, "Learn from my PRs", Optional.of("task-1")), 1,
                Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW);
        Memory spec = new Memory("m2", "workspace-1", Memory.Kind.SPEC, "Delta Lake transaction protocol", "The Delta Lake transaction protocol governs the connector.",
                ImmutableList.of(), ImmutableList.of(page), new Memory.Origin(false, "Spec check of plugin/trino-delta-lake/", Optional.of("task-2")), 1, Optional.empty(),
                0, 0, ImmutableList.of(), NOW.plusSeconds(1), NOW.plusSeconds(1));
        Memory elsewhere = new Memory("m3", "workspace-2", Memory.Kind.RULE, "Elsewhere", "Elsewhere", ImmutableList.of(), ImmutableList.of(review),
                Memory.Origin.USER_EDIT, 1, Optional.empty(), 0, 0, ImmutableList.of(), NOW, NOW);
        store.save(rule);
        store.save(spec);
        store.save(elsewhere);
        assertThat(store.list("workspace-1")).containsExactly(spec, rule);

        Memory changed = rule.edited("Documenting table properties", "Document every new table property on the connector's page.", NOW.plusSeconds(5))
                .reported(new MemoryReport("The page no longer lists properties", "", "task-3", NOW.plusSeconds(6)), NOW.plusSeconds(6));
        store.save(changed);
        assertThat(store.find("m1")).contains(changed);
        assertThat(changed.previous().orElseThrow()).extracting(Memory.Previous::title, Memory.Previous::text).containsExactly(rule.title(), rule.text());
        assertThat(changed.origin().user()).isTrue();

        store.countDelivered(ImmutableList.of("m1", "m2", "m9"));
        store.countDelivered(ImmutableList.of("m1"));
        store.countCited(ImmutableList.of("m2"));
        assertThat(store.find("m1").orElseThrow().delivered()).isEqualTo(2);
        assertThat(store.find("m2").orElseThrow()).extracting(Memory::delivered, Memory::cited).containsExactly(1, 1);
        assertThat(store.find("m1").orElseThrow().reports()).hasSize(1);

        store.delete("m1");
        assertThat(store.list("workspace-1")).extracting(Memory::id).containsExactly("m2");
        assertThat(store.list("workspace-2")).containsExactly(elsewhere);

        // A memory saved before titles existed reads with its text's opening words as the title.
        jdbc.update("UPDATE repository_memory SET title = NULL WHERE id = ?", "m2");
        entityManager.clear();
        assertThat(store.find("m2").orElseThrow().title()).isEqualTo("The Delta Lake transaction protocol governs the connector.");
    }
}
