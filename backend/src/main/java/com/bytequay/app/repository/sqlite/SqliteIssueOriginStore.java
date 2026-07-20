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

import com.bytequay.app.repository.IssueOriginStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteIssueOriginStore
        implements IssueOriginStore
{
    private final JdbcTemplate jdbc;

    public SqliteIssueOriginStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<String> find(long issueId)
    {
        return jdbc.queryForList(
                        "SELECT origin FROM issue_origin WHERE issue_id = ?",
                        String.class,
                        issueId)
                .stream()
                .findFirst();
    }

    @Override
    public void saveIfAbsent(long issueId, int issueNumber, String origin)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO issue_origin (issue_id, issue_number, origin)
                VALUES (?, ?, ?)
                """, issueId, issueNumber, requireNonNull(origin, "origin is null"));
    }
}
