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
import { useEffect, useRef, useState } from 'react';

/** One selectable commit in the Changes top-bar dropdown. */
export type CommitOption = {
  sha: string;
  label: string;
  author?: string;
  authoredAt?: number;
};

function commitTimestamp(authoredAt: number | undefined): string {
  if (authoredAt === undefined || !Number.isFinite(authoredAt)) return '';
  return new Date(authoredAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

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
  const rootRef = useRef<HTMLSpanElement>(null);
  const current = selected === null
    ? `All commits${commits.length > 0 ? ` (${commits.length})` : ''}`
    : commits.find(c => c.sha === selected)?.label ?? selected;

  useEffect(() => {
    if (!open) return;
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [open]);

  const pick = (sha: string | null) => () => { setOpen(false); onSelect(sha); };

  return (
    <span ref={rootRef} className="run-menu">
      <button type="button" className="btn" style={{ fontWeight: 400 }} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(o => !o)}>
        <span className="ic" aria-hidden>⎇</span>
        {current}
        <span className="chev" aria-hidden>▾</span>
      </button>
      {open && (
        <div className="run-menu__pop commits-dropdown__pop" role="menu">
          <button type="button" className="run-menu__item" role="menuitem" onClick={pick(null)}>All commits</button>
          {commits.map(c => {
            const timestamp = commitTimestamp(c.authoredAt);
            return (
              <button key={c.sha} type="button" className="run-menu__item commits-dropdown__item" role="menuitem" onClick={pick(c.sha)}>
                <span className="commits-dropdown__label">{c.label}</span>
                {(c.author !== undefined || timestamp !== '') && (
                  <span className="commits-dropdown__meta">
                    {c.author ?? 'Unknown author'}
                    {timestamp !== '' && c.authoredAt !== undefined && (
                      <> · <time dateTime={new Date(c.authoredAt).toISOString()}>{timestamp}</time></>
                    )}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </span>
  );
}
