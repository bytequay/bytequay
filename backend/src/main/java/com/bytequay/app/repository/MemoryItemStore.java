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
package com.bytequay.app.repository;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link MemoryItem} rows. Single table, single
 * scope discriminator — covers WORKSPACE and THREAD items so the
 * meta-tools can join one query against both.
 */
public interface MemoryItemStore
{
    /** Bag of fields needed to insert one row. Records are immutable
     *  inputs; the store stamps {@code proposedAt} from the clock. */
    record NewItem(
            MemoryItemScopeKind scopeKind,
            String scopeId,
            MemoryItemKind kind,
            String text,
            List<MemoryItemSource> sources,
            MemoryItemConfidence confidence,
            List<String> tags,
            MemoryItemOrigin source) {}

    /** Insert. Returns the persisted row with {@code id} and
     *  {@code proposedAt} populated. */
    MemoryItem insert(NewItem newItem);

    Optional<MemoryItem> findById(long id);

    /** Every row at the given scope, newest first; includes pending,
     *  applied, superseded, and resolved rows. The meta-tools filter
     *  client-side after a coarse scope-narrowed query. */
    List<MemoryItem> findByScope(MemoryItemScopeKind scopeKind, String scopeId);

    /** Subset of {@link #findByScope} where {@code appliedAt} is
     *  {@code null} — the proposal-banner contents. */
    List<MemoryItem> findPending(MemoryItemScopeKind scopeKind, String scopeId);

    /** Subset where {@code appliedAt} is non-null AND the row isn't
     *  superseded — the live items used to render WORKSPACE.md. */
    List<MemoryItem> findLive(MemoryItemScopeKind scopeKind, String scopeId);

    /** Flip {@code applied_at_ms} to {@code nowMs}. No-op if the row
     *  is already applied. */
    Optional<MemoryItem> markApplied(long id, long nowMs);

    /** Hard delete (a discarded proposal is a deliberate "this
     *  wasn't worth keeping" signal — we don't want it haunting
     *  recall as historical noise). */
    boolean delete(long id);

    /** Set {@code superseded_by} on the given row. Used by the
     *  conflict-resolution path. */
    boolean markSuperseded(long id, long supersededByItemId);

    /** Set {@code resolved_at_ms}; used when an OPEN_QUESTION gets
     *  answered or a BLOCKER gets cleared. */
    boolean markResolved(long id, long nowMs);

    /** Hard-delete every memory item at a scope (used by workspace/thread teardown). */
    int deleteByScope(MemoryItemScopeKind scopeKind, String scopeId);
}
