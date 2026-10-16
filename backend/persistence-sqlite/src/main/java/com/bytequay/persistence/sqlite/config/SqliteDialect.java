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

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.dialect.unique.AlterTableUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.mapping.UniqueKey;

import static java.util.stream.Collectors.joining;

/** Hibernate 6.6's SQLite delegate omits table-level unique constraints; SQLite enforces them through unique indexes. */
public class SqliteDialect
        extends SQLiteDialect
{
    private final UniqueDelegate unique = new AlterTableUniqueDelegate(this)
    {
        @Override
        public String getAlterTableToAddUniqueKeyCommand(UniqueKey key, Metadata metadata, SqlStringGenerationContext context)
        {
            String columns = key.getColumns().stream().map(column -> column.getQuotedName(SqliteDialect.this)).collect(joining(", "));
            return "create unique index " + quote(key.getName()) + " on " + context.format(key.getTable().getQualifiedTableName()) + " (" + columns + ")";
        }

        @Override
        public String getAlterTableToDropUniqueKeyCommand(UniqueKey key, Metadata metadata, SqlStringGenerationContext context)
        {
            return "drop index " + quote(key.getName());
        }
    };

    @Override
    public UniqueDelegate getUniqueDelegate()
    {
        return unique;
    }
}
