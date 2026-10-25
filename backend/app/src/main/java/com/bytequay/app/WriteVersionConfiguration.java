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
package com.bytequay.app;

import com.bytequay.app.config.WriteVersion;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves the write version, and the written table's own version, for each statement that changes the database. Counting statements
 * rather than read-write transactions keeps a read that happens to open one from making every poll rebuild.
 */
@Configuration
class WriteVersionConfiguration
{
    private static final Pattern WRITE = Pattern.compile("\\s*(?:/\\*.*?\\*/\\s*)*(?:insert|update|delete|replace|merge|create|alter|drop)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Sync checkpoints in app_settings change on every background tick, and no read answered by version reads that table;
    // counting them would rebuild the attention list after every tick.
    private static final Pattern SETTINGS = Pattern.compile("\\b(?:into|update|from)\\s+app_settings\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile("\\s*(?:/\\*.*?\\*/\\s*)*(?:insert\\s+(?:or\\s+\\w+\\s+)?into|replace\\s+into|update|delete\\s+from)\\s+[\"`]?(\\w+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Bean
    HibernatePropertiesCustomizer writeVersionStatements(WriteVersion writes)
    {
        StatementInspector inspector = sql -> {
            if (WRITE.matcher(sql).lookingAt()) {
                if (!SETTINGS.matcher(sql).find()) {
                    writes.written();
                }
                Matcher table = TABLE.matcher(sql);
                writes.written(table.lookingAt() ? table.group(1).toLowerCase(Locale.ROOT) : WriteVersion.UNNAMED);
            }
            return sql;
        };
        return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, inspector);
    }
}
