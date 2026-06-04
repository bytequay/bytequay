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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where one {@link MemoryItem} came from. Every applied item carries
 * at least one source (Phase E enforces it at the boundary) so the
 * agent can cite "per the decision from {@code thread X / task Y}"
 * with a jump target the UI surfaces as a chip.
 *
 * <p>Exactly one of {@code threadId} / {@code taskId} / {@code prRef}
 * is the primary anchor for any given source; the others (and the
 * optional message range) are extra positioning. The record allows
 * any combination because the distiller can produce thread+message-
 * range references and the merge-event trigger can produce
 * thread+prRef references — keeping them as one shape avoids a sealed
 * hierarchy for very little win.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryItemSource(
        String threadId,
        String taskId,
        String prRef,
        Long messageStart,
        Long messageEnd)
{
    public static MemoryItemSource thread(String threadId)
    {
        return new MemoryItemSource(threadId, null, null, null, null);
    }

    public static MemoryItemSource threadMessages(String threadId, long first, long last)
    {
        return new MemoryItemSource(threadId, null, null, first, last);
    }

    public static MemoryItemSource task(String threadId, String taskId)
    {
        return new MemoryItemSource(threadId, taskId, null, null, null);
    }

    public static MemoryItemSource pr(String prRef)
    {
        return new MemoryItemSource(null, null, prRef, null, null);
    }
}
