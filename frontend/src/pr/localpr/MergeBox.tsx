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
import type { PRCapabilities } from '../prCapabilities';
import type { LocalPR, LocalPRCheck } from '../../types/localPr';

/** The brain's dev-end review verdict, once it's concluded (plan-rail-runs.md
 *  R21/R23) — derived by the caller from the PR's brain-authored comments,
 *  since a brain review round never blocks the flip forever. */
export type BrainReviewSummary = { total: number; unresolved: number };

function BrainReviewTag({ brainReview }: { brainReview?: BrainReviewSummary }) {
  if (brainReview === undefined || brainReview.total === 0) return null;
  if (brainReview.unresolved === 0) {
    return <span className="brain-review-tag ok">✓ Brain-reviewed</span>;
  }
  return <span className="brain-review-tag warn">◆ brain unresolved · {brainReview.unresolved}</span>;
}

function checksSummary(checks: LocalPRCheck[]): { icon: 'green' | 'amber'; headline: string; sub: string } {
  if (checks.length === 0) {
    return { icon: 'amber', headline: 'No checks recorded yet', sub: 'nothing to gate on' };
  }
  const passed = checks.filter(c => c.status === 'passed' || c.status === 'neutral').length;
  const failed = checks.some(c => c.status === 'failed');
  const running = checks.some(c => c.status === 'running' || c.status === 'pending');
  if (failed) return { icon: 'amber', headline: `${passed} of ${checks.length} checks have passed`, sub: 'some checks are failing' };
  if (running) return { icon: 'amber', headline: `${passed} of ${checks.length} checks have passed`, sub: 'checks still running' };
  return { icon: 'green', headline: 'All checks have passed', sub: `${checks.length} successful check${checks.length === 1 ? '' : 's'}` };
}

const CHECK_GLYPH: Record<string, { cls: string; glyph: string }> = {
  passed: { cls: 'ok', glyph: '✓' },
  neutral: { cls: 'ok', glyph: '✓' },
  failed: { cls: 'fail', glyph: '✗' },
  running: { cls: 'skip', glyph: '●' },
  pending: { cls: 'skip', glyph: '·' },
};

function CheckRows({ checks }: { checks: LocalPRCheck[] }) {
  return (
    <>
      {checks.map(c => {
        const g = CHECK_GLYPH[c.status] ?? CHECK_GLYPH.pending;
        return (
          <div className="check-row" key={c.id}>
            <span className={`st ${g.cls}`}>{g.glyph}</span>
            <span className="nm">{c.name}</span>
            <span className="res">
              {c.durationMs !== null ? `${Math.round(c.durationMs / 1000)}s` : c.status}
            </span>
          </div>
        );
      })}
    </>
  );
}

/**
 * The merge-box accordion (U13e) — replaces the old `<PRActionBar>` +
 * `<PRChecksCard>` pair with one sectioned card: a checks summary that
 * expands into the local + remote check lists, then whichever gate applies
 * (push / merge / submit-review) per the surface's capabilities, plus a
 * strip-count or draft-count warning where relevant.
 */
export function MergeBox({
  pr, capabilities, localChecks, remoteChecks, openComments, localTestsFailing = false,
  pendingStripCount, draftCount, brainReview, onPush, onAskAgent, onMerge, onMergeAnyway,
  onPublishReview, onDiscardDrafts,
}: {
  pr: LocalPR;
  capabilities: PRCapabilities;
  localChecks: LocalPRCheck[];
  remoteChecks: LocalPRCheck[];
  openComments: number;
  localTestsFailing?: boolean;
  pendingStripCount: number;
  draftCount: number;
  brainReview?: BrainReviewSummary;
  onPush?: () => void;
  onAskAgent?: () => void;
  onMerge?: () => void;
  onMergeAnyway?: () => void;
  onPublishReview?: () => void;
  onDiscardDrafts?: () => void;
}) {
  const [checksOpen, setChecksOpen] = useState(true);
  const allChecks = [...localChecks, ...remoteChecks];
  const summary = checksSummary(allChecks);

  const showPushGate = capabilities.push && pr.status === 'local-open';
  const showMergeGate = capabilities.merge && pr.status === 'remote-open';
  const showPublishGate = capabilities.publishReview && draftCount > 0;
  if (!showPushGate && !showMergeGate && !showPublishGate && allChecks.length === 0) {
    return null;
  }

  return (
    <div className="pr-merge-box">
      <div className="mb-sec clickable" onClick={() => setChecksOpen(o => !o)}>
        <span className={`mb-ic ${summary.icon}`}>{summary.icon === 'green' ? '✓' : '●'}</span>
        <div className="mb-t">
          <div className="h">{summary.headline}<span className="chev">{checksOpen ? '▴' : '▾'}</span></div>
          <div className="s">{summary.sub}</div>
        </div>
      </div>
      {checksOpen && allChecks.length > 0 && (
        <div className="check-list">
          {localChecks.length > 0 && <div className="check-group">LOCAL</div>}
          <CheckRows checks={localChecks} />
          {remoteChecks.length > 0 && <div className="check-group">REMOTE</div>}
          <CheckRows checks={remoteChecks} />
        </div>
      )}

      {showPushGate && (
        <>
          <div className="mb-sec">
            <span className="mb-ic green">✓</span>
            <div className="mb-t">
              <div className="h">Ready to push to GitHub as a Draft PR <BrainReviewTag brainReview={brainReview} /></div>
              <div className="s">
                Pushes <code>{pr.branchName}</code> and opens a draft
                {openComments > 0 ? ` · ${openComments} open comment${openComments === 1 ? '' : 's'}` : ''}
                {localTestsFailing ? ' · local tests failing' : ''}
              </div>
            </div>
          </div>
          {pendingStripCount > 0 && (
            <div className="mb-warn">
              <b>{pendingStripCount} local comment{pendingStripCount === 1 ? '' : 's'}</b> will be <b>stripped on push</b> — local review never leaves your machine.
            </div>
          )}
          <div className="mb-actions">
            <button
              type="button"
              className="btn green"
              onClick={onPush}
              disabled={openComments > 0 || localTestsFailing}
            >
              Approve &amp; push to GitHub<span className="kbd">⌘↵</span>
            </button>
            {(openComments > 0 || localTestsFailing) && onAskAgent !== undefined && (
              <span className="alt">or <button type="button" className="pr-link-btn" onClick={onAskAgent}>ask the agent to address comments first</button></span>
            )}
          </div>
        </>
      )}

      {showMergeGate && (
        <>
          <div className="mb-sec">
            <span className="mb-ic green">⎇</span>
            <div className="mb-t">
              <div className="h">{openComments > 0 ? `${openComments} open comment${openComments === 1 ? '' : 's'} — resolve before merge` : 'Ready to merge'}</div>
              <div className="s">Merging opens {pr.baseBranch} ← {pr.branchName}</div>
            </div>
          </div>
          <div className="mb-actions">
            <button type="button" className="btn green" onClick={onMerge} disabled={openComments > 0}>
              Merge pull request<span className="kbd">⌘↵</span>
            </button>
            {openComments > 0 && onMergeAnyway !== undefined && (
              <button type="button" className="btn" onClick={onMergeAnyway}>Merge anyway ▾</button>
            )}
          </div>
        </>
      )}

      {showPublishGate && (
        <>
          <div className="mb-sec">
            <span className="mb-ic amber">✎</span>
            <div className="mb-t">
              <div className="h">{draftCount} pending review comment{draftCount === 1 ? '' : 's'}</div>
              <div className="s">Drafts are visible only to you until you submit the review — nothing has been sent to GitHub.</div>
            </div>
          </div>
          <div className="mb-actions">
            <button type="button" className="btn green" onClick={onPublishReview}>
              Submit review ({draftCount})<span className="kbd">⌘↵</span>
            </button>
            {onDiscardDrafts !== undefined && (
              <button type="button" className="btn" onClick={onDiscardDrafts}>Discard drafts</button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
