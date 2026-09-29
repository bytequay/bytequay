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

import com.bytequay.core.context.ContextUsageEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Indexed local observations; durable task/review receipts remain the evidence behind each row. */
public interface ContextUsageStore
{
    /** Inserts one idempotent fact and creates the workspace tracking boundary with its first eligible activity. */
    void record(ContextUsageEvent event);

    /** Promotes a prepared fact after a correlated provider receipt; repeats never increase counts. */
    void confirm(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage);

    /** Marks a terminal operation whose provider delivery could not be established. */
    void markUncertain(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage);

    Optional<Instant> trackingSince(String workspaceId);

    /** Streams compact rows in a read-only transaction; the consumer must not call another store. */
    void forEach(String workspaceId, Optional<Instant> since, Consumer<ContextUsageEvent> consumer);

    /** Frozen metadata for an original review selection, without consulting a later notebook version. */
    Optional<ContextUsageEvent.Note> findSelectedNote(String workspaceId, String reviewRunId, String entryId, long version);

    /** Latest task facts for task-detail delivery labels; a caller requests one extra row to detect truncation. */
    List<ContextUsageEvent> listTask(String workspaceId, String taskId, int limit);
}
