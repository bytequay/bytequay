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
import type { LocalPR } from '../../types/localPr';

/**
 * The GitHub merge-box-style action bar (decisions #54 + #57). In a local
 * state it's the amber "Approve & push to GitHub" box; in `remote-open` it's
 * the green "Merge pull request" box, disabled while open comments remain.
 * Terminal states (merged / closed / drafting) render no bar.
 */
/** The brain's dev-end review verdict, once it's concluded (plan-rail-runs.md
 *  R21/R23) — derived by the caller from the local PR's brain-authored
 *  comments, since a brain review round never blocks the flip forever. */
export type BrainReviewSummary = { total: number; unresolved: number };

function BrainReviewTag({ brainReview }: { brainReview?: BrainReviewSummary }) {
  if (brainReview === undefined || brainReview.total === 0) return null;
  if (brainReview.unresolved === 0) {
    return <span className="brain-review-tag ok">✓ Brain-reviewed</span>;
  }
  return <span className="brain-review-tag warn">◆ brain unresolved · {brainReview.unresolved}</span>;
}

export function PRActionBar({
  pr, openComments, localChecksPassed, localTestsFailing = false, brainReview,
  onPush, onAskAgent, onMerge, onMergeAnyway,
}: {
  pr: LocalPR;
  openComments: number;
  localChecksPassed: boolean;
  /** The most recently recorded local check run failed — hard-blocks Push
   *  (design doc slice 5's promotion gate). No checks recorded at all does
   *  NOT count as failing (nothing to gate on). */
  localTestsFailing?: boolean;
  /** The brain's dev-end review outcome, when it ran (R21) — undefined or
   *  zero-total renders no tag at all (no brain review data for this PR). */
  brainReview?: BrainReviewSummary;
  onPush?: () => void;
  onAskAgent?: () => void;
  onMerge?: () => void;
  onMergeAnyway?: () => void;
}) {
  if (pr.status === 'local-open') {
    const gated = openComments > 0 || localTestsFailing;
    return (
      <div className="pr-action-bar">
        <div className="ab-head">
          <span className="ic">✓</span>
          <span className="title">Ready to push to GitHub as a Draft PR</span>
          <BrainReviewTag brainReview={brainReview} />
          <span className="subtitle">
            {localChecksPassed ? 'local checks passed' : 'local checks pending'}
            {openComments > 0 ? ` · ${openComments} open comment${openComments === 1 ? '' : 's'}` : ''}
          </span>
        </div>
        <div className="ab-body">
          {localTestsFailing ? (
            <>Local tests are currently <b>failing</b>. Fix them (or ask the agent to) before
              promoting.</>
          ) : openComments > 0 ? (
            <>You have <b>{openComments} open review comment{openComments === 1 ? '' : 's'}</b>. Resolve
              or dismiss them, or ask the agent to address them, before promoting.</>
          ) : (
            <>Pushing opens <code>{pr.baseBranch} ← {pr.branchName}</code> as a <b>Draft</b> — flip to
              ready-for-review on GitHub after remote CI is green.</>
          )}
        </div>
        <div className="ab-actions">
          <button
            type="button"
            className="approve-btn"
            onClick={onPush}
            disabled={gated}
            style={gated ? { opacity: 0.55, cursor: 'not-allowed' } : undefined}
          >
            ↑ Approve &amp; push to GitHub<span className="kbd">⌘↵</span>
          </button>
          {gated && onAskAgent !== undefined && (
            <button type="button" className="secondary-btn" onClick={onAskAgent}>
              Ask agent to address comments first
            </button>
          )}
        </div>
      </div>
    );
  }

  if (pr.status === 'remote-open') {
    const gated = openComments > 0;
    return (
      <div
        className="pr-action-bar"
        style={{ background: 'rgba(34,197,94,0.06)', borderColor: 'rgba(34,197,94,0.28)' }}
      >
        <div className="ab-head">
          <span className="ic" style={{ background: 'var(--orange)' }}>◐</span>
          <span className="title">
            {gated
              ? `${openComments} open comment${openComments === 1 ? '' : 's'} · resolve before merge`
              : 'Ready to merge'}
          </span>
          <span className="subtitle">remote CI</span>
        </div>
        <div className="ab-body">
          {gated
            ? 'Merge is gated until open comments are resolved. Override with Merge anyway if you are confident.'
            : `Merging opens ${pr.baseBranch} ← ${pr.branchName}.`}
        </div>
        <div className="ab-actions">
          <button
            type="button"
            className="approve-btn"
            onClick={onMerge}
            disabled={gated}
            style={gated ? { opacity: 0.55, cursor: 'not-allowed' } : undefined}
          >
            ⎇ Merge pull request<span className="kbd">⌘↵</span>
          </button>
          {gated && onMergeAnyway !== undefined && (
            <button type="button" className="secondary-btn" onClick={onMergeAnyway}>
              Merge anyway ▾
            </button>
          )}
        </div>
      </div>
    );
  }

  // local-drafted (agent still working), remote-drafted, merged, closed: no bar.
  return null;
}
