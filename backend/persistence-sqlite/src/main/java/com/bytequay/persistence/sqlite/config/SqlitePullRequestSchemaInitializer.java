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
package com.bytequay.persistence.sqlite.config;

import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SqlitePullRequestSchemaInitializer
{
    private final JdbcTemplate jdbc;

    public SqlitePullRequestSchemaInitializer(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initialize()
    {
        // Hibernate's SQLite dialect ignores composite unique constraints during schema generation.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_pull_requests_repo_number "
                + "ON pull_requests (repo, number)");
    }
}
