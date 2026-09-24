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
package com.bytequay.core.context;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A compact observation of notebook delivery or demand; note text never belongs in usage facts. */
public record ContextUsageEvent(
        String workspaceId,
        String taskId,
        Optional<String> turnId,
        Optional<String> reviewRunId,
        TaskKind taskKind,
        Stage stage,
        Delivery delivery,
        String receiptRef,
        Optional<Note> note,
        Optional<Scope> scope,
        Optional<Boolean> contextEnabled,
        Instant occurredAt)
{
    public ContextUsageEvent
    {
        checkArgument(workspaceId != null && !workspaceId.isBlank(), "workspaceId is required");
        checkArgument(taskId != null && !taskId.isBlank(), "taskId is required");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(reviewRunId, "reviewRunId is null");
        requireNonNull(taskKind, "taskKind is null");
        checkArgument((taskKind == TaskKind.PR_REVIEW) == reviewRunId.isPresent(), "Only review usage has a review run ID");
        requireNonNull(stage, "stage is null");
        requireNonNull(delivery, "delivery is null");
        checkArgument(receiptRef != null && !receiptRef.isBlank() && receiptRef.length() <= 500, "receiptRef is invalid");
        requireNonNull(note, "note is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(contextEnabled, "contextEnabled is null");
        requireNonNull(occurredAt, "occurredAt is null");
        checkArgument((stage == Stage.ELIGIBLE) == contextEnabled.isPresent(), "Only eligibility has a context setting");
        checkArgument((stage == Stage.SELECTED || stage == Stage.PROVIDED || stage == Stage.FOUND || stage == Stage.OPENED || stage == Stage.CITED)
                == note.isPresent(), "Entry stage needs exactly one note");
        checkArgument(stage != Stage.SCOPE_DEMAND || scope.isPresent(), "Scope demand needs a scope");
        checkArgument(stage == Stage.SCOPE_DEMAND || stage == Stage.NO_MATCH || scope.isEmpty(), "Entry stage cannot carry a scope observation");
    }

    public enum TaskKind
    {
        DEVELOPMENT,
        PR_REVIEW
    }

    public enum Stage
    {
        ELIGIBLE,
        SELECTED,
        PROVIDED,
        FOUND,
        OPENED,
        CITED,
        SCOPE_DEMAND,
        NO_MATCH
    }

    public enum Delivery
    {
        PREPARED,
        CONFIRMED,
        UNCERTAIN
    }

    public enum Preparation
    {
        NOT_PREPARED,
        READY,
        EMPTY,
        FAILED
    }

    public record Note(String id, long version, ContextEntryKind kind, String scopeId, String title)
    {
        public Note
        {
            checkArgument(id != null && !id.isBlank(), "note id is required");
            checkArgument(version > 0, "note version must be positive");
            requireNonNull(kind, "note kind is null");
            checkArgument(scopeId != null && !scopeId.isBlank(), "note scopeId is required");
            checkArgument(title != null && !title.isBlank(), "note title is required");
        }

        public static Note from(ContextEntry entry)
        {
            return new Note(entry.id(), entry.version(), entry.kind(), entry.scopeId(), entry.title());
        }
    }

    public record Scope(String id, Preparation preparation)
    {
        public Scope
        {
            checkArgument(id != null && !id.isBlank(), "scope id is required");
            requireNonNull(preparation, "preparation is null");
        }

        public static Scope from(ContextScope scope)
        {
            return new Scope(scope.id(), scope.preparation().map(result -> Preparation.valueOf(result.outcome().name())).orElse(Preparation.NOT_PREPARED));
        }
    }
}
