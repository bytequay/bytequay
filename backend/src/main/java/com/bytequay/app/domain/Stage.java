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
import java.util.Set;
import java.util.UUID;

/**
 * A thin domain view of one {@code task_stage} row — one {@code open →
 * close} lifecycle within a Task. The single immutable implementation is
 * {@link StageInstance}; the interface is the seam downstream code (and the
 * later per-type stage behaviour) programs against.
 *
 * <p>In this milestone a stage instance is fully described by its
 * {@link StageType} plus the row fields, so there is no per-type subclass —
 * the operation-bearing behaviour each stage type will own arrives with the
 * loop machinery, not here.
 */
public interface Stage
{
    UUID id();

    String taskId();

    StageType type();

    StageState state();

    Instant openedAt();

    Optional<Instant> closedAt();

    Optional<UUID> callerStageId();

    /**
     * Phases that may legally appear inside this stage instance — delegates
     * to {@link StageType#allowedPhases()}. The phase machine uses this to
     * validate transitions.
     */
    default Set<TaskPhase> allowedPhases()
    {
        return type().allowedPhases();
    }
}
