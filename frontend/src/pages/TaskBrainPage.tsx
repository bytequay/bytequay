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
import type { ReactNode } from 'react';
import ResizeHandle from '../ResizeHandle';
import { IconBtn, MergeIcon, Pill } from '../ui/primitives';
import {
  Composer, CtxChip, Grow, Main, RunMenu, Shell, TopBar, TopBarButton, TopBarTitle,
  usePaneWidth,
} from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import { PlanReminderTab } from './PlanOverlay';
import { SubmitReviewDrawer } from './SubmitReviewDrawer';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import type { DiffInlineComment } from '../diff/DiffInlineComments';

type BrainTab = 'pr';

/**
 * The task brain surface (frame 2): the 2-pane shell with the brain
 * conversation in the centre, a stage-chip strip + lifecycle Run menu in
 * the top bar, and a collapsible right PR pane. The sidebar and
 * conversation are slots; the pane content is composed by the host from
 * the Phase-4 tab components. The model selector lives in the composer's
 * mode pill (the relocated WORK MODEL card); lifecycle controls live in
 * the Run menu.
 */
export function TaskBrainPage({
  task, pr, sidebar, conversation, collapsed = false, composer, run = {},
  tabs, planReminder, onRevealPlan, onOpenCi,
  onSubmitReview, submittingReview = false, openTabRequest,
  pendingReviewComments = [], onRemovePendingReviewComment,
}: {
  task: { pillLabel: string; title: string; branch?: string; finished?: boolean };
  /** The linked pull request, shown as a clickable chip once the task is
   *  shipped — clicking opens the PR on GitHub. */
  pr?: { number: number; status: string; onOpen: () => void };
  sidebar?: ReactNode;
  conversation: ReactNode;
  collapsed?: boolean;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    queueWhenBusy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
    images?: string[];
    onImagesChange?: (next: string[]) => void;
  };
  run?: {
    statusLabel?: string;
    paused?: boolean;
    terminal?: boolean;
    onPause?: () => void;
    onResume?: () => void;
    onClose?: () => void;
  };
  /** Pane contents; Changes may be supplied by older hosts but is not shown
   *  in the task brain's right pane. */
  tabs: { pr?: ReactNode; code?: ReactNode };
  /** Shows a reminder tab above the composer while a plan needs attention:
   *  'awaiting' → the plan is unreviewed (orange, animated flowing border);
   *  'locked' → the plan is finalized (purple, static). Undefined hides it. */
  planReminder?: 'awaiting' | 'locked';
  /** Click handler for the reminder pill — opens the original execution plan
   *  card. When omitted, the pill falls back to scrolling to the inline card
   *  or opening the right-pane Plan tab. */
  onRevealPlan?: () => void;
  /** Shows a green, glowing reminder pill above the composer while a shipped
   *  draft's CI is green and the mark-ready gate is parked — clicking it
   *  opens the Changes tab. */
  markReadyReminder?: boolean;
  onOpenCi?: () => void;
  /** Submits the reviewer's comment + verdict (from the Submit-review
   *  drawer), plus any unresolved comments on the task's diff, to the dev
   *  agent as a steering turn. Undefined hides the top-bar button. */
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  /** Draft comments the drawer lists so the reviewer sees exactly what a
   *  submission will send. Omit where no draft-comment source is wired up. */
  pendingReviewComments?: DiffInlineComment[];
  onRemovePendingReviewComment?: (commentId: string) => void;
  /** Force-opens the right PR pane from outside (the live-plan rail's gate
   *  nodes) — a fresh object (new `token`) re-fires even if it is already open. */
  openTabRequest?: { tab: BrainTab; token: number };
}) {
  const available: { key: BrainTab; label: string; node: ReactNode }[] = [
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
  ];
  // No PR yet (task hasn't opened one) means nothing to show in the pane.
  const hasTabs = available.length > 0;
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();
  const [submitReviewOpen, setSubmitReviewOpen] = useState(false);

  useEffect(() => {
    if (openTabRequest === undefined) return;
    setPaneOpen(true);
  }, [openTabRequest]);

  const active = available[0];

  // The PR pill mirrors the top-right pane button: it folds/unfolds the
  // right pane, without acting as a tab selector.
  const togglePane = () => setPaneOpen(o => !o);

  // Reveal the plan when the reminder tab is clicked: scroll to the inline
  // plan card if it's shown in the conversation (planning live). The host
  // supplies onRevealPlan to open the plan overlay in every other case.
  const revealPlan = () => {
    document.querySelector('.conv-col .plan-card')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  const topBar = (
    <TopBar>
      <Pill kind="task" icon="▣">{task.pillLabel}</Pill>
      <TopBarTitle>{task.finished === true && <MergeIcon />}{task.title}</TopBarTitle>
      {task.branch !== undefined && <CtxChip>{task.branch}</CtxChip>}
      {pr !== undefined && (
        <button type="button" className="pr-chip" onClick={pr.onOpen} title="Open the pull request on GitHub">
          <span className="pr-chip__num">#{pr.number}</span>
          <span className="pr-chip__status">{pr.status}</span>
        </button>
      )}
      <Grow />
      <RunMenu
        hideStatus
        statusLabel={run.statusLabel}
        paused={run.paused}
        terminal={run.terminal}
        onPause={run.onPause}
        onResume={run.onResume}
        onClose={run.onClose}
      />
      {onSubmitReview !== undefined && (
        <TopBarButton
          variant="submit"
          icon="✓"
          onClick={submittingReview ? undefined : () => setSubmitReviewOpen(true)}
        >
          {submittingReview
            ? 'Submitting…'
            : pendingReviewComments.length > 0
              ? `Submit review (${pendingReviewComments.length})`
              : 'Submit review'}
        </TopBarButton>
      )}
      {hasTabs && (
        <IconBtn active={paneOpen} ariaLabel="Toggle right pane" onClick={() => setPaneOpen(o => !o)}>◧</IconBtn>
      )}
    </TopBar>
  );

  const showPane = paneOpen && hasTabs && active !== undefined;

  return (
    <Shell collapsed={collapsed} fullWidth={sidebar === undefined}>
      {sidebar}
      <Main topBar={topBar}>
        <div
          ref={bodyRef}
          className={showPane ? 'body with-pane' : 'body'}
          style={showPane ? { gridTemplateColumns: `minmax(0, 1fr) 5px ${paneWidth}px` } : undefined}
        >
          <div className="conv-col">
            {conversation}
            {/* Same row as the development stage: the plan reminder pill sits
                on the left, the tab chips align to the right. */}
            <div className="chip-reminder-row">
              {planReminder !== undefined && (
                <PlanReminderTab state={planReminder} onClick={onRevealPlan ?? revealPlan} />
              )}
              <InlineChips chips={[
                ...available.map(t => ({ label: t.label, active: paneOpen, onClick: togglePane })),
                ...(onOpenCi !== undefined ? [{ icon: '✓', label: 'CI Status', onClick: onOpenCi }] : []),
              ]}
              />
            </div>
            <Composer
              value={composer.value}
              onChange={composer.onChange}
              onSubmit={composer.onSubmit}
              busy={composer.busy}
              queueWhenBusy={composer.queueWhenBusy}
              modePill={composer.modePill}
              placeholder={composer.placeholder}
              images={composer.images}
              onImagesChange={composer.onImagesChange}
            />
          </div>
          {showPane && (
            <ResizeHandle onResize={onResize} className="pane-resize" ariaLabel="Resize the side pane" />
          )}
          {showPane && (
            <RightPane>
              <RightPane.Content>{active.node}</RightPane.Content>
            </RightPane>
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
          onSubmit={(body, verdict) => {
            onSubmitReview(body, verdict);
            setSubmitReviewOpen(false);
          }}
        />
      )}
    </Shell>
  );
}
