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
import { IconBtn, Pill } from '../ui/primitives';
import { CheckIcon, PanelIcon } from '../ui/TaskBrainDesignIcons';
import {
  Composer, CtxChip, Grow, Main, RunMenu, Shell, TopBar, TopBarButton, TopBarTitle,
  usePaneWidth,
} from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import { PlanReminderTab } from './PlanOverlay';
import { SubmitReviewDrawer } from './SubmitReviewDrawer';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import type { DiffInlineComment } from '../diff/DiffInlineComments';

/** Work-stage variants that share this page, including legacy run containers. */
export type StageKind = 'plan' | 'dev' | 'remote-dev' | 'ci-fix' | 'comments' | 'cleanup';
type StageTab = 'pr' | 'ci';

const PILL_LABEL: Record<StageKind, string> = {
  plan: 'PLAN',
  dev: 'DEV',
  'remote-dev': 'REMOTE DEV',
  'ci-fix': 'CI FIX',
  comments: 'COMMENTS',
  cleanup: 'CLEANUP',
};

/**
 * The generic work-stage surface — Local Development, Remote Development,
 * legacy run containers, and Cleanup all compose this one page. They differ
 * only in the stage pill, the default right-pane tab, the CI-Status entry
 * (legacy CI Fix only), and the conversation + composer agent the host
 * supplies. The composer mode pill defaults to the stage's agent runtime.
 */
export function StageDetailPage({
  stageKind, stage, sidebar, conversation, collapsed = false, composer, run = {},
  tabs, tabCounts, paneMeta, onOpenCi, planReminder, onRevealPlan,
  onSubmitReview, submittingReview = false, openTabRequest,
  pendingReviewComments = [], onRemovePendingReviewComment, conversationIndex,
}: {
  stageKind: StageKind;
  stage: { title: string; branch?: string; pillLabel?: string };
  sidebar?: ReactNode;
  conversation: ReactNode;
  conversationIndex?: ReactNode;
  collapsed?: boolean;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    queueWhenBusy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
    /** Pending pasted-screenshot data URLs — controlled, like `value`. Omit
     *  (with `onImagesChange`) to disable image paste on this composer. */
    images?: string[];
    onImagesChange?: (next: string[]) => void;
    closedNote?: string;
  };
  run?: {
    statusLabel?: string;
    paused?: boolean;
    terminal?: boolean;
    onPause?: () => void;
    onResume?: () => void;
    onClose?: () => void;
  };
  tabs: { pr?: ReactNode; ci?: ReactNode; code?: ReactNode };
  /** Optional per-tab count badge (e.g. changed-file count, PR number). */
  tabCounts?: Partial<Record<StageTab, { count?: number; countColor?: 'red' | 'acc' | 'muted' }>>;
  /** Sub-header under the tab strip, shown on the CI tab. */
  paneMeta?: { left?: ReactNode; right?: ReactNode };
  onOpenCi?: () => void;
  /** Shows the plan reminder pill above the composer (same as the brain view)
   *  so the plan is one click away from any stage. Undefined hides it. */
  planReminder?: 'awaiting' | 'locked';
  /** Click handler for the reminder pill — opens the zoomed plan overlay. */
  onRevealPlan?: () => void;
  /** Shows a green, glowing reminder pill above the composer while a shipped
   *  draft's CI is green and the mark-ready gate is parked — clicking it
   *  opens the Changes tab. */
  markReadyReminder?: boolean;
  /** Publishes the reviewer's comment + verdict (from the Submit-review
   *  drawer), plus unresolved comments on this stage's diff, to GitHub. */
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  /** Draft comments the drawer lists so the reviewer sees exactly what a
   *  submission will send. Omit where no draft-comment source is wired up. */
  pendingReviewComments?: DiffInlineComment[];
  onRemovePendingReviewComment?: (commentId: string) => void;
  /** Force-opens a tab from outside (the live-plan rail's gate nodes) — a
   *  fresh object (new `token`) re-fires even for a repeat click on the tab
   *  that's already open. */
  openTabRequest?: { tab: StageTab; token: number };
}) {
  // PR leads and opens first. Changes is no longer a pane-level tab; it lives
  // inside the PR view's own sub-tabs so the task page has one PR pane.
  const available: { key: StageTab; label: string; node: ReactNode }[] = [
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
    ...(tabs.ci !== undefined ? [{ key: 'ci' as const, label: 'CI', node: tabs.ci }] : []),
  ];
  // A stage without a PR/diff/CI yet (e.g. Plan, before Dev opens a PR) has
  // nothing to show in the side pane at all.
  const hasTabs = available.length > 0;
  const [activeTab, setActiveTab] = useState<StageTab | undefined>(available[0]?.key);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth('bq.stagePaneWidth.v2', 428);
  const [submitReviewOpen, setSubmitReviewOpen] = useState(false);

  useEffect(() => {
    if (openTabRequest === undefined) return;
    setActiveTab(openTabRequest.tab);
    setPaneOpen(true);
  }, [openTabRequest]);

  const active = available.find(t => t.key === activeTab) ?? available[available.length - 1];
  // CI Fix is the one stage that surfaces the CI Status full-page view.
  const showCi = stageKind === 'ci-fix' && onOpenCi !== undefined;

  // The inline pill toggles the pane: closed → open and jump to the tab;
  // open on another tab → jump; open on this tab → close the pane.
  const openTab = (key: StageTab) => {
    if (paneOpen && active?.key === key) { setPaneOpen(false); return; }
    setActiveTab(key);
    setPaneOpen(true);
  };
  const topBar = (
    <TopBar>
      <Pill kind="stage">{stage.pillLabel ?? PILL_LABEL[stageKind]}</Pill>
      <TopBarTitle>{stage.title}</TopBarTitle>
      {stage.branch !== undefined && <CtxChip>{stage.branch}</CtxChip>}
      <Grow />
      {run.statusLabel !== undefined && (
        <span className={run.terminal === true ? 'task-brain__status task-brain__status--terminal' : 'task-brain__status'}>
          {run.statusLabel}
        </span>
      )}
      <RunMenu
        hideStatus
        statusLabel={run.statusLabel}
        paused={run.paused}
        terminal={run.terminal}
        onPause={run.onPause}
        onResume={run.onResume}
        onClose={run.onClose}
      />
      {showCi && <TopBarButton icon={<CheckIcon size={14} strokeWidth={2.2} />} onClick={onOpenCi}>CI Status</TopBarButton>}
      {onSubmitReview !== undefined && (
        <TopBarButton
          variant="submit"
          icon={<CheckIcon size={14} strokeWidth={2.2} />}
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
        <IconBtn active={paneOpen} ariaLabel="Toggle right pane" onClick={() => setPaneOpen(o => !o)}>
          <PanelIcon />
        </IconBtn>
      )}
    </TopBar>
  );

  const showPane = paneOpen && hasTabs && active !== undefined;

  return (
    <div className="task-brain">
      <Shell
        collapsed={collapsed}
        fullWidth={sidebar === undefined}
        sidebarWidthKey="bq.taskBrainSidebarWidth.v2"
        sidebarWidthDefault={270}
      >
        {sidebar}
        <Main topBar={topBar}>
        <div
          ref={bodyRef}
          className={showPane ? 'body with-pane' : 'body'}
          style={showPane ? { gridTemplateColumns: `minmax(0, 1fr) 5px ${paneWidth}px` } : undefined}
        >
          <div className="conv-col">
            <div className="conv-index-host">
              {conversation}
              {conversationIndex}
            </div>
            {/* Quick-access chips float just above the composer at all times
                (not only when the pane is closed). The plan reminder pill sits
                on the left of the same row; the tab chips align to the right. */}
            <div className="chip-reminder-row">
              {planReminder !== undefined && onRevealPlan !== undefined && (
                <PlanReminderTab state={planReminder} onClick={onRevealPlan} />
              )}
              <InlineChips chips={[
                ...available.map(t => ({ label: t.label, onClick: () => openTab(t.key) })),
                ...(showCi ? [{ icon: '✓', label: 'CI Status', onClick: onOpenCi }] : []),
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
              closedNote={composer.closedNote}
            />
          </div>
          {showPane && (
            <ResizeHandle onResize={onResize} className="pane-resize" ariaLabel="Resize the side pane" />
          )}
          {showPane && (
            <RightPane>
              {available.length > 1 && (
                <RightPane.Tabs<StageTab>
                  tabs={available.map(t => ({
                    key: t.key,
                    label: t.label,
                    count: tabCounts?.[t.key]?.count,
                    countColor: tabCounts?.[t.key]?.countColor,
                  }))}
                  active={active.key}
                  onSelect={setActiveTab}
                />
              )}
              {paneMeta !== undefined && active.key === 'ci' && (
                <RightPane.MetaRow left={paneMeta.left} right={paneMeta.right} />
              )}
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
