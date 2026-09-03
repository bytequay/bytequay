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
import org.springframework.dao.DataAccessException;

import java.util.function.BiFunction;
import java.util.function.Supplier;

final class SqliteStoreExceptions
{
    private SqliteStoreExceptions() {}

    /**
     * Runs one read and reports a persistence failure as the caller's own
     * storage exception, so a store method is the query plus the message it
     * fails with.
     */
    public static <T> T read(
            String message,
            Supplier<T> query,
            BiFunction<String, DataAccessException, RuntimeException> wrap)
    {
        try {
            return query.get();
        }
        catch (DataAccessException e) {
            throw wrap.apply(message, e);
        }
    }

    /** True when SQLite rejected the write, rather than the write failing for any other reason. */
    public static boolean isConstraintViolation(Throwable failure)
    {
        return constraintDetail(failure) != null;
    }

    /**
     * A duplicate or dangling key means durable state disagrees with the caller:
     * a conflict. A CHECK or NOT NULL failure means the row itself does not fit
     * the schema, which the caller never caused, so the failure names SQLite's
     * reason instead of a conflict that does not exist.
     */
    public static RuntimeException mapConstraint(String message, DataAccessException failure)
    {
        String detail = constraintDetail(failure);
        if (detail == null) {
            return new AgentStorageException(message, failure);
        }
        if (detail.contains("SQLITE_CONSTRAINT_CHECK") || detail.contains("SQLITE_CONSTRAINT_NOTNULL")) {
            return new AgentStorageException("Database rejected the write: " + detail, failure);
        }
        return new AgentConflictException(message, failure);
    }

    private static String constraintDetail(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("SQLITE_CONSTRAINT")) {
                return cause.getMessage();
            }
        }
        return null;
    }
}
