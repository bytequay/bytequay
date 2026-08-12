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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The immutable stage row the store hands back — a straight mapping of
 * a {@code task_stage} row. {@code closedAt} and {@code callerStageId} are
 * nullable in the row and surfaced through {@link Optional}. {@code
 * workModel} is the stage's optional override on the work-model cascade
 * (see V159); {@code null} means "no override," matching the plain-nullable
 * convention {@link Task#workModel()} and {@link Thread#workModel()} use
 * rather than this record's own {@code OrNull}/{@link Optional} style.
 */
public record StageInstance(
        UUID id,
        String taskId,
        StageType type,
        StageState state,
        Instant openedAt,
        Instant closedAtOrNull,
        UUID callerStageIdOrNull,
        WorkModel workModel)
{
    public Optional<Instant> closedAt()
    {
        return Optional.ofNullable(closedAtOrNull);
    }

    public Optional<UUID> callerStageId()
    {
        return Optional.ofNullable(callerStageIdOrNull);
    }

    /**
     * Back-compat constructor for the 7-field shape that predates
     * {@code workModel} (V159). Defaults it to null, correct for every
     * call site except the store's row mapper and the work-model update
     * path, which thread a real value through the canonical constructor.
     */
    public StageInstance(
            UUID id,
            String taskId,
            StageType type,
            StageState state,
            Instant openedAt,
            Instant closedAtOrNull,
            UUID callerStageIdOrNull)
    {
        this(id, taskId, type, state, openedAt, closedAtOrNull, callerStageIdOrNull, null);
    }
}
