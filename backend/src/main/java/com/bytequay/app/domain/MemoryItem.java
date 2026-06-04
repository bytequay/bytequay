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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

/**
 * One durable observation about a workspace or a thread — the typed
 * shape that's replacing the free-form {@code memoryMd} blob. Owned
 * by the memory_item table; created by the distiller (or by a user
 * paste) and curated through propose / apply / discard.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code proposedAt} is set at row creation.</li>
 *   <li>{@code appliedAt} flips from {@code null} to wall-clock when
 *       the user clicks Apply. Pending items have it {@code null}.</li>
 *   <li>{@code resolvedAt} marks the row as historical (an
 *       OPEN_QUESTION got answered, a BLOCKER got cleared); it
 *       still surfaces on recall as audit context.</li>
 *   <li>{@code supersededBy} points at the row that replaces this
 *       one; {@code lookup_memory} returns the successor as
 *       {@code liveSuccessor} so the agent doesn't cite a dead
 *       decision.</li>
 * </ul>
 */
public record MemoryItem(
        long id,
        MemoryItemScopeKind scopeKind,
        String scopeId,
        MemoryItemKind kind,
        String text,
        List<MemoryItemSource> sources,
        MemoryItemConfidence confidence,
        List<String> tags,
        Long supersededBy,
        Instant resolvedAt,
        Instant proposedAt,
        Instant appliedAt,
        MemoryItemOrigin source)
{
    /** Defensively copy the list fields so callers can't mutate the
     *  spec the store handed out. */
    public MemoryItem
    {
        sources = sources == null ? List.of() : ImmutableList.copyOf(sources);
        tags = tags == null ? List.of() : ImmutableList.copyOf(tags);
    }

    /** True iff the row is still in the proposal banner. */
    public boolean isPending()
    {
        return appliedAt == null;
    }

    /** True iff a newer row has replaced this one. Still recallable
     *  but the agent should prefer the successor. */
    public boolean isSuperseded()
    {
        return supersededBy != null;
    }

    /** Whether the row is treated as "live" for write / decision
     *  purposes: applied, not superseded, not resolved. Recall still
     *  surfaces non-live rows as audit context. */
    public boolean isLive()
    {
        return appliedAt != null && supersededBy == null && resolvedAt == null;
    }
}
