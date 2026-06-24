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
import { IconBtn, Pill } from '../ui/primitives';
import {
  Composer, CtxChip, Grow, Main, RunMenu, Shell, StageChips, TopBar, TopBarButton, TopBarTitle,
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
  task, sidebar, conversation, collapsed = false, stageChips, composer, run = {},
  tabs, priorityTab, onOpenChanges, onOpenCi,
}: {
  task: { pillLabel: string; title: string; branch?: string };
  sidebar?: ReactNode;
  conversation: ReactNode;
  collapsed?: boolean;
  stageChips?: StageChip[];
  composer: {
    value: string;
    onChange: (next: string) => void;
    onSubmit: () => void;
    busy?: boolean;
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
  onOpenChanges?: () => void;
  onOpenCi?: () => void;
}) {
  const available: { key: BrainTab; label: string; node: ReactNode }[] = [
    ...(tabs.plan !== undefined ? [{ key: 'plan' as const, label: 'Plan', node: tabs.plan }] : []),
    ...(tabs.pr !== undefined ? [{ key: 'pr' as const, label: 'PR', node: tabs.pr }] : []),
    { key: 'details' as const, label: 'Details', node: tabs.details },
  ];
  const [activeTab, setActiveTab] = useState<BrainTab>(available[0].key);
  const [paneOpen, setPaneOpen] = useState(true);

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

  const topBar = (
    <TopBar>
      <Pill kind="task" icon="▣">{task.pillLabel}</Pill>
      <TopBarTitle>{task.title}</TopBarTitle>
      {task.branch !== undefined && <CtxChip>{task.branch}</CtxChip>}
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
        <div className={paneOpen ? 'body with-pane' : 'body'}>
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
            <Composer
              value={composer.value}
              onChange={composer.onChange}
              onSubmit={composer.onSubmit}
              busy={composer.busy}
              modePill={composer.modePill}
              placeholder={composer.placeholder}
            />
          </div>
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
