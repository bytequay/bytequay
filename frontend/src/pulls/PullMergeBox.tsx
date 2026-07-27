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
import { useState, type CSSProperties } from 'react';
import type { PullRequestDetailDto } from '../types';
import type { LocalPR } from '../types/localPr';

/** github.com's merge-method picker; squash is the backend's own default. */
const METHODS = [
  { key: 'squash', label: 'Squash and merge', verb: 'squash' },
  { key: 'merge', label: 'Create a merge commit', verb: 'merge' },
  { key: 'rebase', label: 'Rebase and merge', verb: 'rebase' },
] as const;

/**
 * Why the PR can't merge yet — mirrors the readiness half of the backend's
 * `ReadyToMergeService.isReadyForMerge` that the detail DTO can see. The
 * merge call re-runs the full server-side preflight (approvals + live review
 * rounds included), so this only decides what the box shows, never whether
 * the merge is allowed. A null (unloaded) detail defers entirely to the server.
 */
function mergeBlockers(detail: PullRequestDetailDto | null): string[] {
  if (detail === null) return [];
  const b: string[] = [];
  if (detail.draft) b.push('This PR is still a draft.');
  if (!detail.viewerCanWrite) b.push("You don't have write access to this repository.");
  if (detail.ciStatus === 'FAILING') b.push('Some checks are failing.');
  else if (detail.ciStatus === 'PENDING') b.push('Checks are still running.');
  else if (detail.ciStatus === 'NONE') b.push('No checks have reported yet.');
  if (detail.changesRequestedCount > 0) b.push('Changes have been requested.');
  if (detail.mergeable === false) b.push('This branch has conflicts with the base branch.');
  // Matches the backend: a thread is clear only when resolved === true, so an
  // unknown (null, REST-only) resolved state blocks exactly as the server does.
  const unresolved = detail.reviewThreads.filter(t => t.resolved !== true).length;
  if (unresolved > 0) b.push(`${unresolved} unresolved conversation${unresolved === 1 ? '' : 's'}.`);
  return b;
}

function errText(e: unknown): string {
  return e instanceof Error && e.message.length > 0 ? e.message : 'The merge could not be completed.';
}

const shellStyle = (accent: string): CSSProperties => ({
  border: `1px solid ${accent}`, borderRadius: 10,
  background: '#fff', margin: '18px 0', padding: '14px 18px',
});
const greenBtn: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '6px 14px',
  border: '1px solid #1a7f37', background: '#1f883d', borderRadius: 7,
  fontSize: 13, fontWeight: 500, color: '#fff', cursor: 'pointer',
};
const plainBtn: CSSProperties = {
  padding: '6px 12px', border: '1px solid #d0d7de', background: '#f6f8fa',
  borderRadius: 7, fontSize: 13, fontWeight: 500, color: '#24292f', cursor: 'pointer',
};

function mergeQueueCopy(state: string): { title: string; description: string; accent: string } {
  switch (state) {
    case 'AWAITING_CHECKS':
      return { title: 'In merge queue', description: 'Waiting for merge queue checks to pass.', accent: '#bf8700' };
    case 'LOCKED':
      return { title: 'In merge queue', description: 'This pull request is locked while the merge queue processes it.', accent: '#bf8700' };
    case 'MERGEABLE':
      return { title: 'In merge queue', description: 'This pull request is ready to be merged by the queue.', accent: '#1f883d' };
    case 'UNMERGEABLE':
      return { title: 'Merge queue blocked', description: 'This pull request cannot currently be merged by the queue.', accent: '#cf222e' };
    default:
      return { title: 'In merge queue', description: 'This pull request is waiting in the merge queue.', accent: '#bf8700' };
  }
}

/**
 * The Overview-tab merge card, restored for the unified PR pane. Shows a
 * github-style "Ready to merge" split-button (or "Merge when ready" for a
 * merge-queue repo), the blocking reasons when it isn't ready, and the
 * queued / merged lifecycle tails. Every action goes through the same
 * user-gated backend endpoints (`mergeLocalPr` / `dequeueLocalPr` /
 * `deleteLocalPrBranch`) that re-check readiness and drive the task phase.
 */
export default function PullMergeBox({ pr, detail, onDone }: {
  pr: LocalPR;
  detail: PullRequestDetailDto | null;
  onDone: () => void;
}) {
  const [phase, setPhase] = useState<'idle' | 'confirm'>('idle');
  const [method, setMethod] = useState<string>('squash');
  const [menuOpen, setMenuOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = (action: Promise<unknown>) => {
    setBusy(true);
    setError(null);
    void action
      .then(() => { setPhase('idle'); onDone(); })
      .catch((e: unknown) => setError(errText(e)))
      .finally(() => setBusy(false));
  };

  // Every non-null GitHub entry state means the PR is already in the queue.
  const queueState = pr.syncedMergeQueueState?.trim().toUpperCase();
  if (queueState) {
    const copy = mergeQueueCopy(queueState);
    return (
      <div style={shellStyle(copy.accent)}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 14, color: '#17191c' }}>{copy.title}</span>
            <span style={{ display: 'block', fontSize: 12.5, color: '#8b949e', marginTop: 2 }}>{copy.description}</span>
          </span>
          <button type="button" style={plainBtn} disabled={busy} onClick={() => run(window.bridge.dequeueLocalPr(pr.id))}>Remove from queue</button>
        </div>
        {error !== null && <div style={{ marginTop: 10, fontSize: 12.5, color: '#cf222e' }}>{error}</div>}
      </div>
    );
  }

  if (pr.status === 'merged') {
    if (pr.branchDeletedAt !== null) return null;
    return (
      <div style={shellStyle('#8250df')}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 14, color: '#17191c' }}>Pull request merged</span>
            <span style={{ display: 'block', fontSize: 12.5, color: '#8b949e', marginTop: 2 }}>You&apos;re all set — the <code>{pr.branchName}</code> branch can be safely deleted.</span>
          </span>
          <button type="button" style={plainBtn} disabled={busy} onClick={() => run(window.bridge.deleteLocalPrBranch(pr.id))}>Delete branch</button>
        </div>
        {error !== null && <div style={{ marginTop: 10, fontSize: 12.5, color: '#cf222e' }}>{error}</div>}
      </div>
    );
  }

  if ((pr.status !== 'remote-open' && pr.status !== 'remote-drafted')
      || pr.remotePrNumber === null) return null;

  const blockers = mergeBlockers(detail);
  const ready = blockers.length === 0;
  const queueMode = pr.syncedMergeQueueEnabled;
  const current = METHODS.find(m => m.key === method) ?? METHODS[0];

  return (
    <div style={shellStyle(ready ? '#1f883d' : '#bf8700')}>
      <div style={{ fontSize: 14, fontWeight: 600, color: '#17191c' }}>
        {ready ? 'Ready to merge' : 'This PR can’t be merged yet'}
      </div>
      {ready ? (
        <div style={{ fontSize: 12.5, color: '#8b949e', marginTop: 2 }}>
          Merging {pr.branchName} into {pr.baseBranch}.
        </div>
      ) : (
        <ul style={{ margin: '8px 0 0', paddingLeft: 18, fontSize: 12.5, color: '#57606a' }}>
          {blockers.map(reason => <li key={reason} style={{ marginBottom: 2 }}>{reason}</li>)}
        </ul>
      )}

      {ready && phase === 'idle' && (
        <div style={{ marginTop: 12 }}>
          {queueMode ? (
            <button type="button" style={greenBtn} disabled={busy} onClick={() => setPhase('confirm')}>Merge when ready</button>
          ) : (
            <span style={{ position: 'relative', display: 'inline-flex' }}>
              <button type="button" style={{ ...greenBtn, borderRadius: '7px 0 0 7px' }} disabled={busy} onClick={() => setPhase('confirm')}>{current.label}</button>
              <button type="button" aria-label="Choose a merge method" aria-haspopup="menu" aria-expanded={menuOpen} style={{ ...greenBtn, borderRadius: '0 7px 7px 0', marginLeft: -1, padding: '6px 9px' }} disabled={busy} onClick={() => setMenuOpen(o => !o)}>▾</button>
              {menuOpen && (
                <div role="menu" style={{ position: 'absolute', zIndex: 20, top: 'calc(100% + 4px)', left: 0, minWidth: 210, padding: 4, border: '1px solid #d5dbe1', borderRadius: 8, background: '#fff', boxShadow: '0 8px 24px rgba(31,35,40,.16)' }}>
                  {METHODS.map(m => (
                    <button key={m.key} type="button" role="menuitem" onClick={() => { setMethod(m.key); setMenuOpen(false); }} style={{ width: '100%', padding: '7px 9px', border: 0, borderRadius: 6, background: m.key === method ? '#f3f5f7' : 'transparent', color: '#17191c', font: 'inherit', fontSize: 12.5, textAlign: 'left', cursor: 'pointer' }}>
                      {m.label}
                    </button>
                  ))}
                </div>
              )}
            </span>
          )}
        </div>
      )}

      {ready && phase === 'confirm' && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontSize: 12.5, color: '#57606a', marginBottom: 8 }}>
            This will {queueMode ? 'add this pull request to the merge queue.' : `${current.verb} your changes and merge them into ${pr.baseBranch}.`}
          </div>
          <span style={{ display: 'inline-flex', gap: 8 }}>
            <button type="button" style={greenBtn} disabled={busy} onClick={() => run(window.bridge.mergeLocalPr(pr.id, method))}>
              {busy ? 'Merging…' : queueMode ? 'Confirm merge when ready' : `Confirm ${current.verb} and merge`}
            </button>
            <button type="button" style={plainBtn} disabled={busy} onClick={() => setPhase('idle')}>Cancel</button>
          </span>
        </div>
      )}

      {error !== null && <div style={{ marginTop: 10, fontSize: 12.5, color: '#cf222e' }}>{error}</div>}
    </div>
  );
}
