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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteStoreExceptions
{
    @Test
    void reportsADuplicateKeyAsTheCallersConflict()
    {
        assertThat(mapConstraint("Task already exists: t1", rejected(
                "[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: agent_task.id)")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("Task already exists: t1");
    }

    @Test
    void reportsARowTheSchemaRejectsWithSqlitesReasonNotAConflict()
    {
        assertThat(mapConstraint("Task already exists: t1", rejected(
                "[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed (CHECK constraint failed: state in ('CREATED'))")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("CHECK constraint failed: state in ('CREATED')")
                .extracting(Throwable::getMessage).asString().doesNotContain("already exists");
    }

    private static DataIntegrityViolationException rejected(String sqliteMessage)
    {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(sqliteMessage));
    }
}
