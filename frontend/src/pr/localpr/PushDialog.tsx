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
import { useEffect } from 'react';
import { MarkdownProse } from '../../threads/MarkdownProse';
import type { LocalPRBundle } from '../../types/localPr';

/**
 * The push confirmation modal (mockup frame 16, decisions #47 + #53). It is a
 * one-way action: it summarises what migrates (repo, branch flow, commits, the
 * description) and warns — with the exact count — which local review comments
 * will be stripped and never pushed. Confirm with the Push button or ⌘↵; Esc
 * or the backdrop cancels. Draft is fixed (a stated row, not a toggle).
 */
export function PushDialog({
  bundle, repoLabel, onPush, onCancel, busy = false,
}: {
  bundle: LocalPRBundle;
  /** "owner/repo" — resolved by the host from the task. */
  repoLabel?: string;
  onPush: () => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  const { pr, commits } = bundle;
  const additions = commits.reduce((n, c) => n + c.additions, 0);
  const deletions = commits.reduce((n, c) => n + c.deletions, 0);
  const stripped = bundle.pendingStripCount
    ?? bundle.comments.filter(c => c.origin === 'local' && c.strippedOnPushAt === null).length;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onCancel(); }
      else if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); if (!busy) onPush(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onPush, onCancel, busy]);

  return (
    <div className="push-dialog-overlay" role="dialog" aria-modal="true" onClick={onCancel}>
      <div className="push-dialog" onClick={e => e.stopPropagation()}>
        <div className="pd-head">
          <span className="push-icon" aria-hidden>↑</span>
          <span className="pd-title">Push to GitHub</span>
          <span className="pd-sub">One-way action</span>
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
            <div className="push-info-row">
              <span className="label">Commits</span>
              <span className="value">
                {commits.length} commit{commits.length === 1 ? '' : 's'} · <span className="add">+{additions}</span> <span className="del">−{deletions}</span>
              </span>
            </div>
            <div className="push-info-row">
              <span className="label">Open as</span>
              <span className="value">Draft PR — flip to ready-for-review on GitHub after remote CI is green</span>
            </div>
          </div>

          <div className="pr-section-h">Description preview</div>
          <div className="push-desc">
            {pr.description.trim().length > 0
              ? <MarkdownProse text={pr.description} />
              : <span style={{ color: 'var(--text-4)' }}>No description.</span>}
          </div>

          {stripped > 0 && (
            <div className="push-warning">
              <span className="ic" aria-hidden>⚠</span>
              <span>
                <span className="b">{stripped} local review comment{stripped === 1 ? '' : 's'} will NOT be pushed.</span>{' '}
                They stay in ByteQuay as your private review record. If you want them addressed on GitHub,
                cancel this push, ask the agent to fix them, then push again.
              </span>
            </div>
          )}
        </div>
        <div className="pd-foot">
          <span className="left-hint">Will open as Draft PR · you&apos;ll land on the PR view</span>
          <span style={{ flex: 1 }} />
          <button type="button" className="cancel-btn" onClick={onCancel}>Cancel</button>
          <button type="button" className="push-btn" onClick={onPush} disabled={busy}>
            ↑ Push to GitHub<span className="kbd">⌘↵</span>
          </button>
        </div>
      </div>
    </div>
  );
}
