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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.repository.IdSequenceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * SQLite-backed allocator for the per-day thread seq.
 *
 * <p>A single UPSERT-with-RETURNING does the whole allocate atomically
 * under SQLite's file-level writer lock. No JPA entity / no read-then-
 * write split — that older shape lets two concurrent callers both
 * read the same {@code next_seq} before either writes, and SQLite
 * surfaces the second writer's wait as {@code SQLITE_BUSY} rather
 * than blocking. The one-statement form sidesteps the race entirely:
 * the second concurrent caller waits at the pool / writer lock and
 * sees the post-first-caller row by the time its INSERT runs.
 *
 * <p>The semantics:
 * <ul>
 *   <li>First call for a {@code ymd} inserts a row with
 *       {@code next_seq = 2} and returns {@code 1}.</li>
 *   <li>Subsequent calls conflict on the primary key, take the UPDATE
 *       branch incrementing {@code next_seq}, and return the value
 *       that was at {@code next_seq} before the increment (i.e.
 *       {@code next_seq - 1} after the post-update RETURNING).</li>
 * </ul>
 */
@Repository
public class SqliteIdSequenceStore
        implements IdSequenceStore
{
    /**
     * The value that {@code next_seq} is seeded to on the first
     * allocation for a {@code ymd}. Set so the RETURNING formula below
     * yields 1 on first call (the value handed out) and the row is
     * already primed to hand out 2 on the next.
     */
    private static final int INITIAL_NEXT_SEQ = 2;

    /**
     * UPSERT + RETURNING. INSERT lands {@code next_seq = INITIAL_NEXT_SEQ}
     * on first call so the formula {@code next_seq - 1} in RETURNING
     * yields 1. On conflict the UPDATE bumps next_seq by 1; RETURNING
     * reflects the post-update value, so {@code next_seq - 1} is the
     * value just handed out.
     */
    private static final String ALLOCATE_SQL = ""
            + "INSERT INTO thread_day_seq (ymd, next_seq, updated_at_ms) "
            + "VALUES (?, " + INITIAL_NEXT_SEQ + ", ?) "
            + "ON CONFLICT(ymd) DO UPDATE "
            + "SET next_seq = next_seq + 1, "
            + "    updated_at_ms = excluded.updated_at_ms "
            + "RETURNING next_seq - 1";

    private final JdbcTemplate jdbc;

    public SqliteIdSequenceStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public int nextThreadSeq(String ymd)
    {
        requireNonNull(ymd, "ymd is null");
        long now = Instant.now().toEpochMilli();
        Integer issued = jdbc.queryForObject(ALLOCATE_SQL, Integer.class, ymd, now);
        if (issued == null) {
            throw new IllegalStateException("allocate returned no row for ymd " + ymd);
        }
        return issued;
    }
}
