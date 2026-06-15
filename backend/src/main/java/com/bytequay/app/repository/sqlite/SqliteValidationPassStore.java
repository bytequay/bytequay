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

import com.bytequay.app.repository.ValidationPassStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

@Component
class SqliteValidationPassStore
        implements ValidationPassStore
{
    private final ValidationPassJpaRepository rows;

    SqliteValidationPassStore(ValidationPassJpaRepository rows)
    {
        this.rows = requireNonNull(rows, "rows is null");
    }

    @Override
    @Transactional
    public long startPass(String taskId, Instant startedAt)
    {
        ValidationPassEntity e = new ValidationPassEntity();
        e.setTaskId(taskId);
        e.setStartedAtMs(startedAt.toEpochMilli());
        return rows.save(e).getId();
    }

    @Override
    @Transactional
    public void finishPass(long id, Instant endedAt, boolean passed, int fixRounds, String failuresJson)
    {
        rows.findById(id).ifPresent(e -> {
            e.setEndedAtMs(endedAt.toEpochMilli());
            e.setPassed(passed);
            e.setFixRounds(fixRounds);
            e.setFailuresJson(failuresJson);
            rows.save(e);
        });
    }
}
