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
import type { LocalPR } from '../../types/localPr';

export type MergeMethod = 'merge' | 'squash' | 'rebase';

const METHODS: { key: MergeMethod; label: string }[] = [
  { key: 'squash', label: 'Squash and merge' },
  { key: 'merge', label: 'Create a merge commit' },
  { key: 'rebase', label: 'Rebase and merge' },
];

/**
 * The merge confirmation modal (decision #52 merge affordance). Confirms the
 * merge method (squash is the default, matching the common GitHub setting) for
 * a pushed PR, then calls back with the chosen method. Same shell as the push
 * dialog; ⌘↵ confirms, Esc / backdrop cancels.
 */
export function MergeDialog({
  pr, repoLabel, defaultMethod = 'squash', onMerge, onCancel, busy = false,
}: {
  pr: LocalPR;
  repoLabel?: string;
  defaultMethod?: MergeMethod;
  onMerge: (method: MergeMethod) => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  const [method, setMethod] = useState<MergeMethod>(defaultMethod);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onCancel(); }
      else if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); if (!busy) onMerge(method); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onMerge, onCancel, busy, method]);

  const prNumLabel = pr.remotePrNumber !== null ? `#${pr.remotePrNumber}` : '#local';

  return (
    <div className="push-dialog-overlay" role="dialog" aria-modal="true" onClick={onCancel}>
      <div className="push-dialog" onClick={e => e.stopPropagation()}>
        <div className="pd-head">
          <span className="push-icon" aria-hidden>⎇</span>
          <span className="pd-title">Merge pull request {prNumLabel}</span>
          <button type="button" className="pd-close" aria-label="Close" onClick={onCancel}>✕</button>
        </div>
        <div className="pd-body">
          <div className="push-summary">
            {repoLabel !== undefined && (
              <div className="push-info-row">
                <span className="label">Repository</span>
                <span className="value"><code>{repoLabel}</code></span>
              </div>
            )}
            <div className="push-info-row">
              <span className="label">Branch</span>
              <span className="value"><code>{pr.baseBranch}</code> ← <code>{pr.branchName}</code></span>
            </div>
          </div>
          <div className="pr-section-h">Merge method</div>
          <div className="push-summary" role="radiogroup" aria-label="Merge method">
            {METHODS.map(m => (
              <button
                key={m.key}
                type="button"
                role="radio"
                aria-checked={method === m.key}
                className="push-info-row"
                style={method === m.key
                  ? { borderColor: 'var(--green)', cursor: 'pointer', textAlign: 'left' }
                  : { cursor: 'pointer', textAlign: 'left' }}
                onClick={() => setMethod(m.key)}
              >
                <span className="value">{method === m.key ? '● ' : '○ '}{m.label}</span>
              </button>
            ))}
          </div>
        </div>
        <div className="pd-foot">
          <span style={{ flex: 1 }} />
          <button type="button" className="cancel-btn" onClick={onCancel}>Cancel</button>
          <button type="button" className="push-btn" onClick={() => onMerge(method)} disabled={busy}>
            ⎇ Merge pull request<span className="kbd">⌘↵</span>
          </button>
        </div>
      </div>
    </div>
  );
}
