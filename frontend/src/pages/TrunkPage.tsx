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
import { AskUserQuestionCard, BacklogPrompt, pickTopBacklog, TriageCard } from '../ui/conv';
import { IconBtn, Pill } from '../ui/primitives';
import {
  Composer, Main, Shell, TopBar, TopBarTitle, CrumbSep, CreatedChip, Grow, usePaneWidth,
} from '../ui/shell';
import {
  BacklogFormModal, BacklogTabContent, InlineChips, NotificationsTabContent, RightPane, TasksTabContent,
} from '../ui/pane';
import type { NotifData, TaskCardData } from '../ui/pane';
import type { BacklogItemDto, ThreadSignalDto } from '../types';
import { useTrunkPane } from './useTrunkPane';

type TrunkTab = 'tasks' | 'backlog' | 'notifications';
/** Sub-tabs under Tasks: every task, the mergeable subset, the closed ones. */
type TaskSubTab = 'all' | 'mergeable' | 'closed';

function backlogToCard(item: BacklogItemDto, formatTime: (ms: number) => string) {
  return {
    id: item.id,
    title: item.title,
    body: item.body.length > 0 ? item.body : undefined,
    tags: item.tags.map(label => ({ label })),
    createdLabel: formatTime(item.createdAt),
    started: item.startedAt !== null,
    linkedTaskLabel: item.linkedTaskId !== null ? '→ Task' : undefined,
  };
}

function signalToNotif(s: ThreadSignalDto, formatTime: (ms: number) => string): NotifData {
  return {
    id: s.id,
    iconKind: s.iconKind,
    title: s.title,
    sub: s.body ?? undefined,
    timestamp: formatTime(s.createdAt),
    unread: s.readAt === null,
  };
}

/**
 * The thread trunk surface (frame 1): the 2-pane shell with the trunk
 * conversation in the centre and a right pane of Tasks / Backlog /
 * Notifications. The sidebar and conversation are passed in as slots
 * (assembled by the host from thread data); the Backlog + Notifications
 * tabs are wired here to the new per-thread APIs via {@link useTrunkPane}.
 */
export function TrunkPage({
  threadId, thread, sidebar, conversation, collapsed = false, composer,
  tasks, onOpenTask, formatTime = () => '',
}: {
  threadId: string;
  thread: { title: string; createdLabel?: string };
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
  };
  tasks: { active: TaskCardData[]; closed: TaskCardData[] };
  onOpenTask?: (id: string) => void;
  formatTime?: (ms: number) => string;
}) {
  const pane = useTrunkPane(threadId);
  const [activeTab, setActiveTab] = useState<TrunkTab>('tasks');
  const [addBacklogOpen, setAddBacklogOpen] = useState(false);
  // Triage-card candidates: the trunk's freshly-proposed (trunk-split, still
  // `created`) items, minus any the user has "kept" this session. Starting or
  // skipping one flips its status, so it falls out of this list on the next
  // poll; "Keep" just dismisses the card without a status change.
  const [keptTriage, setKeptTriage] = useState<Set<string>>(() => new Set());
  const triageItems = pane.backlog.filter(
    i => i.source === 'trunk-split' && i.status === 'created' && !keptTriage.has(i.id));
  // Open agent questions for this thread, oldest first, rendered as amber
  // cards in the conversation; answering one posts the reply as the next
  // message and the card drops out on the next poll.
  const openQuestions = pane.questions.filter(q => q.status === 'open');
  // Idle-trunk prompt: when nothing is running and the backlog still holds
  // unstarted items, offer to run the top one (highest priority, then oldest).
  // "Ignore" is a per-thread dismissal (localStorage) — once set, the backlog
  // waits for a manual Start and this prompt stays hidden.
  const ignoreKey = `bq.backlogPrompt.ignore.${threadId}`;
  const [promptIgnored, setPromptIgnored] = useState(() => {
    try { return typeof localStorage !== 'undefined' && localStorage.getItem(ignoreKey) === 'true'; }
    catch { return false; }
  });
  const ignorePrompt = () => {
    try { if (typeof localStorage !== 'undefined') localStorage.setItem(ignoreKey, 'true'); }
    catch { /* storage unavailable */ }
    setPromptIgnored(true);
  };
  const topBacklog = pickTopBacklog(pane.backlog, tasks.active.length > 0, promptIgnored);
  const [taskSub, setTaskSub] = useState<TaskSubTab>('all');
  const [paneOpen, setPaneOpen] = useState(true);
  // Trunk keeps its own pane width, independent of the brain/stage surfaces.
  const { paneWidth, bodyRef, onResize } = usePaneWidth('bq.trunkPaneWidth');

  const unreadCount = pane.signals.filter(s => s.readAt === null).length;
  // The Tasks tab's "All" sub-tab renders active cards + the Closed folder,
  // so the top badge counts every task.
  const taskCount = tasks.active.length + tasks.closed.length;
  // Tasks whose PR is ready to merge (CI green, no unresolved comments,
  // mergeable) — surfaced in the "Ready to merge" sub-tab + tinted in All.
  const mergeable = [...tasks.active, ...tasks.closed].filter(t => t.mergeReady === true);

  // Clicking a chip opens its tab; clicking the chip of the tab already shown
  // collapses the pane. The chip row stays pinned above the composer either
  // way, so it never disappears when the pane expands.
  const onChipClick = (tab: TrunkTab) => {
    if (paneOpen && activeTab === tab) {
      setPaneOpen(false);
      return;
    }
    setActiveTab(tab);
    setPaneOpen(true);
  };

  const topBar = (
    <TopBar>
      <Pill kind="thread" icon="💭">THREAD</Pill>
      <TopBarTitle>{thread.title}</TopBarTitle>
      {thread.createdLabel !== undefined && (
        <>
          <CrumbSep />
          <CreatedChip>{thread.createdLabel}</CreatedChip>
        </>
      )}
      <Grow />
      <IconBtn active={paneOpen} ariaLabel="Toggle right pane" onClick={() => setPaneOpen(o => !o)}>◧</IconBtn>
    </TopBar>
  );

  const tasksTabContent = (() => {
    switch (taskSub) {
      case 'all':
        // "All" lists every task flat — active then closed — with no Closed
        // folder; the dedicated Closed sub-tab is the place to fold them away.
        return (
          <TasksTabContent
            active={[...tasks.active, ...tasks.closed]}
            onOpenTask={onOpenTask}
          />
        );
      case 'mergeable':
        return mergeable.length === 0
          ? <div className="pane-empty-note">No tasks are ready to merge right now.</div>
          : <TasksTabContent active={mergeable} onOpenTask={onOpenTask} />;
      case 'closed':
        return tasks.closed.length === 0
          ? <div className="pane-empty-note">No closed tasks yet.</div>
          : <TasksTabContent active={tasks.closed} onOpenTask={onOpenTask} />;
    }
  })();

  const tabContent = (() => {
    switch (activeTab) {
      case 'tasks':
        return tasksTabContent;
      case 'backlog':
        return (
          <BacklogTabContent
            items={pane.backlog.map(i => backlogToCard(i, formatTime))}
            onAddItem={() => setAddBacklogOpen(true)}
            onStartDevelopment={id => { void pane.startDevelopment(id); }}
            onDrop={id => { void pane.skip(id); }}
          />
        );
      case 'notifications':
        return (
          <NotificationsTabContent
            notifications={pane.signals.map(s => signalToNotif(s, formatTime))}
            onOpen={id => { void pane.markSignalRead(id); }}
          />
        );
    }
  })();

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
            {openQuestions.length > 0 && (
              <div className="trunk-questions">
                {openQuestions.map((q, i) => (
                  <AskUserQuestionCard
                    key={q.id}
                    question={q.question}
                    context={q.context}
                    options={q.options}
                    allowFreeForm={q.allowFreeForm}
                    index={i + 1}
                    total={openQuestions.length}
                    onAnswer={(optionId, freeForm) => {
                      void pane.answerQuestion(q.id, optionId, freeForm);
                    }}
                  />
                ))}
              </div>
            )}
            {triageItems.length > 0 && (
              <div className="trunk-triage">
                <div className="trunk-triage__head">Proposed by the trunk — triage these</div>
                {triageItems.map(item => (
                  <TriageCard
                    key={item.id}
                    title={item.title}
                    body={item.body}
                    tags={item.tags}
                    onStartDev={() => { void pane.startDevelopment(item.id); }}
                    onKeep={() => setKeptTriage(prev => new Set(prev).add(item.id))}
                    onSkip={() => { void pane.skip(item.id); }}
                  />
                ))}
              </div>
            )}
            {topBacklog !== undefined && (
              <BacklogPrompt
                title={topBacklog.title}
                body={topBacklog.body}
                tags={topBacklog.tags}
                onApprove={() => { void pane.startDevelopment(topBacklog.id); }}
                onIgnore={ignorePrompt}
                onDrop={() => { void pane.skip(topBacklog.id); }}
              />
            )}
            <InlineChips chips={[
              { icon: '◳', label: 'Tasks', count: taskCount, countColor: 'acc', active: paneOpen && activeTab === 'tasks', onClick: () => onChipClick('tasks') },
              { icon: '☷', label: 'Backlog', count: pane.backlog.length, active: paneOpen && activeTab === 'backlog', onClick: () => onChipClick('backlog') },
              { icon: '🔔', label: 'Notifications', count: unreadCount, countColor: 'red', active: paneOpen && activeTab === 'notifications', onClick: () => onChipClick('notifications') },
            ]}
            />
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
              {/* Top-level switcher inside the pane: the pinned composer chips
                  double as a collapsed-pane entry, but when the pane is open the
                  user needs to reach Backlog / Notifications from the pane itself
                  rather than reaching back up to the composer. */}
              <RightPane.Tabs<TrunkTab>
                tabs={[
                  { key: 'tasks', label: 'Tasks', count: taskCount, countColor: 'acc' },
                  { key: 'backlog', label: 'Backlog', count: pane.backlog.length },
                  { key: 'notifications', label: 'Notifications', count: unreadCount, countColor: 'red' },
                ]}
                active={activeTab}
                onSelect={setActiveTab}
              />
              {activeTab === 'tasks' && (
                <div className="pane-subtabs">
                  <RightPane.Tabs<TaskSubTab>
                    tabs={[
                      { key: 'all', label: 'All', count: taskCount, countColor: 'acc' },
                      { key: 'mergeable', label: 'Ready to merge', count: mergeable.length, countColor: 'acc' },
                      { key: 'closed', label: 'Closed', count: tasks.closed.length, countColor: 'muted' },
                    ]}
                    active={taskSub}
                    onSelect={setTaskSub}
                  />
                </div>
              )}
              <RightPane.Content>{tabContent}</RightPane.Content>
            </RightPane>
          )}
        </div>
      </Main>
      {addBacklogOpen && (
        <BacklogFormModal
          onSave={item => {
            void pane.createItem(item.title, item.body, item.tags, item.priority);
            setAddBacklogOpen(false);
          }}
          onClose={() => setAddBacklogOpen(false)}
        />
      )}
    </Shell>
  );
}
