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
package com.bytequay.app.developmentflow.persistence;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;

final class SqliteTransactions
{
    private SqliteTransactions() {}

    static <T> T withConnection(
            DataSource dataSource,
            ThreadLocal<Connection> transactionConnection,
            SqlWork<T> work)
    {
        requireNonNull(dataSource, "dataSource is null");
        requireNonNull(transactionConnection, "transactionConnection is null");
        requireNonNull(work, "work is null");
        Connection current = transactionConnection.get();
        if (current != null) {
            return run(work, current);
        }
        // Join an enclosing Spring transaction when one exists. Opening a
        // second SQLite writer here can deadlock a read-then-control flow such
        // as Workspace purge cancellation against its own transaction.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return run(work, connection);
        }
        finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** Acquires SQLite's writer lock before invoking work or allowing a read. */
    static <T> T immediate(
            DataSource dataSource,
            ThreadLocal<Connection> transactionConnection,
            SqlWork<T> work)
    {
        requireNonNull(dataSource, "dataSource is null");
        requireNonNull(transactionConnection, "transactionConnection is null");
        requireNonNull(work, "work is null");
        Connection nested = transactionConnection.get();
        if (nested != null) {
            return run(work, nested);
        }

        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            execute(connection, "BEGIN IMMEDIATE");
            transactionConnection.set(connection);
            try {
                T result = run(work, connection);
                execute(connection, "COMMIT");
                return result;
            }
            catch (SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
            catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
            finally {
                transactionConnection.remove();
            }
        }
        catch (SQLException e) {
            throw failure("SQLite immediate transaction failed", e);
        }
    }

    static DataAccessResourceFailureException failure(String message, SQLException cause)
    {
        return new DataAccessResourceFailureException(message, cause);
    }

    private static <T> T run(SqlWork<T> work, Connection connection)
    {
        try {
            return work.apply(connection);
        }
        catch (SQLException e) {
            throw failure("SQLite statement failed", e);
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollback(Connection connection, Throwable failure)
    {
        try {
            execute(connection, "ROLLBACK");
        }
        catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    interface SqlWork<T>
    {
        T apply(Connection connection)
                throws SQLException;
    }
}
