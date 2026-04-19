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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Team;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Local persistence for teams. Member rosters are stored in a separate
 * table; the store presents them as a single {@link Team} record so callers
 * never juggle two collections.
 */
public interface TeamStore
{
    List<Team> findAll();

    Optional<Team> find(long id);

    /** Creates a new team with the given roster. Throws if {@code name} is already in use. */
    Team create(String name, String avatar, String color, Set<String> members);

    /** Renames / re-colours an existing team without touching its roster. */
    Team update(long id, String name, String avatar, String color);

    /** Replaces the entire roster. Logins are normalised to lowercase. */
    Team replaceMembers(long id, Set<String> members);

    void delete(long id);
}
