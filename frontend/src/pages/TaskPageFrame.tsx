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
import { useEffect, useState, type ReactNode } from 'react';
import type { DiffInlineComment } from '../diff/DiffInlineComments';
import ResizeHandle from '../ResizeHandle';
import { Composer, Main, Shell, type ComposerUsage } from '../ui/shell';
import { usePaneWidth } from '../ui/shell/usePaneWidth';
import { CheckIcon } from '../ui/TaskBrainDesignIcons';
import { ChevronIcon, PullRequestBranchIcon, TrunkLineIcon } from '../ui/workspace';
import { SubmitReviewDrawer, type ReviewVerdict } from './SubmitReviewDrawer';
import { RunMenu } from '../ui/shell/RunMenu';

export type TaskPageComposer = {
  value: string;
  onChange: (next: string) => void;
  onSubmit: () => void;
  busy?: boolean;
  queueWhenBusy?: boolean;
  /** Interrupt the running turn; wires the composer's Stop button. */
  onStop?: () => void;
  modePill?: ReactNode;
  placeholder?: string;
  images?: string[];
  onImagesChange?: (next: string[]) => void;
  closedNote?: string;
  usage?: ComposerUsage;
  /** Compact run context shown at the right edge of the locked toolbar. */
  meta?: ReactNode;
};

export type TaskPagePr = {
  number: number;
  status: string;
  onOpen?: () => void;
  /** Opens the pull request on GitHub (the header #N chip); falls back to onOpen. */
  onOpenRemote?: () => void;
};

export type TaskPageChanges = {
  additions: number;
  deletions: number;
  onOpen?: () => void;
};

export type TaskPageRun = {
  statusLabel?: string;
  paused?: boolean;
  terminal?: boolean;
  onPause?: () => void;
  onResume?: () => void;
  onClose?: () => void;
};

export function TaskPageFrame({
  surface, pageTitle, taskTitle, taskNumber, trunkLabel = 'Trunk', statusLabel,
  sidebar, conversation, conversationIndex, composer, run, pr, prPane, changes, stageKey,
  onOpenTrunk, onOpenTask, onSubmitReview, submittingReview = false,
  pendingReviewComments = [], onRemovePendingReviewComment, openPrToken,
  leadingToolbar,
}: {
  surface: 'brain' | 'stage';
  pageTitle: string;
  taskTitle: string;
  taskNumber?: number;
  trunkLabel?: string;
  statusLabel?: string;
  run?: TaskPageRun;
  sidebar?: ReactNode;
  conversation: ReactNode;
  conversationIndex?: ReactNode;
  composer: TaskPageComposer;
  pr?: TaskPagePr;
  prPane?: ReactNode;
  changes?: TaskPageChanges;
  /** Short stage identity used by the composer pill, e.g. "dev". */
  stageKey?: string;
  onOpenTrunk?: () => void;
  onOpenTask?: () => void;
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  pendingReviewComments?: DiffInlineComment[];
  onRemovePendingReviewComment?: (commentId: string) => void;
  /** A changed token forces the PR column open. */
  openPrToken?: number;
  /** Rare state reminders share the locked pill row instead of adding chrome. */
  leadingToolbar?: ReactNode;
}) {
  // The locked frame starts with the PR column open. Keep that intent while
  // the local-PR bundle is loading so an async pane does not arrive folded.
  const [prOpen, setPrOpen] = useState(true);
  const [submitReviewOpen, setSubmitReviewOpen] = useState(false);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();

  useEffect(() => {
    if (openPrToken !== undefined) setPrOpen(true);
  }, [openPrToken]);

  const showPr = prOpen && prPane !== undefined;
  const runControls = run ?? (statusLabel === undefined ? undefined : { statusLabel });
  const badge = surface === 'brain' ? 'BRAIN' : `${stageKey?.toUpperCase() ?? 'DEV'} STAGE`;
  const header = (
    <div className="workspace-task-header">
      <button type="button" className="workspace-task-header__crumb" onClick={onOpenTrunk}>
        <span><TrunkLineIcon size={11} /></span>{trunkLabel}
      </button>
      <span className="workspace-task-header__separator" aria-hidden><ChevronIcon size={11} /></span>
      {surface === 'stage' && (
        <>
          <button type="button" className="workspace-task-header__crumb" onClick={onOpenTask}>
            Task #{taskNumber ?? 1}
          </button>
          <span className="workspace-task-header__separator" aria-hidden><ChevronIcon size={11} /></span>
        </>
      )}
      <span className={`workspace-task-header__badge is-${surface}`}>{badge}</span>
      {surface === 'brain' && <strong className="workspace-task-header__title">{pageTitle}</strong>}
      {pr !== undefined && (
        <button type="button" className="workspace-task-header__pr" title="Open pull request on GitHub"
          onClick={pr.onOpenRemote ?? pr.onOpen ?? (() => setPrOpen(true))}>
          <PullRequestBranchIcon size={10} />#{pr.number} {displayPrStatus(pr.status)}
        </button>
      )}
      <span className="workspace-task-header__grow" />
      {runControls !== undefined && (
        <RunMenu
          hideStatus
          statusLabel={runControls.statusLabel}
          paused={runControls.paused}
          terminal={runControls.terminal}
          onPause={runControls.onPause}
          onResume={runControls.onResume}
          onClose={runControls.onClose}
        />
      )}
      {onSubmitReview !== undefined && (
        <button type="button" className="workspace-task-header__submit"
          onClick={submittingReview ? undefined : () => setSubmitReviewOpen(true)}>
          <CheckIcon size={13} strokeWidth={2.4} />
          {submittingReview ? 'Submitting…' : `Submit review • ${pendingReviewComments.length}`}
        </button>
      )}
      {prPane !== undefined && (
        <button type="button" className="workspace-task-header__panel" aria-label="Toggle PR panel"
          aria-pressed={showPr} title="Toggle PR panel" onClick={() => setPrOpen(open => !open)}>
          <PanelIcon />
        </button>
      )}
    </div>
  );

  const toolbar = (
    <>
      {leadingToolbar}
      {changes !== undefined && (
        <button type="button" className="workspace-task-artifact-pill" onClick={changes.onOpen}>
          Changes
          <span className="is-add">+{changes.additions}</span>
          <span className="is-del">−{changes.deletions}</span>
        </button>
      )}
      {pr !== undefined && (
        <button type="button" className="workspace-task-artifact-pill" title="Open pull request overview"
          onClick={pr.onOpen ?? (() => setPrOpen(true))}>
          <span className="workspace-task-artifact-pill__pr"><PullRequestBranchIcon size={12} /></span>
          PR <span className="is-muted">#{pr.number}</span>
        </button>
      )}
    </>
  );

  return (
    <div className={`task-brain workspace-task-v2 workspace-task-v2--${surface}`}>
      <Shell fullWidth={sidebar === undefined}>
        {sidebar}
        <Main topBar={header}>
          <div ref={bodyRef} className={`workspace-task-v2__body${showPr ? ' with-pr' : ''}`}>
            <div className="workspace-task-v2__conversation">
              <div className="conv-index-host">
                {conversation}
                {conversationIndex}
              </div>
              <Composer
                variant="workspace-v2"
                value={composer.value}
                onChange={composer.onChange}
                onSubmit={composer.onSubmit}
                busy={composer.busy}
                queueWhenBusy={composer.queueWhenBusy}
                onStop={composer.onStop}
                modePill={composer.modePill}
                placeholder={composer.placeholder}
                images={composer.images}
                onImagesChange={composer.onImagesChange}
                closedNote={composer.closedNote}
                toolbar={toolbar}
                meta={composer.meta ?? taskTitle}
                usage={composer.usage}
              />
            </div>
            {showPr && (
              <aside className="workspace-task-v2__pr" style={{ width: paneWidth }}>
                <ResizeHandle
                  className="pane-resize pl-hov-drag"
                  ariaLabel="Resize pull request panel"
                  onResize={onResize}
                  style={{ position: 'absolute', left: -3, top: 0, bottom: 0, width: 6, zIndex: 5 }}
                />
                {prPane}
              </aside>
            )}
          </div>
        </Main>
        {onSubmitReview !== undefined && (
          <SubmitReviewDrawer
            open={submitReviewOpen}
            submitting={submittingReview}
            pendingComments={pendingReviewComments}
            onRemovePending={onRemovePendingReviewComment}
            onClose={() => setSubmitReviewOpen(false)}
            onSubmit={async (body, verdict) => {
              await onSubmitReview(body, verdict);
              setSubmitReviewOpen(false);
            }}
          />
        )}
      </Shell>
    </div>
  );
}

function displayPrStatus(status: string): string {
  if (status === 'local-drafted' || status === 'remote-drafted') return 'DRAFT';
  if (status === 'local-open' || status === 'remote-open') return 'OPEN';
  return status.toUpperCase();
}

function PanelIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" aria-hidden>
      <rect x="3" y="4" width="18" height="16" rx="2.2" />
      <path d="M15 4v16" />
    </svg>
  );
}
