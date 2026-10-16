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
package com.bytequay.core.store;

import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaRun;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AgendaStore
{
    Optional<AgendaDay> find(LocalDate day);

    /** The newest days first, at most {@code limit}. */
    List<AgendaDay> recent(int limit);

    void save(AgendaDay day);

    void record(AgendaRun run);

    /** Removes the days and runs before {@code day}. */
    void deleteBefore(LocalDate day);
}
