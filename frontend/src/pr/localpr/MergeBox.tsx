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
import type { ReactNode } from 'react';
import type { PRCapabilities } from '../prCapabilities';
import type { LocalPR, LocalPRCheck } from '../../types/localPr';
import {
  CheckIcon, ChevronRightIcon, MergeBranchIcon, PullRequestIcon,
} from '../../ui/TaskBrainDesignIcons';
import { CheckRows } from './CheckRows';

/** The brain's dev-end review verdict, once it's concluded (plan-rail-runs.md
 *  R21/R23) — derived by the caller from the PR's brain-authored comments,
 *  since a brain review round never blocks the flip forever. */
export type BrainReviewSummary = { total: number; unresolved: number };

function MergeBoxShell({ children, className = '', tone = 'green' }: {
  children: ReactNode;
  className?: string;
  tone?: 'green' | 'amber' | 'purple';
}) {
  return (
    <div className={`pr-merge-shell ${tone}`}>
      <span className={`mb-branch-icon ${tone}`} aria-hidden="true">
        <PullRequestIcon size={24} strokeWidth={2} />
      </span>
      <div className={`pr-merge-box${className === '' ? '' : ` ${className}`}`}>{children}</div>
    </div>
  );
}

/** Mirrors github.com's own merge-method picker, replacing the old
 *  `MergeDialog` modal — squash stays the default (matches the backend's
 *  own fallback in `PRPublishService.mergeCommand`). */
const MERGE_METHODS: { key: string; label: string; verb: string }[] = [
  { key: 'squash', label: 'Squash and merge', verb: 'squash' },
  { key: 'merge', label: 'Create a merge commit', verb: 'merge' },
  { key: 'rebase', label: 'Rebase and merge', verb: 'rebase' },
];

function BrainReviewTag({ brainReview }: { brainReview?: BrainReviewSummary }) {
  if (brainReview === undefined || brainReview.total === 0) return null;
  if (brainReview.unresolved === 0) {
    return <span className="brain-review-tag ok">✓ Brain-reviewed</span>;
  }
  return <span className="brain-review-tag warn">◆ brain unresolved · {brainReview.unresolved}</span>;
}

/** Mirrors github.com's own merge-box phrasing (docs/mockups/v3/design/
 *  unified-pr-view.html line 566): "All checks have passed" / "N skipped, M
 *  successful checks" — skipped (github.com's word for `neutral`) called
 *  out only when there is at least one. */
function checksSummary(checks: LocalPRCheck[]): { icon: 'green' | 'amber'; headline: string; sub: string } {
  if (checks.length === 0) {
    return { icon: 'amber', headline: 'No checks recorded yet', sub: 'nothing to gate on' };
  }
  const successful = checks.filter(c => c.status === 'passed').length;
  const skipped = checks.filter(c => c.status === 'neutral').length;
  const failed = checks.some(c => c.status === 'failed');
  const running = checks.some(c => c.status === 'running' || c.status === 'pending');
  const passed = successful + skipped;
  if (failed) return { icon: 'amber', headline: `${passed} of ${checks.length} checks have passed`, sub: 'some checks are failing' };
  if (running) return { icon: 'amber', headline: `${passed} of ${checks.length} checks have passed`, sub: 'checks still running' };
  const sub = skipped > 0
    ? `${skipped} skipped, ${successful} successful check${successful === 1 ? '' : 's'}`
    : `${successful} successful check${successful === 1 ? '' : 's'}`;
  return { icon: 'green', headline: 'All checks have passed', sub };
}

/** github.com's "No conflicts with base branch" / conflict-warning line —
 *  sourced from the same GitHub-reported mergeable(State) the dashboard
 *  already syncs (`PR.PRSyncSnapshot`), null until GitHub has computed it. */
function MergeableLine({ mergeable, mergeableState }: { mergeable: boolean | null; mergeableState: string | null }) {
  if (mergeable === null) return null;
  if (mergeable) {
    return (
      <div className="mb-sec">
        <span className="mb-ic green"><CheckIcon size={18} /></span>
        <div className="mb-t">
          <div className="h">No conflicts with base branch</div>
          <div className="s">Changes can be cleanly merged.</div>
        </div>
      </div>
    );
  }
  return (
    <div className="mb-sec">
      <span className="mb-ic amber">!</span>
      <div className="mb-t">
        <div className="h">This branch has conflicts that must be resolved</div>
        {mergeableState !== null && <div className="s">{mergeableState}</div>}
      </div>
    </div>
  );
}

/** The on-demand local-test trigger (local-pr-design.md slice 4) — lives
 *  inside the same bordered card as the checks summary now, instead of
 *  floating unstyled below it. */
function RunTestsRow({ onRunTests, busy }: { onRunTests?: () => void; busy: boolean }) {
  if (onRunTests === undefined) return null;
  return (
    <div className="mb-sec">
      <span className="mb-ic amber">↻</span>
      <div className="mb-t">
        <div className="h">Local tests</div>
        <div className="s">Run the test suite before pushing</div>
      </div>
      <button type="button" className="btn sm" onClick={onRunTests} disabled={busy}>
        {busy ? 'Running tests…' : 'Run tests'}
      </button>
    </div>
  );
}

/** The merge method split-button ("Squash and merge ▾") — a main action
 *  (starts the confirm step with the currently-picked method) plus a
 *  chevron sub-button that opens the method picker, reusing the existing
 *  `.run-menu` dropdown chrome ({@link CommitsDropdown}). Hidden entirely
 *  when the repo uses a merge queue (the queue's configured method wins,
 *  there's nothing to pick). */
function MethodButton({ method, onChange, onConfirm, disabled }: {
  method: string;
  onChange: (method: string) => void;
  onConfirm: () => void;
  disabled: boolean;
}) {
  const [open, setOpen] = useState(false);
  const current = MERGE_METHODS.find(m => m.key === method) ?? MERGE_METHODS[0];
  return (
    <span className="run-menu">
      <button type="button" className="btn green" disabled={disabled} onClick={onConfirm}>
        {current.label}
      </button>
      <button
        type="button"
        className="btn green run-menu__chev"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Choose a merge method"
        disabled={disabled}
        onClick={() => setOpen(o => !o)}
      >
        <span className="chev" aria-hidden><ChevronRightIcon size={12} /></span>
      </button>
      {open && (
        <div className="run-menu__pop" role="menu">
          {MERGE_METHODS.map(m => (
            <button
              key={m.key}
              type="button"
              className="run-menu__item"
              role="menuitem"
              onClick={() => { onChange(m.key); setOpen(false); }}
            >
              {m.label}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}

/**
 * The merge-box accordion (U13e) — replaces the old `<PRActionBar>` +
 * `<PRChecksCard>` pair with one sectioned card: a checks summary that
 * expands into the local + remote check lists, then whichever gate applies
 * (push / merge / submit-review) per the surface's capabilities, plus a
 * strip-count or draft-count warning where relevant.
 *
 * <p>The merge gate itself is a small inline state machine (idle → confirm
 * → queued/merged), replacing the old `MergeDialog` popup — mirrors
 * github.com's own in-card confirm swap. A queue-enabled repo shows "Merge
 * when ready" instead of a method picker (the queue's configured method
 * wins); confirming either one calls {@link onMerge}, and the box then
 * reflects whatever GitHub actually did (queued vs. merged) from the PR's
 * own synced fields, not local optimism.
 */
export function MergeBox({
  pr, capabilities, localChecks, remoteChecks, openComments, localTestsFailing = false,
  pendingStripCount, draftCount, brainReview, onPush, onAskAgent, onMerge,
  onDequeue, onDeleteBranch, onPublishReview, onDiscardDrafts, onRunTests, runTestsBusy = false,
  hidePublishGate = false,
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
  onMerge?: (method: string) => void;
  onDequeue?: () => void;
  onDeleteBranch?: () => void;
  onPublishReview?: () => void;
  onDiscardDrafts?: () => void;
  /** Manually re-run the local test suite. Omitted when there's no PR to run
   *  tests against yet. */
  onRunTests?: () => void;
  runTestsBusy?: boolean;
  hidePublishGate?: boolean;
}) {
  // Collapsed by default, matching github.com's own merge-box — a reviewer
  // wants the aggregate line, not 60 individual rows, at a glance.
  const [checksOpen, setChecksOpen] = useState(false);
  const [mergePhase, setMergePhase] = useState<'idle' | 'confirm'>('idle');
  const [method, setMethod] = useState('squash');

  // Queued/merged are the two terminal-ish outcomes of a merge attempt —
  // github.com shows either as the box's ENTIRE content (no checks summary
  // alongside), so these short-circuit before anything else below.
  const queued = capabilities.merge && pr.status === 'remote-open' && pr.syncedMergeQueueState === 'QUEUED';
  const merged = pr.status === 'merged';

  if (queued) {
    return (
      <MergeBoxShell className="queued" tone="amber">
        <div className="mb-sec">
          <span className="mb-ic amber">↻</span>
          <div className="mb-t">
            <div className="h">Queued to merge…</div>
            <div className="s">This pull request is next up in the merge queue.</div>
          </div>
          {onDequeue !== undefined && (
            <button type="button" className="btn sm" onClick={onDequeue}>Remove from queue</button>
          )}
        </div>
      </MergeBoxShell>
    );
  }

  if (merged) {
    const canDeleteBranch = pr.branchDeletedAt === null && onDeleteBranch !== undefined;
    return (
      <MergeBoxShell className="merged" tone="purple">
        <div className="mb-sec">
          <span className="mb-ic purple"><MergeBranchIcon size={13} strokeWidth={2.2} /></span>
          <div className="mb-t">
            <div className="h">Pull request successfully merged and closed</div>
            <div className="s">
              {canDeleteBranch
                ? <>You're all set — the <code>{pr.branchName}</code> branch can be safely deleted.</>
                : <>You're all set — the branch has been merged.</>}
            </div>
          </div>
          {canDeleteBranch && (
            <button type="button" className="btn sm" onClick={onDeleteBranch}>Delete branch</button>
          )}
        </div>
      </MergeBoxShell>
    );
  }

  const allChecks = [...localChecks, ...remoteChecks];
  const summary = checksSummary(allChecks);

  const showPushGate = capabilities.push && pr.status === 'local-open';
  // Drafts show the gate too — merging one marks it ready for review
  // first (the backend flips it before merging/queueing).
  const showMergeGate = capabilities.merge;
  const draft = pr.status === 'remote-drafted';
  const showPublishGate = !hidePublishGate && capabilities.publishReview && draftCount > 0;
  const hasMergeableData = pr.syncedMergeable !== null;
  if (!showPushGate && !showMergeGate && !showPublishGate
      && allChecks.length === 0 && !hasMergeableData && onRunTests === undefined) {
    return null;
  }
  // Matches github.com's fully-green card border once checks pass AND
  // there's nothing blocking a merge.
  const allGood = summary.icon === 'green' && pr.syncedMergeable === true;
  const mergeBlocked = openComments > 0 || pr.syncedMergeable === false;

  const confirmMerge = () => {
    setMergePhase('idle');
    onMerge?.(method);
  };

  return (
    <MergeBoxShell
      className={allGood ? 'ok' : ''}
      tone={pr.syncedMergeable === false ? 'amber' : summary.icon}
    >
      <button
        type="button"
        className="mb-sec mb-summary clickable"
        aria-expanded={checksOpen}
        onClick={() => setChecksOpen(o => !o)}
      >
        <span className={`mb-ic ${summary.icon}`}>
          {summary.icon === 'green' ? <CheckIcon size={18} /> : '●'}
        </span>
        <div className="mb-t">
          <div className="h">{summary.headline}</div>
          <div className="s">{summary.sub}</div>
        </div>
        <span className={`mb-chevron${checksOpen ? ' open' : ''}`} aria-hidden="true">
          <ChevronRightIcon size={16} />
        </span>
      </button>
      {checksOpen && allChecks.length > 0 && (
        <div className="check-list">
          {localChecks.length > 0 && <div className="check-group">LOCAL</div>}
          <CheckRows checks={localChecks} />
          {remoteChecks.length > 0 && <div className="check-group">REMOTE</div>}
          <CheckRows checks={remoteChecks} />
        </div>
      )}
      <RunTestsRow onRunTests={onRunTests} busy={runTestsBusy} />
      <MergeableLine mergeable={pr.syncedMergeable} mergeableState={pr.syncedMergeableState} />

      {showPushGate && (
        <>
          <div className="mb-sec">
            <span className="mb-ic green"><CheckIcon size={18} /></span>
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
              Approve &amp; push to GitHub
            </button>
            {(openComments > 0 || localTestsFailing) && onAskAgent !== undefined && (
              <span className="alt">or <button type="button" className="pr-link-btn" onClick={onAskAgent}>ask the agent to address comments first</button></span>
            )}
          </div>
        </>
      )}

      {showMergeGate && mergePhase === 'confirm' && (
        <>
          <div className="mb-sec">
            <div className="mb-t">
              <div className="s">
                {draft ? 'This will mark the draft ready for review, then ' : 'This will '}
                {pr.syncedMergeQueueEnabled
                  ? 'add this pull request to the merge queue.'
                  : `${MERGE_METHODS.find(m => m.key === method)?.verb} your changes and merge them into ${pr.baseBranch}.`}
              </div>
            </div>
          </div>
          <div className="mb-actions">
            <button type="button" className="btn green" onClick={confirmMerge}>
              {pr.syncedMergeQueueEnabled
                ? 'Confirm merge when ready'
                : `Confirm ${MERGE_METHODS.find(m => m.key === method)?.verb} and merge`}
            </button>
            <button type="button" className="btn" onClick={() => setMergePhase('idle')}>Cancel</button>
          </div>
        </>
      )}

      {showMergeGate && mergePhase === 'idle' && (
        <>
          <div className="mb-sec">
            <span className="mb-ic green"><MergeBranchIcon size={18} /></span>
            <div className="mb-t">
              <div className="h">
                {openComments > 0
                  ? `${openComments} open comment${openComments === 1 ? '' : 's'} — resolve before merge`
                  : pr.syncedMergeable === false ? 'Merge blocked'
                  : draft ? 'Draft — merging marks it ready first' : 'Ready to merge'}
              </div>
              <div className="s">
                {pr.syncedMergeable === false
                  ? `Resolve branch conflicts before merging into ${pr.baseBranch}.`
                  : `Merging opens ${pr.baseBranch} ← ${pr.branchName}`}
              </div>
            </div>
          </div>
          <div className="mb-actions">
            {pr.syncedMergeQueueEnabled ? (
              <button
                type="button"
                className="btn green"
                disabled={mergeBlocked}
                onClick={() => setMergePhase('confirm')}
              >
                Merge when ready
              </button>
            ) : (
              <MethodButton
                method={method}
                onChange={setMethod}
                onConfirm={() => setMergePhase('confirm')}
                disabled={mergeBlocked}
              />
            )}
            {openComments > 0 && pr.syncedMergeable !== false && (
              <button type="button" className="btn" onClick={() => setMergePhase('confirm')}>Merge anyway</button>
            )}
          </div>
          <div className="mb-cli-hint">
            {pr.syncedMergeQueueEnabled
              ? `This repository uses the merge queue for all merges into the ${pr.baseBranch} branch.`
              : 'You can also merge this with the command line.'}
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
              Submit review ({draftCount})
            </button>
            {onDiscardDrafts !== undefined && (
              <button type="button" className="btn" onClick={onDiscardDrafts}>Discard drafts</button>
            )}
          </div>
        </>
      )}
    </MergeBoxShell>
  );
}
