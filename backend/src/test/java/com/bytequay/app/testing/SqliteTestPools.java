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
package com.bytequay.app.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Hands tests a pooled {@link DataSource} over a SQLite test database and closes
 * the pools when the test method ends.
 *
 * <p>SQLite parses the whole schema the first time a connection prepares a
 * statement, and this schema is nine megabytes of it. An unpooled
 * {@code SQLiteDataSource} gives {@link org.springframework.jdbc.core.JdbcTemplate}
 * a fresh connection for every operation, so a test pays that parse per
 * statement — about 30 ms each, against 0.2 ms once a connection is reused.
 * Reusing connections is worth two orders of magnitude to any test that touches
 * the database more than a handful of times.
 *
 * <p>A pool holds threads, so it has to be closed; a suite this size would
 * otherwise accumulate them for the life of the fork. Register the extension
 * with {@code @ExtendWith(SqliteTestPools.class)} and every pool
 * {@link #open} handed out is closed after the test method.
 */
public final class SqliteTestPools
        implements AfterEachCallback
{
    // Surefire gives each fork its own JVM and runs its classes one at a time,
    // so a single static list tracks exactly the pools of the running test.
    private static final List<HikariDataSource> OPEN_POOLS = new ArrayList<>();

    /** Opens a pooled DataSource over an already-migrated SQLite database. */
    public static DataSource open(String url)
    {
        requireNonNull(url, "url is null");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        // Enough for the few threads a test starts, small enough that the pools
        // of a long-running fork stay cheap.
        config.setMaximumPoolSize(4);
        config.setPoolName("sqlite-test-" + Integer.toHexString(url.hashCode()));
        HikariDataSource pool = new HikariDataSource(config);
        synchronized (OPEN_POOLS) {
            OPEN_POOLS.add(pool);
        }
        return pool;
    }

    @Override
    public void afterEach(ExtensionContext context)
    {
        closeAll();
    }

    private static void closeAll()
    {
        List<HikariDataSource> pools;
        synchronized (OPEN_POOLS) {
            pools = List.copyOf(OPEN_POOLS);
            OPEN_POOLS.clear();
        }
        for (HikariDataSource pool : pools) {
            pool.close();
        }
    }
}
