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
import { AskUserQuestionCard, BacklogPrompt, pickTopBacklog } from '../ui/conv';
import { IconBtn } from '../ui/primitives';
import {
  Composer, Main, Shell, TopBar, TopBarTitle, Grow, usePaneWidth,
} from '../ui/shell';
import { InlineChips, RightPane } from '../ui/pane';
import type { TaskCardData } from '../ui/pane';
import { workspaceApi, type TrunkActivityItemDto } from '../workspace/workspaceApi';
import { useTrunkPane } from './useTrunkPane';

const READY_BACKLOG_STATUSES = new Set(['open', 'created']);

/**
 * The long-lived conversation stays untouched in the centre. Its former
 * Tasks / Backlog / Notifications tabs are intentionally replaced by one
 * server-owned activity projection, with unresolved questions and publish
 * gates pinned above the chronological feed.
 */
export function TrunkPage({
  threadId, thread, sidebar, conversation, conversationIndex, collapsed = false, composer,
  tasks, onOpenTask, formatTime = defaultActivityTime, conversationFooter,
  hideConversationPrompts = false,
}: {
  threadId: string;
  thread: {
    title: string;
    createdLabel?: string;
    status?: string;
    branch?: string | null;
  };
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
    images?: string[];
    onImagesChange?: (next: string[]) => void;
  };
  tasks: { active: TaskCardData[]; closed: TaskCardData[] };
  onOpenTask?: (id: string) => void;
  formatTime?: (ms: number) => string;
  /** Fixture/read-only surfaces can keep the production shell and activity
   *  projection while supplying their own non-interactive conversation
   *  footer. The live trunk route leaves this unset and retains Composer. */
  conversationFooter?: ReactNode;
  hideConversationPrompts?: boolean;
}) {
  const pane = useTrunkPane(threadId);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth('bq.trunkPaneWidth', 330);

  const openQuestions = pane.questions.filter(question => question.status === 'open');
  const readyBacklog = pane.backlog.filter(item => READY_BACKLOG_STATUSES.has(item.status));
  const ignoreKey = `bq.backlogPrompt.ignore.${threadId}`;
  const [promptIgnored, setPromptIgnored] = useState(() => {
    try {
      return typeof localStorage !== 'undefined' && localStorage.getItem(ignoreKey) === 'true';
    }
    catch {
      return false;
    }
  });
  const ignorePrompt = () => {
    try {
      if (typeof localStorage !== 'undefined') localStorage.setItem(ignoreKey, 'true');
    }
    catch {
      // Storage is a convenience for this prompt, never a workflow gate.
    }
    setPromptIgnored(true);
  };
  const topBacklog = pickTopBacklog(readyBacklog, tasks.active.length > 0, promptIgnored);

  const fallbackTimeline: TrunkActivityItemDto[] = pane.activity.generatedAt === 0
    ? [
        ...tasks.active.map(task => fallbackTaskActivity(task, 'running')),
        ...tasks.closed.map(task => fallbackTaskActivity(task, 'done')),
        ...pane.signals.map<TrunkActivityItemDto>(signal => ({
          id: `signal:${signal.id}`,
          kind: signal.iconKind,
          title: signal.title,
          summary: signal.body,
          status: signal.readAt === null ? 'unread' : 'read',
          itemPath: signal.sourceUrl,
          taskId: signal.taskId,
          sessionId: null,
          occurredAt: signal.createdAt,
          actionable: false,
        })),
      ]
    : [];
  const timeline = pane.activity.generatedAt === 0 ? fallbackTimeline : pane.activity.timeline;
  const activityCount = pane.activity.pinned.length + timeline.length;

  const runningSession = pane.activity.timeline.find(item =>
    item.kind === 'session' && item.status === 'running' && item.sessionId !== null);
  const topBar = (
    <TopBar className={paneOpen ? 'trunk-topbar has-pane' : 'trunk-topbar'}>
      <TopBarTitle>{thread.title}</TopBarTitle>
      {(thread.status === 'RUNNING' || runningSession !== undefined) && (
        <span className="trunk-agent-status"><i />agent running</span>
      )}
      {thread.branch !== null && thread.branch !== undefined && (
        <span className="trunk-branch-chip">{thread.branch}</span>
      )}
      <Grow />
      {runningSession?.sessionId !== null && runningSession !== undefined && (
        <button type="button" className="trunk-pause-agent" onClick={() => {
          void workspaceApi.sessionAction(runningSession.sessionId!, 'pause').then(() => pane.refresh());
        }}>Pause agent</button>
      )}
      {!paneOpen && (
        <IconBtn active={false} ariaLabel="Toggle right pane"
          onClick={() => setPaneOpen(true)}>◧</IconBtn>
      )}
    </TopBar>
  );

  const openActivity = (item: TrunkActivityItemDto) => {
    if (item.kind === 'question' && item.itemPath === null) {
      document.querySelector('.trunk-questions')?.scrollIntoView({ block: 'center' });
      return;
    }
    if (item.taskId !== null && item.sessionId === null && onOpenTask !== undefined) {
      onOpenTask(item.taskId);
      return;
    }
    if (item.itemPath?.startsWith('#/') === true) {
      window.location.hash = item.itemPath;
      return;
    }
    if (item.itemPath?.startsWith('http') === true) {
      void window.bridge?.openInAppBrowser(item.itemPath);
    }
  };

  return (
    <Shell collapsed={collapsed} fullWidth={sidebar === undefined}>
      {sidebar}
      <Main topBar={topBar}>
        <div
          ref={bodyRef}
          className={paneOpen ? 'body trunk-body with-pane' : 'body trunk-body'}
          style={paneOpen ? { gridTemplateColumns: `minmax(0, 1fr) 1px ${paneWidth}px` } : undefined}
        >
          <div className="conv-col">
            <div className="conv-index-host">
              {conversation}
              {conversationIndex}
            </div>
            {!hideConversationPrompts && openQuestions.length > 0 && (
              <div className="trunk-questions">
                {openQuestions.map((question, index) => (
                  <AskUserQuestionCard
                    key={question.id}
                    question={question.question}
                    context={question.context}
                    options={question.options}
                    allowFreeForm={question.allowFreeForm}
                    index={index + 1}
                    total={openQuestions.length}
                    onAnswer={(optionId, freeForm) => {
                      void pane.answerQuestion(question.id, optionId, freeForm);
                    }}
                  />
                ))}
              </div>
            )}
            {!hideConversationPrompts && topBacklog !== undefined && (
              <BacklogPrompt
                title={topBacklog.title}
                body={topBacklog.body}
                tags={topBacklog.tags}
                onApprove={() => { void pane.startDevelopment(topBacklog.id); }}
                onIgnore={ignorePrompt}
                onDrop={() => { void pane.skip(topBacklog.id); }}
              />
            )}
            {conversationFooter ?? (
              <>
                <InlineChips
                  chips={[{
                    icon: '◫',
                    label: 'Activity',
                    count: activityCount,
                    countColor: pane.activity.pinned.length > 0 ? 'red' : 'acc',
                    active: paneOpen,
                    onClick: () => setPaneOpen(open => !open),
                  }]}
                />
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
              </>
            )}
          </div>
          {paneOpen && <ResizeHandle onResize={onResize} className="pane-resize" ariaLabel="Resize the activity pane" />}
          {paneOpen && (
            <RightPane>
              <div className="trunk-activity__header">
                <strong>Activity</strong>
                <span>this thread</span>
                <button type="button" aria-label="Close activity"
                  onClick={() => setPaneOpen(false)}><ChevronRightIcon /></button>
              </div>
              <RightPane.Content flush>
                <TrunkActivityFeed
                  pinned={pane.activity.pinned}
                  timeline={timeline}
                  loading={pane.loading}
                  error={pane.error}
                  formatTime={formatTime}
                  onOpen={openActivity}
                />
              </RightPane.Content>
              <div className="trunk-activity__footer">
                <span>
                  {`${pane.activity.taskCount ?? tasks.active.length + tasks.closed.length} tasks`
                    + ` · ${pane.activity.pullRequestCount ?? 0} PR`
                    + ` · $${((pane.activity.costUsdMilli ?? 0) / 1000).toFixed(2)} this thread`}
                </span>
                <button type="button">Thread settings</button>
              </div>
            </RightPane>
          )}
        </div>
      </Main>
    </Shell>
  );
}

function TrunkActivityFeed({
  pinned,
  timeline,
  loading,
  error,
  formatTime,
  onOpen,
}: {
  pinned: TrunkActivityItemDto[];
  timeline: TrunkActivityItemDto[];
  loading: boolean;
  error: string | null;
  formatTime: (ms: number) => string;
  onOpen: (item: TrunkActivityItemDto) => void;
}) {
  if (loading && pinned.length === 0 && timeline.length === 0) {
    return <div className="trunk-activity__empty">Loading activity…</div>;
  }
  if (error !== null && pinned.length === 0 && timeline.length === 0) {
    return <div className="trunk-activity__empty is-error">{error}</div>;
  }
  if (pinned.length === 0 && timeline.length === 0) {
    return <div className="trunk-activity__empty">Activity from sessions and linked work will appear here.</div>;
  }
  return (
    <div className="trunk-activity">
      {pinned.length > 0 && (
        <section className="trunk-activity__section">
          <span className="trunk-activity__section-title">Needs you</span>
          <div className="trunk-activity__pinned">
            {pinned.map(item => (
              <ActivityRow key={item.id} item={item} formatTime={formatTime} onOpen={onOpen} pinned />
            ))}
          </div>
        </section>
      )}
      <div className="trunk-activity__timeline">
        {timeline.map(item => (
          <ActivityRow key={item.id} item={item} formatTime={formatTime} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}

function ActivityRow({
  item,
  formatTime,
  onOpen,
  pinned = false,
}: {
  item: TrunkActivityItemDto;
  formatTime: (ms: number) => string;
  onOpen: (item: TrunkActivityItemDto) => void;
  pinned?: boolean;
}) {
  const canOpen = item.itemPath !== null || item.taskId !== null
    || item.sessionId !== null || (pinned && item.actionable);
  const content = pinned ? (
    <>
      <span className={`trunk-activity__icon is-${activityTone(item.kind)}`} aria-hidden>
        {activityIcon(item.kind, pinned)}
      </span>
      <span className="trunk-activity__title">{item.title}</span>
      <span className="trunk-activity__action">
        {item.kind === 'question' ? 'Jump' : 'Open'}
      </span>
    </>
  ) : (
    <>
      <span className={`trunk-activity__icon is-${activityTone(item.kind)}`} aria-hidden>
        {activityIcon(item.kind, pinned)}
      </span>
      <span className="trunk-activity__body">
        <span className="trunk-activity__title">{item.title}</span>
        {item.summary !== null && item.summary.length > 0 && (
          <span className={`trunk-activity__summary${item.kind === 'memory' ? ' is-link' : ''}`}>
            {activitySummaryContent(item)}
          </span>
        )}
      </span>
      <time>{formatTime(item.occurredAt)}</time>
    </>
  );
  const className = `trunk-activity__row is-${item.kind}${pinned ? ' is-pinned' : ''}`;
  return (
    <div
      className={className}
      role={canOpen ? 'button' : undefined}
      tabIndex={canOpen ? 0 : undefined}
      onClick={canOpen ? () => onOpen(item) : undefined}
      onKeyDown={canOpen ? event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen(item);
        }
      } : undefined}
    >
      {content}
    </div>
  );
}

function activitySummaryContent(item: TrunkActivityItemDto): ReactNode {
  if (item.summary === null) return null;
  if (item.kind === 'session') {
    return <>{item.summary} · <b>watch</b></>;
  }
  if (item.kind === 'backlog') {
    return <>{item.summary} · <b>open</b></>;
  }
  if (item.kind === 'task') {
    const separator = item.summary.indexOf(' · ');
    if (separator >= 0) {
      return (
        <>
          {item.summary.slice(0, separator)} ·
          {' '}<span className="trunk-activity__mono">{item.summary.slice(separator + 3)}</span>
        </>
      );
    }
  }
  return item.summary;
}

function fallbackTaskActivity(task: TaskCardData, status: string): TrunkActivityItemDto {
  return {
    id: `task:${task.id}`,
    kind: 'task',
    title: task.title,
    summary: task.status,
    status,
    itemPath: null,
    taskId: task.id,
    sessionId: null,
    occurredAt: Date.now(),
    actionable: false,
  };
}

function activityIcon(kind: string, pinned = false): ReactNode {
  const shared = {
    width: 13,
    height: 13,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  switch (kind) {
    case 'question':
      return <svg {...shared}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        <path d="M12 8v3M12 13.5h.01" /></svg>;
    case 'approval':
    case 'pull-request':
      return <svg {...shared}><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" />
        <path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" /></svg>;
    case 'session':
      return <svg {...shared}><path d="m4 17 6-6-6-6M12 19h8" /></svg>;
    case 'task':
      return <svg {...shared} strokeWidth="1.9"><circle cx="18" cy="18" r="2.6" />
        <circle cx="6" cy="6" r="2.6" /><path d="M6 21V9a9 9 0 0 0 9 9" /></svg>;
    case 'review':
      if (pinned) {
        return <svg {...shared}><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" />
          <path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" /></svg>;
      }
      return <svg {...shared} strokeWidth="2"><path d="M20 6 9 17l-5-5" /></svg>;
    case 'backlog':
      return <svg {...shared} strokeWidth="1.7"><path d="M22 12h-6l-2 3h-4l-2-3H2" />
        <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></svg>;
    case 'ci':
      return <svg {...shared} strokeWidth="2"><path d="M18 6 6 18M6 6l12 12" /></svg>;
    case 'memory':
      return <svg {...shared} strokeWidth="1.6"><path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />
        <path d="M12 3v18" /></svg>;
    default:
      return <span>•</span>;
  }
}

function activityTone(kind: string): string {
  switch (kind) {
    case 'question':
    case 'approval': return 'amber';
    case 'task': return 'violet';
    case 'pull-request':
      return 'blue';
    case 'session':
    case 'review': return 'green';
    case 'ci': return 'red';
    default: return 'slate';
  }
}

function ChevronRightIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}

function defaultActivityTime(ms: number): string {
  const delta = Math.max(0, Date.now() - ms);
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (delta < minute) return 'now';
  if (delta < hour) return `${Math.floor(delta / minute)}m`;
  if (delta < day) return `${Math.floor(delta / hour)}h`;
  if (delta < 7 * day) return `${Math.floor(delta / day)}d`;
  return new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
