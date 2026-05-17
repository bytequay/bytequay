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
import { useEffect, useState } from 'react';
import type { EmailTagAction, EmailTagDto, EmailThreadMetaDto } from '../types';

type Props = {
  account: string;
  tags: EmailTagDto[];
  /** Currently-loaded inbox threads — drives the "Hits" column so the
   *  user gets a live sanity-check while editing. The match is the
   *  same case-insensitive substring the backend uses. */
  threads: EmailThreadMetaDto[];
  onClose: () => void;
  /** Closes the modal once a successful save has propagated; the
   *  parent re-fetches tags + inbox to pick up any reclassification. */
  onSaved: () => void;
};

/**
 * Inline-editable table for the per-account tag rules. The whole
 * grid is local-state until the user clicks Save, at which point we
 * diff against the original list and issue create / update / delete
 * calls. Per-row trash is immediate (no undo affordance here — the
 * archive-log keeps the historical effect even if the rule is gone).
 */
export default function ManageRulesModal({
  account, tags, threads, onClose, onSaved,
}: Props)
{
  type DraftRow = {
    // null id = newly added in this session
    id: string | null;
    originalId: string | null;
    name: string;
    subjectContains: string;
    action: EmailTagAction;
  };

  const toDraft = (t: EmailTagDto): DraftRow => ({
    id: t.id,
    originalId: t.id,
    name: t.name,
    subjectContains: t.subjectContains,
    action: t.action,
  });

  const [rows, setRows] = useState<DraftRow[]>(() => tags.map(toDraft));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // If the parent's tag list refreshes while we're open (rare, but
  // happens when another action triggers a re-fetch), reset our draft.
  useEffect(() => {
    setRows(tags.map(toDraft));
  }, [tags]);

  const addBlank = () => {
    setRows(r => [...r, { id: null, originalId: null, name: '', subjectContains: '', action: 'FOCUS' }]);
  };

  const updateRow = (idx: number, patch: Partial<DraftRow>) => {
    setRows(r => r.map((row, i) => (i === idx ? { ...row, ...patch } : row)));
  };

  const removeRow = (idx: number) => {
    setRows(r => r.filter((_, i) => i !== idx));
  };

  const handleSave = async () => {
    if (saving) return;
    setError(null);
    // Trim and validate locally so we don't waste round-trips.
    const cleaned = rows.map(r => ({
      ...r,
      name: r.name.trim(),
      subjectContains: r.subjectContains.trim(),
    }));
    const invalid = cleaned.find(r => !r.name || !r.subjectContains);
    if (invalid) {
      setError('Each rule needs a name and a subject-contains pattern.');
      return;
    }
    setSaving(true);
    try {
      const originalIds = new Set(tags.map(t => t.id));
      const keptIds = new Set(cleaned.filter(r => r.originalId).map(r => r.originalId!));

      // Deletes first so an updated-then-deleted rule doesn't double-fire.
      for (const removedId of originalIds) {
        if (!keptIds.has(removedId)) {
          await window.bridge.deleteEmailTag(removedId);
        }
      }
      for (const row of cleaned) {
        const input = { name: row.name, subjectContains: row.subjectContains, action: row.action };
        if (row.originalId == null) {
          await window.bridge.createEmailTag(account, input);
        }
        else {
          // Only PUT if something actually changed — avoids bumping
          // updated_at on no-op saves.
          const original = tags.find(t => t.id === row.originalId);
          const changed = !original
                  || original.name !== row.name
                  || original.subjectContains !== row.subjectContains
                  || original.action !== row.action;
          if (changed) {
            await window.bridge.updateEmailTag(row.originalId, input);
          }
        }
      }
      onSaved();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  return (
    <div className="email-modal-backdrop" onClick={onClose}>
      <div
        className="email-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="manage-rules-title"
        onClick={e => e.stopPropagation()}
      >
        <header className="email-modal__head">
          <h2 id="manage-rules-title" className="email-modal__title">Manage rules</h2>
          <button
            type="button"
            className="email-modal__close"
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <p className="email-modal__hint">
          Tags apply to incoming mail by matching the subject (case-insensitive).
          Precedence: ignore beats focus beats archive.
        </p>

        <div className="email-rules-table">
          <div className="email-rules-table__head">
            <span>Name</span>
            <span>Subject contains</span>
            <span>Action</span>
            <span className="email-rules-table__hits-h">Hits</span>
            <span aria-hidden="true" />
          </div>
          {rows.length === 0 && (
            <div className="email-rules-table__empty">No rules yet — add one below.</div>
          )}
          {rows.map((row, idx) => (
            <div className="email-rules-table__row" key={row.id ?? `new-${idx}`}>
              <input
                className="email-rules-input"
                value={row.name}
                placeholder="GitHub PRs"
                onChange={e => updateRow(idx, { name: e.target.value })}
                disabled={saving}
              />
              <input
                className="email-rules-input"
                value={row.subjectContains}
                placeholder="Pull request"
                onChange={e => updateRow(idx, { subjectContains: e.target.value })}
                disabled={saving}
              />
              <select
                className="email-rules-input email-rules-input--action"
                value={row.action}
                onChange={e => updateRow(idx, { action: e.target.value as EmailTagAction })}
                disabled={saving}
              >
                <option value="FOCUS">Focus</option>
                <option value="ARCHIVE">Archive</option>
                <option value="IGNORE">Ignore</option>
              </select>
              <span className="email-rules-table__hits">{hitsForRow(row, threads)}</span>
              <button
                type="button"
                className="email-rules-table__delete"
                onClick={() => removeRow(idx)}
                disabled={saving}
                aria-label="Delete rule"
              >
                🗑
              </button>
            </div>
          ))}
        </div>

        <button
          type="button"
          className="email-rules-add"
          onClick={addBlank}
          disabled={saving}
        >
          + Add rule
        </button>

        {error && <div className="repo-error email-modal__error">{error}</div>}

        <footer className="email-modal__actions">
          <button
            type="button"
            className="button button--secondary"
            onClick={onClose}
            disabled={saving}
          >
            Cancel
          </button>
          <button
            type="button"
            className="button button--primary"
            onClick={() => void handleSave()}
            disabled={saving}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </footer>
      </div>
    </div>
  );
}

/** Same matching contract as the backend: case-insensitive substring. */
function hitsForRow(
  row: { subjectContains: string },
  threads: EmailThreadMetaDto[],
): number
{
  const needle = row.subjectContains.trim().toLowerCase();
  if (!needle) return 0;
  return threads.filter(t => (t.subject ?? '').toLowerCase().includes(needle)).length;
}
