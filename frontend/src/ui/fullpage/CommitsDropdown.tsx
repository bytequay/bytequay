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
import { useState } from 'react';

/** One selectable commit in the Changes top-bar dropdown. */
export type CommitOption = { sha: string; label: string };

/**
 * The "All commits ▾" dropdown in the Changes top bar. Selecting a commit
 * re-scopes the file tree + diff to that commit; "All commits" returns to
 * the cumulative diff. Reuses the shared run-menu dropdown chrome.
 */
export function CommitsDropdown({ commits, selected, onSelect }: {
  commits: CommitOption[];
  /** The selected commit sha, or null for the cumulative "All commits". */
  selected: string | null;
  onSelect: (sha: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const current = selected === null
    ? `All commits${commits.length > 0 ? ` (${commits.length})` : ''}`
    : commits.find(c => c.sha === selected)?.label ?? selected;

  const pick = (sha: string | null) => () => { setOpen(false); onSelect(sha); };

  return (
    <span className="run-menu">
      <button type="button" className="btn" style={{ fontWeight: 400 }} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(o => !o)}>
        <span className="ic" aria-hidden>⎇</span>
        {current}
        <span className="chev" aria-hidden>▾</span>
      </button>
      {open && (
        <div className="run-menu__pop" role="menu">
          <button type="button" className="run-menu__item" role="menuitem" onClick={pick(null)}>All commits</button>
          {commits.map(c => (
            <button key={c.sha} type="button" className="run-menu__item" role="menuitem" onClick={pick(c.sha)}>
              {c.label}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}
