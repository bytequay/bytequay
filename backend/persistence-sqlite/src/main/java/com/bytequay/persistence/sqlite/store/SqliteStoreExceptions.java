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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("SQLITE_CONSTRAINT")) {
                return true;
            }
        }
        return false;
    }

    public static RuntimeException mapConstraint(String message, DataAccessException failure)
    {
        return isConstraintViolation(failure)
                ? new AgentConflictException(message, failure)
                : new AgentStorageException(message, failure);
    }
}
