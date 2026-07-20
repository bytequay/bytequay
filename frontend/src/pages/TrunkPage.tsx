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
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import ResizeHandle from '../ResizeHandle';
import type { BacklogItemDto, PullRequestDto } from '../types';
import { AskUserQuestionCard, BacklogPrompt, pickTopBacklog } from '../ui/conv';
import { Composer, Main, Shell, usePaneWidth } from '../ui/shell';
import type { TaskCardData } from '../ui/pane';
import {
  PullRequestBranchIcon, TrunkLineIcon,
} from '../ui/workspace/WorkspacePageChrome';
import {
  workspaceApi,
  type TrunkActivityItemDto,
  type WorkspaceBacklogItemDto,
  type WorkspaceOverviewDto,
  type WorkspaceSessionDto,
} from '../workspace/workspaceApi';
import ProviderUsagePanel from '../workspace/ProviderUsagePanel';
import { useTrunkPane } from './useTrunkPane';

const READY_BACKLOG_STATUSES = new Set(['open', 'created']);
const OVERVIEW_WIDTH_KEY = 'bq.trunkOverviewWidth';

/**
 * Locked trunk page. The conversation and every live gate remain owned by the
 * existing trunk hooks; only their shell is translated to the selected 1b
 * design, including the fixed workspace overview at the right.
 */
export function TrunkPage({
  threadId, thread, sidebar, conversation, conversationIndex, collapsed = false, composer,
  tasks, onOpenTask, formatTime = defaultActivityTime, conversationFooter,
  historyTasks, hideConversationPrompts = false, onResume, resuming = false, resumeError = null,
}: {
  threadId: string;
  thread: {
    title: string;
    createdLabel?: string;
    status?: string;
    errorMessage?: string | null;
    branch?: string | null;
    workspaceId?: string;
    repository?: string;
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
  /** Completed cuts preceding the one expanded in the conversation. */
  historyTasks?: TaskCardData[];
  onOpenTask?: (id: string) => void;
  formatTime?: (ms: number) => string;
  conversationFooter?: ReactNode;
  hideConversationPrompts?: boolean;
  onResume?: () => void;
  resuming?: boolean;
  resumeError?: string | null;
}) {
  const pane = useTrunkPane(threadId);
  const panel = useWorkspacePanelData(thread.workspaceId);
  const [paneOpen, setPaneOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth(OVERVIEW_WIDTH_KEY, 318, 240, 520);

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
  const runningSession = timeline.find(item =>
    item.kind === 'session' && item.status === 'running' && item.sessionId !== null);

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

  const repository = panel.overview?.repository.fullName ?? thread.repository ?? 'workspace';
  const mergedCount = tasks.closed.length;
  const taskCount = pane.activity.taskCount ?? tasks.active.length + tasks.closed.length;
  const pullRequestCount = pane.activity.pullRequestCount
    ?? [...tasks.active, ...tasks.closed].filter(task => task.prNumber !== undefined).length;
  const cost = (pane.activity.costUsdMilli ?? 0) / 1000;
  const trunkUsage = sumUsage(panel.sessions.filter(session => session.trunkId === threadId));
  const compactHistoryTasks = historyTasks
    ?? tasks.closed.slice(0, Math.max(0, tasks.closed.length - 1));

  const topBar = (
    <div className="trunk-page-v2__topbar">
      <span className="trunk-page-v2__topbar-icon"><TrunkLineIcon /></span>
      <strong>{thread.title}</strong>
      <span>trunk · {repository} · {mergedCount} tasks merged</span>
      <button type="button" title="Toggle workspace panel" aria-label="Toggle workspace panel"
        onClick={() => setPaneOpen(open => !open)}><PanelIcon /></button>
    </div>
  );

  return (
    <div className="trunk-page-v2">
      <Shell collapsed={collapsed} fullWidth={sidebar === undefined}>
        {sidebar}
        <Main topBar={topBar}>
          <div ref={bodyRef} className={paneOpen ? 'trunk-page-v2__body has-panel' : 'trunk-page-v2__body'}>
            <div className="conv-col trunk-page-v2__conversation">
              <div className="trunk-page-v2__conversation-intro">
                <div className="trunk-page-v2__trunk-head">
                  <span><TrunkLineIcon /></span>
                  <strong>Trunk</strong>
                  <small>tasks branch off this line and merge back when shipped</small>
                  <button type="button">History</button>
                </div>
                {compactHistoryTasks.map(task => (
                  <button type="button" className="trunk-page-v2__history-row" key={task.id}
                    onClick={() => onOpenTask?.(task.id)}>
                    <span className="trunk-page-v2__history-rail"><PullRequestBranchIcon size={9} /></span>
                    <span className="trunk-page-v2__history-content">
                      <span>{task.title}</span>
                      <small>merged{task.prNumber === undefined ? '' : ` · PR #${task.prNumber}`}</small>
                      <ChevronRightIcon />
                    </span>
                  </button>
                ))}
              </div>
              {thread.status === 'ERRORED' && (
                <div className="trunk-page-v2__error" role="alert">
                  <span className="trunk-page-v2__error-icon" aria-hidden>!</span>
                  <span className="trunk-page-v2__error-copy">
                    <strong>Agent stopped</strong>
                    <small>{thread.errorMessage ?? 'The agent process exited before it could reply.'}</small>
                    {resumeError !== null && <small>{resumeError}</small>}
                  </span>
                  {onResume !== undefined && (
                    <button type="button" onClick={onResume} disabled={resuming}>
                      {resuming ? 'Resuming…' : 'Resume thread'}
                    </button>
                  )}
                </div>
              )}
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
                <Composer
                  variant="workspace-v2"
                  value={composer.value}
                  onChange={composer.onChange}
                  onSubmit={composer.onSubmit}
                  busy={composer.busy}
                  queueWhenBusy={composer.queueWhenBusy}
                  modePill={composer.modePill}
                  placeholder={composer.placeholder}
                  images={composer.images}
                  onImagesChange={composer.onImagesChange}
                  meta={`${taskCount} tasks · ${pullRequestCount} PRs · $${cost.toFixed(2)} this thread`}
                  usage={{ tokensIn: trunkUsage.tokensIn, tokensOut: trunkUsage.tokensOut }}
                />
              )}
            </div>
            {paneOpen && (
              <WorkspaceOverviewPanel
                workspaceId={thread.workspaceId}
                workspaceName={panel.overview?.workspace.name ?? 'Workspace'}
                overview={panel.overview}
                sessions={panel.sessions}
                trunkUsage={trunkUsage}
                pullRequests={panel.pullRequests}
                workspaceBacklog={panel.backlog}
                trunkBacklog={readyBacklog}
                pinned={pane.activity.pinned}
                timeline={timeline}
                runningSession={runningSession}
                threadTitle={thread.title}
                formatTime={formatTime}
                onOpenActivity={openActivity}
                onCollapse={() => setPaneOpen(false)}
                onRefresh={pane.refresh}
                width={paneWidth}
                onResize={onResize}
              />
            )}
          </div>
        </Main>
      </Shell>
    </div>
  );
}

function WorkspaceOverviewPanel({
  workspaceId,
  workspaceName,
  overview,
  sessions,
  trunkUsage,
  pullRequests,
  workspaceBacklog,
  trunkBacklog,
  pinned,
  timeline,
  runningSession,
  threadTitle,
  formatTime,
  onOpenActivity,
  onCollapse,
  onRefresh,
  width,
  onResize,
}: {
  workspaceId?: string;
  workspaceName: string;
  overview: WorkspaceOverviewDto | null;
  sessions: WorkspaceSessionDto[];
  trunkUsage: ModelUsage;
  pullRequests: PullRequestDto[];
  workspaceBacklog: WorkspaceBacklogItemDto[];
  trunkBacklog: BacklogItemDto[];
  pinned: TrunkActivityItemDto[];
  timeline: TrunkActivityItemDto[];
  runningSession?: TrunkActivityItemDto;
  threadTitle: string;
  formatTime: (ms: number) => string;
  onOpenActivity: (item: TrunkActivityItemDto) => void;
  onCollapse: () => void;
  onRefresh: () => void;
  width: number;
  onResize: (clientX: number) => void;
}) {
  const needsItem = pinned[0];
  const runningItem = runningSession ?? timeline.find(item => item.status === 'running');
  const needsTrunk = overview?.today.needsYou[0];
  const runningTrunk = overview?.today.running[0];
  const openPullRequests = pullRequests.filter(pr => pr.state !== 'closed' && pr.state !== 'merged').slice(0, 3);
  const backlog = workspaceBacklog.length > 0 ? workspaceBacklog.slice(0, 2) : trunkBacklog.slice(0, 2);
  const pullRequestTotal = overview?.sidebarCounts.pullRequests ?? openPullRequests.length;
  const runningSessionId = runningSession?.sessionId;
  const midnight = new Date();
  midnight.setHours(0, 0, 0, 0);
  const todayUsage = sumUsage(sessions.filter(session => session.startedAt >= midnight.getTime()));

  const openPath = (suffix: string) => {
    if (workspaceId !== undefined) window.location.hash = `#/workspace/${workspaceId}/${suffix}`;
  };

  return (
    <aside className="trunk-page-v2__overview" style={{ width }}>
      <ResizeHandle
        className="trunk-page-v2__overview-resize"
        ariaLabel="Resize workspace panel"
        onResize={onResize}
      />
      <div className="trunk-page-v2__overview-head">
        <span>{workspaceName.charAt(0).toUpperCase() || 'B'}</span>
        <strong>{workspaceName}</strong>
        <small>WORKSPACE</small>
        <button type="button" title="Collapse panel" aria-label="Collapse workspace panel" onClick={onCollapse}>
          <CollapsePanelIcon />
        </button>
      </div>
      <div className="trunk-page-v2__overview-scroll">
        <section>
          <h3>NEEDS YOU</h3>
          {(needsItem !== undefined || needsTrunk !== undefined) ? (
            <div className="trunk-page-v2__needs-card">
              <span><CommentIcon /></span>
              <span>
                <strong>{needsItem?.title ?? needsTrunk?.title ?? 'Agent needs your input'}</strong>
                <small>{needsItem === undefined
                  ? `${threadTitle} · ${needsTrunk === undefined ? 'now' : formatTime(needsTrunk.updatedAt)}`
                  : `${threadTitle} · ${formatTime(needsItem.occurredAt)}`}</small>
              </span>
              <button type="button" onClick={() => {
                if (needsItem !== undefined) onOpenActivity(needsItem);
                else if (needsTrunk !== undefined) openPath(`trunks/${needsTrunk.id}`);
              }}>Jump</button>
            </div>
          ) : <p className="trunk-page-v2__overview-empty">Nothing needs attention.</p>}
        </section>

        <section>
          <h3>RUNNING NOW</h3>
          {(runningItem !== undefined || runningTrunk !== undefined) ? (
            <div className="trunk-page-v2__running-card">
              {runningSessionId !== null && runningSessionId !== undefined ? (
                <button type="button" className="trunk-page-v2__running-dot" aria-label="Pause agent"
                  title="Pause agent" onClick={() => {
                    void workspaceApi.sessionAction(runningSessionId, 'pause').then(() => onRefresh());
                  }} />
              ) : <i />}
              <span>
                <strong>{runningItem?.title ?? runningTrunk?.title ?? 'Review session'}</strong>
                <small>{runningItem?.summary ?? `${runningTrunk?.provider ?? 'agent'} · ${runningTrunk?.model ?? 'working'}`}</small>
              </span>
              <button type="button" onClick={() => {
                if (runningItem !== undefined) onOpenActivity(runningItem);
                else if (runningTrunk !== undefined) openPath(`trunks/${runningTrunk.id}`);
              }}>Watch</button>
            </div>
          ) : <p className="trunk-page-v2__overview-empty">No sessions running.</p>}
        </section>

        <section>
          <div className="trunk-page-v2__overview-title-row">
            <h3>OPEN PRS</h3>
            <button type="button" onClick={() => openPath('prs')}>View all {pullRequestTotal}</button>
          </div>
          {openPullRequests.length > 0 ? (
            <div className="trunk-page-v2__prs">
              {openPullRequests.map(pr => (
                <button type="button" key={pr.id} onClick={() => openPath(`prs/${pr.number}`)}>
                  <span className="trunk-page-v2__pr-icon"><OpenPullRequestIcon /></span>
                  <span>{pr.title}</span>
                  <small>#{pr.number}</small>
                  <i style={{ background: ciColor(pr.ciStatus) }} title={pr.ciStatus ?? 'CI pending'} />
                </button>
              ))}
            </div>
          ) : <p className="trunk-page-v2__overview-empty">No open pull requests.</p>}
        </section>

        <section>
          <h3>BACKLOG</h3>
          {backlog.length > 0 ? (
            <div className="trunk-page-v2__backlog">
              {backlog.map(item => (
                <div key={item.id}><span />{item.title}</div>
              ))}
            </div>
          ) : <p className="trunk-page-v2__overview-empty">Backlog is clear.</p>}
        </section>

        <section>
          <h3>USAGE</h3>
          <ProviderUsagePanel />
          <h4 className="trunk-page-v2__usage-subtitle">MODEL ACTIVITY</h4>
          <div className="trunk-page-v2__usage">
            <div><span>Input</span><strong>{formatTokens(trunkUsage.tokensIn)}</strong></div>
            <div><span>Output</span><strong>{formatTokens(trunkUsage.tokensOut)}</strong></div>
            <div><span>Today</span><strong>{formatTokens(todayUsage.tokensIn + todayUsage.tokensOut)} · {todayUsage.runs} {todayUsage.runs === 1 ? 'run' : 'runs'}</strong></div>
          </div>
        </section>
      </div>
    </aside>
  );
}

type WorkspacePanelData = {
  overview: WorkspaceOverviewDto | null;
  pullRequests: PullRequestDto[];
  backlog: WorkspaceBacklogItemDto[];
  sessions: WorkspaceSessionDto[];
};

function useWorkspacePanelData(workspaceId: string | undefined): WorkspacePanelData {
  const [data, setData] = useState<WorkspacePanelData>({
    overview: null, pullRequests: [], backlog: [], sessions: [],
  });
  const load = useCallback(async () => {
    if (workspaceId === undefined || window.bridge?.workspaceApi === undefined) return;
    const [overview, pullRequests, backlog, sessions] = await Promise.allSettled([
      workspaceApi.overview(workspaceId),
      workspaceApi.pullRequests(workspaceId),
      workspaceApi.backlog(workspaceId),
      workspaceApi.sessions(workspaceId),
    ]);
    setData(current => ({
      overview: overview.status === 'fulfilled' && isWorkspaceOverview(overview.value)
        ? overview.value : current.overview,
      pullRequests: pullRequests.status === 'fulfilled' && Array.isArray(pullRequests.value)
        ? pullRequests.value : current.pullRequests,
      backlog: backlog.status === 'fulfilled' && Array.isArray(backlog.value)
        ? backlog.value : current.backlog,
      sessions: sessions.status === 'fulfilled' && Array.isArray(sessions.value)
        ? sessions.value : current.sessions,
    }));
  }, [workspaceId]);

  useEffect(() => {
    setData({ overview: null, pullRequests: [], backlog: [], sessions: [] });
    void load();
    const timer = window.setInterval(() => { void load(); }, 5000);
    return () => window.clearInterval(timer);
  }, [load]);
  return data;
}

type ModelUsage = { tokensIn: number; tokensOut: number; runs: number };

function sumUsage(sessions: WorkspaceSessionDto[]): ModelUsage {
  return sessions.reduce<ModelUsage>((usage, session) => ({
    tokensIn: usage.tokensIn + session.tokensIn,
    tokensOut: usage.tokensOut + session.tokensOut,
    runs: usage.runs + 1,
  }), { tokensIn: 0, tokensOut: 0, runs: 0 });
}

function formatTokens(tokens: number): string {
  return `${tokens.toLocaleString('en-US')} tokens`;
}

function isWorkspaceOverview(value: unknown): value is WorkspaceOverviewDto {
  return value !== null && typeof value === 'object'
    && 'sidebarCounts' in value && 'today' in value && 'repository' in value;
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

function ciColor(status: string | null): string {
  const normalized = status?.toLowerCase() ?? '';
  if (normalized.includes('success') || normalized.includes('pass')) return '#2da44e';
  if (normalized.includes('fail') || normalized.includes('error')) return '#cf222e';
  return '#d4a72c';
}

function PanelIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M15 4v16" /></svg>;
}

function CollapsePanelIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /><path d="M4 4v16" transform="translate(14 0)" /></svg>;
}

function ChevronRightIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>;
}

function CommentIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>;
}

function OpenPullRequestIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="5.5" r="2.4" /><circle cx="6" cy="18.5" r="2.4" /><circle cx="18" cy="18.5" r="2.4" /><path d="M6 8v8" /><path d="M11.5 5.5H15a3 3 0 0 1 3 3V16" /></svg>;
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
