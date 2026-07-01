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
import ResizeHandle from '../ResizeHandle';
import { IconBtn, Pill } from '../ui/primitives';
import {
  Composer, CtxChip, Grow, Main, RunMenu, Shell, StageChips, TopBar, TopBarButton, TopBarTitle,
  usePaneWidth,
} from '../ui/shell';
import type { StageChip } from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import type { PaneTab } from '../ui/pane';
import { PlanReminderTab } from './PlanOverlay';

/** The four work stages that share this page. */
export type StageKind = 'plan' | 'dev' | 'ci-fix' | 'comments' | 'cleanup';
type StageTab = 'changes' | 'pr' | 'files' | 'details';

const PILL_LABEL: Record<StageKind, string> = {
  plan: 'PLAN',
  dev: 'DEV',
  'ci-fix': 'CI FIX',
  comments: 'COMMENTS',
  cleanup: 'CLEANUP',
};

/** Which tab opens first for a given stage. Cleanup leads with Details;
 *  Comments leads with the PR comment threads; the rest lead with Changes.
 *  Falls back to Details when the preferred tab is absent. */
const PREFERRED_TAB: Record<StageKind, StageTab> = {
  plan: 'changes',
  dev: 'changes',
  'ci-fix': 'changes',
  comments: 'pr',
  cleanup: 'details',
};

/**
 * The generic work-stage surface — Dev / CI Fix / Comments / Cleanup all
 * compose this one page (frames 3, 6, 7). They differ only in the stage
 * pill, the default right-pane tab, the CI-Status entry (CI Fix only), and
 * the conversation + composer agent the host supplies. The composer mode
 * pill defaults to the stage's agent runtime (passed in as `modePill`).
 */
export function StageDetailPage({
  stageKind, stage, sidebar, conversation, collapsed = false, stageChips, composer, run = {},
  tabs, tabCounts, paneMeta, onOpenChanges, onOpenCi, planReminder, onRevealPlan,
}: {
  stageKind: StageKind;
  stage: { title: string; branch?: string; pillLabel?: string };
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
  tabs: { changes?: ReactNode; pr?: ReactNode; files?: ReactNode; details: ReactNode };
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
}) {
  const available: { key: StageTab; label: string; node: ReactNode }[] = [
    ...(tabs.changes !== undefined
      ? [{ key: 'changes' as const, label: stageKind === 'ci-fix' ? 'CI' : 'Changes', node: tabs.changes }]
      : []),
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
    ...(tabs.files !== undefined ? [{ key: 'files' as const, label: 'Files', node: tabs.files }] : []),
    { key: 'details' as const, label: 'Details', node: tabs.details },
  ];
  const preferred = PREFERRED_TAB[stageKind];
  const initial = available.find(t => t.key === preferred)?.key ?? available[available.length - 1].key;
  const [activeTab, setActiveTab] = useState<StageTab>(initial);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();

  const active = available.find(t => t.key === activeTab) ?? available[available.length - 1];
  const paneTabs: PaneTab<StageTab>[] = available.map(t => ({
    key: t.key,
    label: t.label,
    count: tabCounts?.[t.key]?.count,
    countColor: tabCounts?.[t.key]?.countColor,
  }));
  // CI Fix is the one stage that surfaces the CI Status full-page view.
  const showCi = stageKind === 'ci-fix' && onOpenCi !== undefined;

  const openTab = (key: StageTab) => { setActiveTab(key); setPaneOpen(true); };

  const topBar = (
    <TopBar>
      <Pill kind="stage">{stage.pillLabel ?? PILL_LABEL[stageKind]}</Pill>
      <TopBarTitle>{stage.title}</TopBarTitle>
      {stage.branch !== undefined && <CtxChip>{stage.branch}</CtxChip>}
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
            {/* Quick-access chips float just above the composer at all times
                (not only when the pane is closed) so Plan / Changes stay one
                click away from where you're typing. The plan reminder pill sits
                on the left of the same row; the tab chips align to the right. */}
            <div className="chip-reminder-row">
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
          {paneOpen && <ResizeHandle onResize={onResize} ariaLabel="Resize the side pane" />}
          {paneOpen && (
            <RightPane>
              <RightPane.Tabs<StageTab> tabs={paneTabs} active={active.key} onSelect={setActiveTab} />
              {paneMeta !== undefined && active.key === 'changes' && (
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
