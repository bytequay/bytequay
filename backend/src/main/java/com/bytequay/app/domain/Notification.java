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

/**
 * One row in the {@code notifications} table. The Phase 7 runtime
 * writes an UNREAD row whenever an automated run parks at
 * {@code AWAITING_REVIEW} / {@code NEEDS_ATTENTION} or finishes
 * something the user opted to be told about. The UI patches it to
 * READ on click and DISMISSED on swipe-away.
 *
 * @param threadId conversation that produced the event; null when the
 *                 event isn't bound to a specific thread.
 * @param taskId   work-unit the event is about; null for thread-level
 *                 events (e.g. ship-and-continue completed).
 * @param payloadJson free-form JSON1 blob — schema is per-{@link #kind}
 *                    so adding a new kind doesn't grow the table.
 */
public record Notification(
        String id,
        NotificationKind kind,
        String threadId,
        String taskId,
        NotificationStatus status,
        String payloadJson,
        Instant createdAt,
        Instant readAt)
{
}
