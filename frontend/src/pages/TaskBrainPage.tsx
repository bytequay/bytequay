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
  Composer, CtxChip, Grow, Main, RunMenu, Shell, StageChips, TopBar, TopBarButton, TopBarTitle,
  usePaneWidth,
} from '../ui/shell';
import type { StageChip } from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import type { PaneTab } from '../ui/pane';
import { MarkReadyReminderTab, PlanReminderTab } from './PlanOverlay';

type BrainTab = 'pr';

/**
 * The task brain surface (frame 2): the 2-pane shell with the brain
 * conversation in the centre, a stage-chip strip + lifecycle Run menu in
 * the top bar, and a right pane of Plan / PR. The sidebar and
 * conversation are slots; the tab contents are composed by the host from
 * the Phase-4 tab components. The model selector lives in the composer's
 * mode pill (the relocated WORK MODEL card); lifecycle controls live in
 * the Run menu.
 */
export function TaskBrainPage({
  task, pr, sidebar, conversation, collapsed = false, stageChips, composer, run = {},
  tabs, planReminder, onRevealPlan, markReadyReminder, onOpenChanges, onOpenCi, autoApprove, onToggleAutoApprove,
}: {
  task: { pillLabel: string; title: string; branch?: string; finished?: boolean };
  /** The linked pull request, shown as a clickable chip once the task is
   *  shipped — clicking opens the PR on GitHub. */
  pr?: { number: number; status: string; onOpen: () => void };
  sidebar?: ReactNode;
  conversation: ReactNode;
  collapsed?: boolean;
  stageChips?: StageChip[];
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
  /** Tab contents; PR is omitted when not applicable. */
  tabs: { pr?: ReactNode };
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
   *  jumps to the Changes page via {@code onOpenChanges}. */
  markReadyReminder?: boolean;
  onOpenChanges?: () => void;
  onOpenCi?: () => void;
  /** Auto-approve mode: when on, the task's parked gates + tool prompts are
   *  approved automatically (the final PR merge stays manual). */
  autoApprove?: boolean;
  onToggleAutoApprove?: () => void;
}) {
  const available: { key: BrainTab; label: string; node: ReactNode }[] = [
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
  ];
  // No PR yet (task hasn't opened one) means nothing to show in the pane.
  const hasTabs = available.length > 0;
  const [activeTab, setActiveTab] = useState<BrainTab | undefined>(available[0]?.key);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();

  const active = available.find(t => t.key === activeTab) ?? available[available.length - 1];
  const paneTabs: PaneTab<BrainTab>[] = available.map(t => ({ key: t.key, label: t.label }));

  // The inline pill toggles the pane: closed → open and jump to the tab;
  // open on another tab → jump; open on this tab → close the pane.
  const openTab = (key: BrainTab) => {
    if (paneOpen && active?.key === key) { setPaneOpen(false); return; }
    setActiveTab(key);
    setPaneOpen(true);
  };

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
      {stageChips !== undefined && stageChips.length > 0 && <StageChips chips={stageChips} />}
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
      {onToggleAutoApprove !== undefined && (
        <TopBarButton
          icon={autoApprove === true ? '⚡' : '○'}
          variant={autoApprove === true ? 'submit' : 'default'}
          onClick={onToggleAutoApprove}
          title="Auto-approve this task's gates and tool prompts — except the final PR merge, which always asks"
        >
          Auto-approve {autoApprove === true ? 'on' : 'off'}
        </TopBarButton>
      )}
      {onOpenChanges !== undefined && (
        <TopBarButton icon="▢" onClick={onOpenChanges}>Changes</TopBarButton>
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
              {markReadyReminder === true && onOpenChanges !== undefined && (
                <MarkReadyReminderTab onClick={onOpenChanges} />
              )}
              {planReminder !== undefined && (
                <PlanReminderTab state={planReminder} onClick={onRevealPlan ?? revealPlan} />
              )}
              <InlineChips chips={[
                ...available.map(t => ({ label: t.label, onClick: () => openTab(t.key) })),
                ...(onOpenChanges !== undefined ? [{ icon: '◳', label: 'Changes', onClick: onOpenChanges }] : []),
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
            />
          </div>
          {showPane && <ResizeHandle onResize={onResize} className="pane-resize" ariaLabel="Resize the side pane" />}
          {showPane && (
            <RightPane>
              <RightPane.Tabs<BrainTab> tabs={paneTabs} active={active.key} onSelect={setActiveTab} />
              <RightPane.Content>{active.node}</RightPane.Content>
            </RightPane>
          )}
        </div>
      </Main>
    </Shell>
  );
}
