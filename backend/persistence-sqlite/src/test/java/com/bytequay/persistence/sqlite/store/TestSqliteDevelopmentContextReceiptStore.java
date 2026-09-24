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

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.DevelopmentContextReceipt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteDevelopmentContextReceiptStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "bytequay-development-context-" + UUID.randomUUID() + ".db").toString();

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH, "create-drop");
    }

    @Autowired
    private SqliteDevelopmentContextReceiptStore store;

    @Test
    void taskAndTurnBoundReceiptsSurviveReadsAndDeleteWithTheirTask()
    {
        DevelopmentContextReceipt receipt = new DevelopmentContextReceipt("task", "turn", "search:one", "context-search:one",
                DevelopmentContextReceipt.Kind.SEARCH, "request", "{\"text\":\"Iceberg 雪\"}", "payload", Instant.EPOCH);
        store.insert(receipt);
        assertThat(store.find("task", "turn", "search:one")).contains(receipt);
        assertThat(store.findReference("task", "turn", "context-search:one")).contains(receipt);
        assertThat(store.findReference("another-task", "turn", "context-search:one")).isEmpty();
        assertThat(store.listTurn("task", "turn")).containsExactly(receipt);
        DevelopmentContextReceipt newer = new DevelopmentContextReceipt("task", "turn-2", "read:two", "context-note:two",
                DevelopmentContextReceipt.Kind.READ, "request-2", "{\"status\":\"AVAILABLE\"}", "payload-2", Instant.EPOCH.plusSeconds(1));
        store.insert(newer);
        assertThat(store.listTask("task", 1)).containsExactly(newer);
        assertThat(store.listTask("task", 2)).containsExactly(newer, receipt);
        assertThat(store.listTask("another-task", 2)).isEmpty();
        assertThat(store.listTaskIds(10)).contains("task");
        assertThat(store.taskBytes("task")).isEqualTo(ContextLimits.bytes(receipt.payloadJson()) + ContextLimits.bytes(newer.payloadJson()));
        store.deleteTask("task");
        assertThat(store.find("task", "turn", "search:one")).isEmpty();
        assertThat(store.listTaskIds(10)).doesNotContain("task");
    }
}
