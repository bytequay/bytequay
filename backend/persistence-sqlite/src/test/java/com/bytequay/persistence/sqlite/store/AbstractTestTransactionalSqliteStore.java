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

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reuses one schema per fork for tests whose writes are rolled back by {@code @DataJpaTest}. */
abstract class AbstractTestTransactionalSqliteStore
        extends AbstractTestSqliteStore
{
    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, newDatabaseUrl("bytequay-transactional-store-") + "?foreign_keys=ON", "create-drop");
    }
}
