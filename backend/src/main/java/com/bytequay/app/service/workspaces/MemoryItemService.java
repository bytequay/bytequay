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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Lifecycle service for {@link MemoryItem} rows — propose, list
 * pending, per-item apply / discard, bulk apply-all, and the
 * one-way DB → markdown render that powers WORKSPACE.md once items
 * are applied.
 *
 * <p>The render is deliberately one-way (DB is the source of truth;
 * .md is a regenerated view) so callers never edit the .md directly
 * and the proposal banner is the only path that mutates state.
 */
@Service
public class MemoryItemService
{
    /** Order matters — the rendered .md respects this section order
     *  so two distill passes produce byte-identical output for the
     *  same input set (the provider's prefix cache hashes the .md
     *  verbatim when it's stuffed into the system prompt). */
    private static final List<MemoryItemKind> SECTION_ORDER = List.of(
            MemoryItemKind.FOCUS_SHIFT,
            MemoryItemKind.DECISION,
            MemoryItemKind.CONVENTION,
            MemoryItemKind.BLOCKER,
            MemoryItemKind.OPEN_QUESTION,
            MemoryItemKind.RECURRING_PATTERN);

    /** Human-readable heading per kind. Used by the renderer and
     *  echoed back to the UI as the chip label. */
    private static final Map<MemoryItemKind, String> SECTION_TITLES = Map.of(
            MemoryItemKind.FOCUS_SHIFT, "Active focus",
            MemoryItemKind.DECISION, "Decisions",
            MemoryItemKind.CONVENTION, "Conventions",
            MemoryItemKind.BLOCKER, "Blockers",
            MemoryItemKind.OPEN_QUESTION, "Open questions",
            MemoryItemKind.RECURRING_PATTERN, "Recurring patterns");

    private final SqliteMemoryItemStore store;

    public MemoryItemService(SqliteMemoryItemStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /**
     * Persist a new pending item. Per Phase E (provenance
     * everywhere) the caller MUST hand over at least one source for
     * the row to survive. An empty-sources draft surfaces a 400 so
     * the distiller catches its own bug rather than putting an
     * unattributed row in front of the user.
     */
    public MemoryItem propose(SqliteMemoryItemStore.NewItem newItem)
    {
        requireNonNull(newItem, "newItem is null");
        if (newItem.sources() == null || newItem.sources().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "memory item must carry at least one source (kind=" + newItem.kind() + ")");
        }
        if (newItem.text() == null || newItem.text().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "memory item text is required");
        }
        return store.insert(newItem);
    }

    public List<MemoryItem> listPending(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return store.findPending(scopeKind, scopeId);
    }

    public List<MemoryItem> listLive(MemoryItemScopeKind scopeKind, String scopeId)
    {
        return store.findLive(scopeKind, scopeId);
    }

    /** Apply one item by id. 404 if the row isn't there. */
    public MemoryItem applyItem(long id)
    {
        return store.markApplied(id, Instant.now().toEpochMilli())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "memory item " + id + " not found"));
    }

    /** Discard one pending item by id. 404 if missing; 409 if the
     *  row is already applied (discard is a proposal-only action —
     *  removing applied rows is a separate supersede / resolve flow). */
    public void discardItem(long id)
    {
        MemoryItem row = store.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404),
                "memory item " + id + " not found"));
        if (!row.isPending()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "memory item " + id + " is already applied; use supersede or resolve");
        }
        store.delete(id);
    }

    /** Convenience: apply every pending item at the scope in one
     *  call. Per-row failures are surfaced as the count of items
     *  that succeeded. */
    public int applyAllPending(MemoryItemScopeKind scopeKind, String scopeId)
    {
        long now = Instant.now().toEpochMilli();
        int applied = 0;
        for (MemoryItem item : store.findPending(scopeKind, scopeId)) {
            if (store.markApplied(item.id(), now).isPresent()) {
                applied++;
            }
        }
        return applied;
    }

    /** Convenience: discard every pending item at the scope. */
    public int discardAllPending(MemoryItemScopeKind scopeKind, String scopeId)
    {
        int dropped = 0;
        for (MemoryItem item : store.findPending(scopeKind, scopeId)) {
            if (store.delete(item.id())) {
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Render every live (applied, non-superseded, non-resolved)
     * item at the scope into a deterministic markdown document. The
     * caller writes this back to {@code memoryMd}; nothing else
     * touches the .md, so the render and the table never drift.
     *
     * <p>Sections appear in {@link #SECTION_ORDER}; bullets within
     * each section preserve insertion (oldest applied first).
     * Empty sections are omitted so the .md doesn't show a parade
     * of blank H2s when a workspace is fresh.
     */
    public String renderToMarkdown(MemoryItemScopeKind scopeKind, String scopeId)
    {
        List<MemoryItem> live = store.findLive(scopeKind, scopeId);
        if (live.isEmpty()) {
            return "";
        }
        Map<MemoryItemKind, List<MemoryItem>> grouped = new EnumMap<>(MemoryItemKind.class);
        for (MemoryItem item : live) {
            grouped.computeIfAbsent(item.kind(), k -> new ArrayList<>()).add(item);
        }
        StringBuilder out = new StringBuilder();
        for (MemoryItemKind kind : SECTION_ORDER) {
            List<MemoryItem> rows = grouped.get(kind);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("## ").append(SECTION_TITLES.get(kind)).append("\n\n");
            for (MemoryItem row : rows) {
                out.append("- ").append(row.text().trim()).append('\n');
            }
        }
        return out.toString();
    }
}
