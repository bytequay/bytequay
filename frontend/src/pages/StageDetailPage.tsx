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
import {
  Composer, CtxChip, Grow, Main, RunMenu, Shell, StageChips, TopBar, TopBarButton, TopBarTitle,
  usePaneWidth,
} from '../ui/shell';
import type { StageChip } from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import type { PaneTab } from '../ui/pane';
import { MarkReadyReminderTab, PlanReminderTab } from './PlanOverlay';

/** The four work stages that share this page. */
export type StageKind = 'plan' | 'dev' | 'ci-fix' | 'comments' | 'cleanup';
type StageTab = 'pr' | 'changes' | 'ci';

const PILL_LABEL: Record<StageKind, string> = {
  plan: 'PLAN',
  dev: 'DEV',
  'ci-fix': 'CI FIX',
  comments: 'COMMENTS',
  cleanup: 'CLEANUP',
};

/**
 * The generic work-stage surface — Dev / CI Fix / Comments / Cleanup all
 * compose this one page (frames 3, 6, 7). They differ only in the stage
 * pill, the default right-pane tab, the CI-Status entry (CI Fix only), and
 * the conversation + composer agent the host supplies. The composer mode
 * pill defaults to the stage's agent runtime (passed in as `modePill`).
 */
export function StageDetailPage({
  stageKind, stage, sidebar, conversation, collapsed = false, stageChips, workModelPill, composer, run = {},
  tabs, tabCounts, paneMeta, onOpenChanges, onOpenCi, planReminder, onRevealPlan, markReadyReminder,
  openTabRequest,
}: {
  stageKind: StageKind;
  stage: { title: string; branch?: string; pillLabel?: string };
  sidebar?: ReactNode;
  conversation: ReactNode;
  collapsed?: boolean;
  stageChips?: StageChip[];
  /** The stage's work-model pill (agent/provider · model · CLI/API),
   *  rendered next to the branch chip — same placement as the thread
   *  trunk header's pill. Undefined hides it (e.g. no stage id yet). */
  workModelPill?: ReactNode;
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
    queueWhenBusy?: boolean;
    modePill?: ReactNode;
    placeholder?: string;
  };
  run?: {
    statusLabel?: string;
    paused?: boolean;
    terminal?: boolean;
    onPause?: () => void;
    onResume?: () => void;
    onClose?: () => void;
  };
  tabs: { pr?: ReactNode; changes?: ReactNode; ci?: ReactNode };
  /** Optional per-tab count badge (e.g. changed-file count, PR number). */
  tabCounts?: Partial<Record<StageTab, { count?: number; countColor?: 'red' | 'acc' | 'muted' }>>;
  /** Sub-header under the tab strip, shown on the Changes tab (frame 6). */
  paneMeta?: { left?: ReactNode; right?: ReactNode };
  onOpenChanges?: () => void;
  onOpenCi?: () => void;
  /** Shows the plan reminder pill above the composer (same as the brain view)
   *  so the plan is one click away from any stage. Undefined hides it. */
  planReminder?: 'awaiting' | 'locked';
  /** Click handler for the reminder pill — opens the zoomed plan overlay. */
  onRevealPlan?: () => void;
  /** Shows a green, glowing reminder pill above the composer while a shipped
   *  draft's CI is green and the mark-ready gate is parked — clicking it
   *  jumps to the Changes page via {@code onOpenChanges}. */
  markReadyReminder?: boolean;
  /** Force-opens a tab from outside (the live-plan rail's gate nodes) — a
   *  fresh object (new `token`) re-fires even for a repeat click on the tab
   *  that's already open. */
  openTabRequest?: { tab: StageTab; token: number };
}) {
  // PR leads the strip and opens first (decision #48) — it's the primary
  // artifact. The Code Diff tab renders the in-pane diff on every work stage;
  // the CI Fix stage adds its own CI tab for the live run. Stages without a
  // PR tab (Plan, or a task with no PR yet) fall back to the first present.
  const available: { key: StageTab; label: string; node: ReactNode }[] = [
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
    ...(tabs.changes !== undefined ? [{ key: 'changes' as const, label: 'Code Diff', node: tabs.changes }] : []),
    ...(tabs.ci !== undefined ? [{ key: 'ci' as const, label: 'CI', node: tabs.ci }] : []),
  ];
  // A stage without a PR/diff/CI yet (e.g. Plan, before Dev opens a PR) has
  // nothing to show in the side pane at all.
  const hasTabs = available.length > 0;
  const [activeTab, setActiveTab] = useState<StageTab | undefined>(available[0]?.key);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();

  useEffect(() => {
    if (openTabRequest === undefined) return;
    setActiveTab(openTabRequest.tab);
    setPaneOpen(true);
  }, [openTabRequest]);

  const active = available.find(t => t.key === activeTab) ?? available[available.length - 1];
  const paneTabs: PaneTab<StageTab>[] = available.map(t => ({
    key: t.key,
    label: t.label,
    count: tabCounts?.[t.key]?.count,
    countColor: tabCounts?.[t.key]?.countColor,
  }));
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
      {workModelPill}
      {stageChips !== undefined && stageChips.length > 0 && <StageChips chips={stageChips} />}
      <Grow />
      <RunMenu
        statusLabel={run.statusLabel}
        paused={run.paused}
        terminal={run.terminal}
        onPause={run.onPause}
        onResume={run.onResume}
        onClose={run.onClose}
      />
      {showCi && <TopBarButton icon="✓" onClick={onOpenCi}>CI Status</TopBarButton>}
      {onOpenChanges !== undefined && <TopBarButton icon="▢" onClick={onOpenChanges}>Changes</TopBarButton>}
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
            {/* Quick-access chips float just above the composer at all times
                (not only when the pane is closed) so Plan / Changes stay one
                click away from where you're typing. The plan reminder pill sits
                on the left of the same row; the tab chips align to the right. */}
            <div className="chip-reminder-row">
              {markReadyReminder === true && onOpenChanges !== undefined && (
                <MarkReadyReminderTab onClick={onOpenChanges} />
              )}
              {planReminder !== undefined && onRevealPlan !== undefined && (
                <PlanReminderTab state={planReminder} onClick={onRevealPlan} />
              )}
              <InlineChips chips={[
                ...available.map(t => ({ label: t.label, onClick: () => openTab(t.key) })),
                ...(onOpenChanges !== undefined ? [{ icon: '◳', label: 'Changes', onClick: onOpenChanges }] : []),
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
            />
          </div>
          {showPane && <ResizeHandle onResize={onResize} className="pane-resize" ariaLabel="Resize the side pane" />}
          {showPane && (
            <RightPane>
              <RightPane.Tabs<StageTab> tabs={paneTabs} active={active.key} onSelect={setActiveTab} />
              {paneMeta !== undefined && (active.key === 'changes' || active.key === 'ci') && (
                <RightPane.MetaRow left={paneMeta.left} right={paneMeta.right} />
              )}
              <RightPane.Content>{active.node}</RightPane.Content>
            </RightPane>
          )}
        </div>
      </Main>
    </Shell>
  );
}
