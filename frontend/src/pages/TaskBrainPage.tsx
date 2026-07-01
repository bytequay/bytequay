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

type BrainTab = 'plan' | 'pr' | 'details';

/**
 * The task brain surface (frame 2): the 2-pane shell with the brain
 * conversation in the centre, a stage-chip strip + lifecycle Run menu in
 * the top bar, and a right pane of Plan / PR / Details. The sidebar and
 * conversation are slots; the tab contents are composed by the host from
 * the Phase-4 tab components. The model selector lives in the composer's
 * mode pill (the relocated WORK MODEL card); task metrics live in the
 * Details tab; lifecycle controls live in the Run menu.
 */
export function TaskBrainPage({
  task, pr, sidebar, conversation, collapsed = false, stageChips, composer, run = {},
  tabs, priorityTab, planReminder, onOpenChanges, onOpenCi, autoApprove, onToggleAutoApprove,
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
  /** Tab contents; Plan/PR are omitted when not applicable, Details is
   *  always shown. */
  tabs: { plan?: ReactNode; pr?: ReactNode; details: ReactNode };
  /** When set, the pane snaps to this tab (e.g. 'plan' when a plan is
   *  awaiting the user's approval) so it can't hide behind the default. */
  priorityTab?: BrainTab;
  /** Shows a reminder tab above the composer while a plan needs attention:
   *  'awaiting' → the plan is unreviewed (orange, animated flowing border);
   *  'locked' → the plan is finalized (purple, static). Undefined hides it. */
  planReminder?: 'awaiting' | 'locked';
  onOpenChanges?: () => void;
  onOpenCi?: () => void;
  /** Auto-approve mode: when on, the task's parked gates + tool prompts are
   *  approved automatically (the final PR merge stays manual). */
  autoApprove?: boolean;
  onToggleAutoApprove?: () => void;
}) {
  const available: { key: BrainTab; label: string; node: ReactNode }[] = [
    ...(tabs.plan !== undefined ? [{ key: 'plan' as const, label: 'Plan', node: tabs.plan }] : []),
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
    { key: 'details' as const, label: 'Details', node: tabs.details },
  ];
  const [activeTab, setActiveTab] = useState<BrainTab>(available[0].key);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();

  // Snap to (and reveal) the priority tab when it appears — e.g. a plan
  // that just finished and now awaits approval shouldn't sit hidden
  // behind whatever tab was the default at mount.
  useEffect(() => {
    if (priorityTab !== undefined) {
      setActiveTab(priorityTab);
      setPaneOpen(true);
    }
  }, [priorityTab]);

  const active = available.find(t => t.key === activeTab) ?? available[available.length - 1];
  const paneTabs: PaneTab<BrainTab>[] = available.map(t => ({ key: t.key, label: t.label }));

  const openTab = (key: BrainTab) => { setActiveTab(key); setPaneOpen(true); };

  // Reveal the plan when the reminder tab is clicked: scroll to the inline
  // plan card if it's shown in the conversation (planning live), otherwise
  // open the right-pane Plan tab (once locked the plan lives there).
  const revealPlan = () => {
    const inline = document.querySelector('.conv-col .plan-card');
    if (inline !== null) {
      inline.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    else if (available.some(t => t.key === 'plan')) {
      openTab('plan');
    }
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
      <IconBtn active={paneOpen} ariaLabel="Toggle right pane" onClick={() => setPaneOpen(o => !o)}>◧</IconBtn>
    </TopBar>
  );

  return (
    <Shell collapsed={collapsed} fullWidth={sidebar === undefined}>
      {sidebar}
      <Main topBar={topBar}>
        <div
          ref={bodyRef}
          className={paneOpen ? 'body with-pane' : 'body'}
          style={paneOpen ? { gridTemplateColumns: `minmax(0, 1fr) 5px ${paneWidth}px` } : undefined}
        >
          <div className="conv-col">
            {conversation}
            {!paneOpen && (
              <InlineChips chips={[
                ...available.map(t => ({ label: t.label, onClick: () => openTab(t.key) })),
                ...(onOpenChanges !== undefined ? [{ icon: '◳', label: 'Changes', onClick: onOpenChanges }] : []),
                ...(onOpenCi !== undefined ? [{ icon: '✓', label: 'CI Status', onClick: onOpenCi }] : []),
              ]}
              />
            )}
            {planReminder !== undefined && (
              <PlanReminderTab state={planReminder} onClick={revealPlan} />
            )}
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
          {paneOpen && <ResizeHandle onResize={onResize} ariaLabel="Resize the side pane" />}
          {paneOpen && (
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

/**
 * Reminder pill above the composer that keeps a plan needing attention in
 * the user's eyeline. While the plan is unreviewed it glows orange with a
 * light flowing around its border; once finalized it goes solid purple and
 * still. Clicking it jumps to the plan.
 */
function PlanReminderTab({ state, onClick }: { state: 'awaiting' | 'locked'; onClick: () => void }) {
  const awaiting = state === 'awaiting';
  return (
    <button
      type="button"
      className={`plan-reminder plan-reminder--${state}`}
      onClick={onClick}
      title={awaiting ? 'Plan awaiting your review — click to view' : 'Plan finalized — click to view'}
    >
      <span className="plan-reminder__ic" aria-hidden>{awaiting ? '✦' : '✓'}</span>
      <span className="plan-reminder__t">
        {awaiting ? 'Plan awaiting your review' : 'Plan finalized'}
      </span>
    </button>
  );
}
