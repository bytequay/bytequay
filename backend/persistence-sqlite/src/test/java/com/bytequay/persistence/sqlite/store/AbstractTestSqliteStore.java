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

import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Shared wiring for the SQLite store tests. Each subclass still declares its own
 * {@code @DynamicPropertySource} method: {@code DynamicPropertiesContextCustomizer} compares only the
 * set of annotated methods, which the context bootstrapper collects from the whole class hierarchy, so
 * a method declared here would make every subclass share one Spring context and one database file.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
abstract class AbstractTestSqliteStore
{
    protected static void registerSqliteProperties(DynamicPropertyRegistry registry, String url, String ddlAuto)
    {
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "com.bytequay.persistence.sqlite.config.SqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> ddlAuto);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }
}
