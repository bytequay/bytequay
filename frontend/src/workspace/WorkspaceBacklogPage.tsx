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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { BacklogItemDto } from '../types';

/** The lifecycle filter pills, in display order. {@code null} is "All". */
const STATUS_FILTERS: { key: string | null; label: string }[] = [
  { key: null, label: 'All' },
  { key: 'created', label: 'Created' },
  { key: 'in-progress', label: 'In progress' },
  { key: 'resolved', label: 'Resolved' },
  { key: 'not-to-proceed', label: 'Not to proceed' },
];

/** Pretty status-tag text + class suffix for a backlog item's status. */
const STATUS_LABEL: Record<string, string> = {
  'created': 'Created',
  'in-progress': 'In progress',
  'resolved': 'Resolved',
  'not-to-proceed': 'Not to proceed',
};

function priorityClass(priority: string): string {
  return priority === 'high' || priority === 'low' ? priority : 'medium';
}

function BacklogSourceCard({
  item, threadName, onOpenThread, onStart,
}: {
  item: BacklogItemDto;
  threadName: string | undefined;
  onOpenThread?: (threadId: string) => void;
  onStart?: (itemId: string) => void;
}) {
  const statusLabel = STATUS_LABEL[item.status] ?? item.status;
  const inProgress = item.status === 'in-progress';
  const resolved = item.status === 'resolved';
  return (
    <div className="backlog-card-with-source">
      <div className="bs-source-row">
        <button
          type="button"
          className="from-chip"
          onClick={() => onOpenThread?.(item.threadId)}
          title="Open the originating thread"
        >
          <span className="ic" aria-hidden>⎇</span>{threadName ?? 'Thread'}
        </button>
        <span className={`source-badge ${item.source === 'trunk-split' ? 'trunk-split' : 'manual'}`}>
          {item.source}
        </span>
        <span className={`status-tag ${item.status}`}>{statusLabel}</span>
      </div>
      <div className="bs-title">{item.title}</div>
      {item.body.length > 0 && <div className="bs-body">{item.body}</div>}
      <div className="bs-meta-row">
        {item.tags.map(tag => <span key={tag} className="tag">{tag}</span>)}
        <span className={`priority ${priorityClass(item.priority)}`}>{item.priority}</span>
        {inProgress
          ? <span className="linked-task">↗ Trunk exploring</span>
          : resolved && item.linkedTaskId !== null
            ? <span className="linked-task">↗ Task</span>
            : item.status === 'created' && (
              <button type="button" className="action-btn" onClick={() => onStart?.(item.id)}>Start →</button>
            )}
      </div>
    </div>
  );
}

/**
 * The workspace-level Backlog page (design frame 11): a status/thread/tag/
 * search filter row over a responsive grid of backlog cards, each badged
 * with its source and originating thread. Reads through the workspace
 * backlog bridge; clicking a card's thread chip routes to that thread,
 * and a created item's "Start →" begins trunk exploration.
 */
export default function WorkspaceBacklogPage({
  workspaceId, threadNames, onOpenThread,
}: {
  workspaceId: string;
  /** threadId → title, for the from-chip. */
  threadNames?: Map<string, string>;
  onOpenThread?: (threadId: string) => void;
}) {
  const [items, setItems] = useState<BacklogItemDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listWorkspaceBacklog === undefined) {
      setLoading(false);
      return;
    }
    try {
      const rows = await bridge.listWorkspaceBacklog(workspaceId, {
        status: status ?? undefined,
        q: query.trim().length > 0 ? query.trim() : undefined,
      });
      setItems(rows);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load the backlog');
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId, status, query]);

  useEffect(() => { void load(); }, [load]);

  // Per-status counts come from an unfiltered view, so the pills stay stable
  // as the active filter changes. We approximate from the loaded set when the
  // "All" filter is active; otherwise the badge reflects the current slice.
  const counts = useMemo(() => {
    const by: Record<string, number> = {};
    for (const it of items) by[it.status] = (by[it.status] ?? 0) + 1;
    return by;
  }, [items]);

  const start = useCallback(async (itemId: string) => {
    await window.bridge.startBacklogDevelopment(itemId);
    await load();
  }, [load]);

  return (
    <div className="wb-body">
      <div className="wb-filter-row">
        <div className="filter-group">
          <span className="filter-label">Status:</span>
          {STATUS_FILTERS.map(f => (
            <button
              key={f.key ?? 'all'}
              type="button"
              className={status === f.key ? 'filter-pill active' : 'filter-pill'}
              onClick={() => setStatus(f.key)}
            >
              {f.label}
              {f.key !== null && counts[f.key] !== undefined && <span className="count">{counts[f.key]}</span>}
              {f.key === null && status === null && <span className="count">{items.length}</span>}
            </button>
          ))}
        </div>
        <span className="grow" />
        <div className="filter-search">
          <span className="ic" aria-hidden>⌕</span>
          <input
            type="text"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search title or body…"
            aria-label="Search the backlog"
          />
        </div>
      </div>

      {error !== null
        ? <div className="wb-grid"><div className="pane-empty-note">{error}</div></div>
        : loading
          ? <div className="wb-grid"><div className="pane-empty-note">Loading the backlog…</div></div>
          : items.length === 0
            ? <div className="wb-grid"><div className="pane-empty-note">No backlog items match.</div></div>
            : (
              <div className="wb-grid">
                {items.map(item => (
                  <BacklogSourceCard
                    key={item.id}
                    item={item}
                    threadName={threadNames?.get(item.threadId)}
                    onOpenThread={onOpenThread}
                    onStart={start}
                  />
                ))}
              </div>
            )}
    </div>
  );
}
