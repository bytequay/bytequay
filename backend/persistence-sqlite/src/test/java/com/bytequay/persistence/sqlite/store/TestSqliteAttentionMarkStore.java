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

import com.bytequay.core.domain.AttentionMark;
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

class TestSqliteAttentionMarkStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-attention-mark-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteAttentionMarkStore store;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void savesReplacesAndRemovesMarksByKey()
    {
        AttentionMark acknowledged = new AttentionMark("trinodb/trino#31402", AttentionMark.Kind.ACK, Optional.empty(), "2026-10-17T01:02:00Z",
                Optional.of("[\"2026-10-17T01:02:00Z\",\"open\"]"), "acknowledged", Optional.empty());
        AttentionMark snoozed = new AttentionMark("task:7d0e", AttentionMark.Kind.SNOOZE, Optional.of(Instant.parse("2026-10-18T01:00:00Z")), "2026-10-17T01:02:00Z",
                Optional.empty(), "tomorrow", Optional.of("{\"key\":\"task:7d0e\",\"title\":\"Expose snapshot ids\"}"));
        store.change(ImmutableList.of(acknowledged, snoozed), ImmutableList.of());
        entities.flush();
        entities.clear();
        assertThat(store.all()).containsExactlyInAnyOrder(acknowledged, snoozed);

        // A second mark on the same item replaces the first, and removing an unknown key is not an error.
        AttentionMark again = new AttentionMark("trinodb/trino#31402", AttentionMark.Kind.SNOOZE, Optional.empty(), "2026-10-17T03:00:00Z", Optional.empty(),
                "until activity", Optional.empty());
        store.change(ImmutableList.of(again), ImmutableList.of("task:7d0e", "jira:gone:1"));
        entities.flush();
        entities.clear();
        assertThat(store.all()).containsExactly(again);
    }
}
