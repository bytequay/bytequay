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

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface TeamJpaRepository
        extends JpaRepository<TeamEntity, Long>
{
    /**
     * Case-insensitive lookup by team name. Used by the create / rename
     * collision check so "Trino core" and "trino core" can't coexist.
     */
    Optional<TeamEntity> findByNameIgnoreCase(String name);
}
