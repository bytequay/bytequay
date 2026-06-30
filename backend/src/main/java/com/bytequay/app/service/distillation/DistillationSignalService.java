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
package com.bytequay.app.service.distillation;

import java.util.Map;

/**
 * Write-only sink for explicit user decisions. Every decision endpoint calls
 * {@link #record} so a future "memory" function can learn across the whole
 * app without re-instrumenting each surface. v1 only writes; there's no read
 * path yet. The write is best-effort — a failure here must never break the
 * user action it's recording.
 */
public interface DistillationSignalService
{
    /**
     * Record one decision.
     *
     * @param eventType       what was decided, e.g. {@code backlog-skip}
     * @param sourceId        the thing decided on (backlog id, plan id, …)
     * @param userDecision    the verb, e.g. {@code skipped} / {@code started}
     * @param reason          optional free-text reason (may be null)
     * @param contextSnapshot small map of what was on screen (may be null);
     *                        serialised to JSON. Must not contain null values.
     * @param threadId        owning thread, or null
     * @param workspaceId     owning workspace, or null
     */
    void record(
            String eventType,
            String sourceId,
            String userDecision,
            String reason,
            Map<String, ?> contextSnapshot,
            String threadId,
            String workspaceId);
}
