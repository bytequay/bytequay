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

import com.google.common.collect.ImmutableList;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.mapping.Table;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Locale.ENGLISH;

/**
 * Hibernate's schema update adds columns but never drops them. A NOT NULL column without a default
 * that no entity maps any more, left by a removed field or re-added by an older build sharing the
 * database, makes every insert into its table fail, so drop it before the update runs.
 */
final class UnmappedColumnCleanup
        implements Integrator
{
    private static final Logger log = LoggerFactory.getLogger(UnmappedColumnCleanup.class);

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory)
    {
        if (!"update".equals(sessionFactory.getProperties().get(AvailableSettings.HBM2DDL_AUTO))) {
            return;
        }
        JdbcConnectionAccess access = sessionFactory.getJdbcServices().getBootstrapJdbcConnectionAccess();
        try {
            Connection connection = access.obtainConnection();
            try {
                for (Table table : metadata.collectTableMappings()) {
                    if (table.isPhysicalTable()) {
                        dropUnmappedRequiredColumns(connection, table);
                    }
                }
            }
            finally {
                access.releaseConnection(connection);
            }
        }
        catch (SQLException e) {
            log.warn("Could not check for unmapped columns", e);
        }
    }

    private static void dropUnmappedRequiredColumns(Connection connection, Table table)
            throws SQLException
    {
        Set<String> mapped = table.getColumns().stream().map(column -> column.getName().toLowerCase(ENGLISH)).collect(toImmutableSet());
        ImmutableList.Builder<String> unmapped = ImmutableList.builder();
        try (PreparedStatement query = connection.prepareStatement("SELECT name FROM pragma_table_info(?) WHERE \"notnull\" AND dflt_value IS NULL AND pk = 0")) {
            query.setString(1, table.getName());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString(1);
                    if (!mapped.contains(column.toLowerCase(ENGLISH))) {
                        unmapped.add(column);
                    }
                }
            }
        }
        for (String column : unmapped.build()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE \"" + table.getName() + "\" DROP COLUMN \"" + column + "\"");
                log.info("Dropped unmapped NOT NULL column {}.{}", table.getName(), column);
            }
            catch (SQLException e) {
                log.warn("Could not drop unmapped NOT NULL column {}.{}", table.getName(), column, e);
            }
        }
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}
