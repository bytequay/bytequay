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

import com.bytequay.core.merge.AutoMerge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAutoMergeStore
        extends AbstractTestSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired
    private SqliteAutoMergeStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, newDatabaseUrl("bytequay-auto-merge-store-") + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void keepsOneStatePerTaskThroughTheWholeMerge()
    {
        assertThat(store.find("task-1")).isEmpty();
        AutoMerge waiting = AutoMerge.waiting("task-1", "c".repeat(40), "Waiting for CI fix");
        store.save(waiting);
        assertThat(store.find("task-1")).contains(waiting);

        AutoMerge merged = waiting.ready(NOW).started(NOW.plusSeconds(3600)).ended(AutoMerge.Outcome.MERGED, "Merged");
        store.save(merged);
        assertThat(store.find("task-1")).contains(merged);
        assertThat(store.find("task-2")).isEmpty();
    }
}
